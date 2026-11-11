package com.shanghaizhida.router

/**
 * 路由目标 Activity 注解
 *
 * @param path 路由映射路径，如 "/market/contract_detail"
 * @param desc 页面描述或用途说明
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Route(
    val path: String,
    val desc: String = ""
)
