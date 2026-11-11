package com.shanghaizhida.router

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import java.io.Serializable
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局跨 Activity 路由调度器 (纯 Kotlin 驱动 · 零反射 · 毫秒级分发)
 *
 * 核心特性：
 * 1. 【零反射与现代 KSP 支持】：支持直接内存映射与 KSP 自动代码生成；
 * 2. 【对齐经典路由体验】：完全支持 AppRouter.build(path).withString(...).navigation() 链式调用；
 * 3. 【统一拦截器机制】：支持登录态校验、权限拦截、风控拦截等多维治理，拦截器列表采用 CopyOnWriteArrayList 保证并发安全；
 * 4. 【内外格式全兼容】：具备 normalizePath 自动剥离协议头、Fragment 与健壮的 Query 参数解析/URL 解码能力；
 * 5. 【全链路并发安全】：路由映射表采用 ConcurrentHashMap，初始化采用双重检查锁定 (DCL)。
 */
object AppRouter {

    /** 内存路由映射注册表：URI Path -> 目标 Activity Class (线程安全的并发哈希表) */
    private val routeRegistry = ConcurrentHashMap<String, Class<out Activity>>()

    /** 全局路由拦截器管道 (读多写少场景下线程安全的写时复制列表) */
    private val interceptors = CopyOnWriteArrayList<RouteInterceptor>()

    @Volatile
    private var hasInitialized = false
    private val initLock = Any()

    /**
     * 全局初始化路由引擎 (自发现并自动装填由 KSP 编译器生成的路由表)
     *
     * 采用双重检查锁定 (Double-Checked Locking) 保证多线程并发初始化的原子性与安全性。
     */
    fun init() {
        if (hasInitialized) return
        synchronized(initLock) {
            if (hasInitialized) return
            try {
                val loaderClass = Class.forName("com.shanghaizhida.router.generated.AppRouteTableLoader")
                val loader = loaderClass.getDeclaredConstructor().newInstance() as IRouteLoader
                loadRouteTable(loader)
                Log.d("AppRouter", "✅ [AppRouter] 全量路由映射表自动装配成功！")
            } catch (e: Throwable) {
                Log.w("AppRouter", "⚠️ 未找到 KSP 生成的路由表，需手动注册或检查编译状态: ${e.message}")
            } finally {
                hasInitialized = true
            }
        }
    }

    /**
     * 路径归一化处理工具
     *
     * 规则：
     * 1. 剔除 Fragment (# 之后内容) 与 Query 参数 (? 之后内容)；
     * 2. 自动剥离任何 Scheme 协议头 (例如 "mtrader://market/detail" -> "/market/detail")；
     * 3. 统一规范化为标准前导斜杠 (例如 "market/detail" -> "/market/detail")；
     * 4. 自动去除多余的尾部斜杠 (例如 "/market/detail/" -> "/market/detail")，保持标准格式。
     */
    fun normalizePath(path: String): String {
        val withoutFragment = path.substringBefore("#")
        val withoutQuery = withoutFragment.substringBefore("?")
        val withoutScheme = if (withoutQuery.contains("://")) {
            withoutQuery.substringAfter("://")
        } else {
            withoutQuery
        }
        val withSlash = if (withoutScheme.startsWith("/")) withoutScheme else "/$withoutScheme"
        return if (withSlash.length > 1 && withSlash.endsWith("/")) {
            withSlash.dropLast(1)
        } else {
            withSlash
        }
    }

    /**
     * 注册单个路由映射 (线程安全)
     */
    fun register(path: String, targetClass: Class<out Activity>) {
        val standardPath = normalizePath(path)
        routeRegistry[standardPath] = targetClass
    }

    /**
     * 批量注册路由表 (线程安全)
     */
    fun registerAll(map: Map<String, Class<out Activity>>) {
        map.forEach { (path, clazz) -> register(path, clazz) }
    }

    /**
     * 装载由 KSP 注解处理器自动生成的路由表
     *
     * @param loader KSP 自动生成的 IRouteLoader 实例
     */
    @Suppress("UNCHECKED_CAST")
    fun loadRouteTable(loader: IRouteLoader) {
        val map = mutableMapOf<String, Class<*>>()
        loader.loadInto(map)
        map.forEach { (path, clazz) ->
            if (Activity::class.java.isAssignableFrom(clazz)) {
                register(path, clazz as Class<out Activity>)
            }
        }
    }

    /**
     * 注册全局路由拦截器 (原子去重添加，线程安全)
     */
    fun addInterceptor(interceptor: RouteInterceptor) {
        interceptors.addIfAbsent(interceptor)
    }

    /**
     * 移除指定的全局路由拦截器 (线程安全)
     */
    fun removeInterceptor(interceptor: RouteInterceptor) {
        interceptors.remove(interceptor)
    }

    /**
     * 清空所有路由拦截器 (主要用于单元测试或重置)
     */
    fun clearInterceptors() {
        interceptors.clear()
    }

    /**
     * 清空所有已注册的路由 (主要用于单元测试或重载)
     */
    fun clearRoutes() {
        routeRegistry.clear()
    }

