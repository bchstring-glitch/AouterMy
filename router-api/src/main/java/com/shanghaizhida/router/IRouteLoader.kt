package com.shanghaizhida.router

/**
 * 编译期生成的路由映射表装载接口
 */
interface IRouteLoader {
    /**
     * 将编译期扫描生成的 (Path -> Class) 路由映射装填进注册表
     */
    fun loadInto(registry: MutableMap<String, Class<*>>)
}
