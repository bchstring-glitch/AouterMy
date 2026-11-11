# =========================================================================
# :router-api Consumer 混淆保护规则
# =========================================================================

# 保持接口与反射基础属性
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature

# =========================================================================
# 路由映射表与反射装载器混淆保护规则 (R8 / ProGuard Keep Rules)
# =========================================================================

# 1. 保持路由表装载接口定义及其方法
-keep interface com.shanghaizhida.router.IRouteLoader {
    public *;
}

# 2. 保持由 KSP 编译器动态生成的路由映射表装载器类及无参构造与方法（供反射实例化与路由装配）
-keep class com.shanghaizhida.router.generated.AppRouteTableLoader {
    public <init>();
    public <methods>;
}

# 3. 兜底保护所有实现 IRouteLoader 接口的类及其构造函数（兼顾未来多模块扩展与自定义装载器）
-keep class * implements com.shanghaizhida.router.IRouteLoader {
    public <init>();
    public <methods>;
}