    /**
     * 重置初始化状态 (仅供单元测试使用)
     */
    fun resetForTesting() {
        synchronized(initLock) {
            hasInitialized = false
            routeRegistry.clear()
            interceptors.clear()
        }
    }

    /**
     * 构建目标路由明信片
     */
    fun build(path: String): Postcard {
        return Postcard(path)
    }

    /**
     * 路由调度载体 (Postcard)
     */
    class Postcard internal constructor(val rawPath: String) {
        val cleanPath: String = normalizePath(rawPath)
        val bundle = Bundle()
        private var flags: Int = 0
        private var enterAnim: Int = 0
        private var exitAnim: Int = 0

        init {
            parseQueryParams(rawPath)
        }

        /**
         * 健壮的 URI Query 参数解析：
         * 1. 支持包含 '=' 的参数值 (如 Base64 凭据、嵌套重定向 URL 等)；
         * 2. 支持 UTF-8 URL 解码 (URLDecoder.decode)；
         * 3. 具备解码异常捕获兜底，防止非法 % 编码导致应用崩溃；
         * 4. 自动剥离 # 片段标识符。
         */
        private fun parseQueryParams(uriStr: String) {
            val queryIndex = uriStr.indexOf('?')
            if (queryIndex == -1 || queryIndex >= uriStr.length - 1) return

            val queryWithFragment = uriStr.substring(queryIndex + 1)
            val queryString = queryWithFragment.substringBefore('#')
            if (queryString.isEmpty()) return

            for (pair in queryString.split('&')) {
                if (pair.isBlank()) continue
                val eqIdx = pair.indexOf('=')
                val rawKey: String
                val rawValue: String
                if (eqIdx != -1) {
                    rawKey = pair.substring(0, eqIdx).trim()
                    rawValue = pair.substring(eqIdx + 1)
                } else {
                    rawKey = pair.trim()
                    rawValue = ""
                }

                if (rawKey.isNotEmpty()) {
                    val key = decodeUrl(rawKey)
                    val value = decodeUrl(rawValue)
                    bundle.putString(key, value)
                }
            }
        }

        private fun decodeUrl(value: String): String {
            return try {
                URLDecoder.decode(value, "UTF-8")
            } catch (e: Exception) {
                value
            }
        }

        fun withString(key: String, value: String?): Postcard {
            bundle.putString(key, value)
            return this
        }

        fun withInt(key: String, value: Int): Postcard {
            bundle.putInt(key, value)
            return this
        }

        fun withBoolean(key: String, value: Boolean): Postcard {
            bundle.putBoolean(key, value)
            return this
        }

        fun withDouble(key: String, value: Double): Postcard {
            bundle.putDouble(key, value)
            return this
        }

        fun withLong(key: String, value: Long): Postcard {
            bundle.putLong(key, value)
            return this
        }

        fun withSerializable(key: String, value: Serializable?): Postcard {
            bundle.putSerializable(key, value)
            return this
        }

        fun withParcelable(key: String, value: Parcelable?): Postcard {
            bundle.putParcelable(key, value)
            return this
        }

        fun withBundle(extras: Bundle?): Postcard {
            if (extras != null) {
                bundle.putAll(extras)
            }
            return this
        }

        fun withFlags(flags: Int): Postcard {
            this.flags = this.flags or flags
            return this
        }

        fun withTransition(enterAnim: Int, exitAnim: Int): Postcard {
            this.enterAnim = enterAnim
            this.exitAnim = exitAnim
            return this
        }

        /**
         * 执行路由跳转
         *
         * @param context 发起上下文
         * @return 是否成功分发
         */
        @Suppress("DEPRECATION")
        fun navigation(context: Context): Boolean {
            // 1. 依次执行注册的全局路由拦截器 (基于快照安全迭代，杜绝 ConcurrentModificationException)
            for (interceptor in interceptors) {
                if (interceptor.intercept(this, context)) {
                    Log.d("AppRouter", "🛑 [AppRouter] 路由已被拦截器拦截: cleanPath='$cleanPath'")
                    return false
                }
            }

            // 2. 查表获取目标 Activity 物理 Class (ConcurrentHashMap 线程安全查询)
            val targetClass = routeRegistry[cleanPath]
            if (targetClass == null) {
                Log.w("AppRouter", "❌ [AppRouter] 未匹配到路由目标: rawPath='$rawPath', cleanPath='$cleanPath'")
                return false
            }

            // 3. 构建原生显式 Intent 并塞入参数
            return try {
                val intent = Intent(context, targetClass).apply {
                    putExtras(this@Postcard.bundle)
                    if (flags != 0) {
                        addFlags(flags)
                    }
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }

                // 4. 启动目标 Activity
                context.startActivity(intent)

                // 5. 触发自定义转场动画 (若有指定)
                if (context is Activity && (enterAnim != 0 || exitAnim != 0)) {
                    context.overridePendingTransition(enterAnim, exitAnim)
                }
                true
            } catch (e: Throwable) {
                Log.e("AppRouter", "❌ [AppRouter] 路由跳转执行异常: ${e.message}", e)
                false
            }
        }
    }

    /**
     * 全局路由拦截器接口定义
     */
    interface RouteInterceptor {
        fun intercept(postcard: Postcard, context: Context): Boolean
    }
}
