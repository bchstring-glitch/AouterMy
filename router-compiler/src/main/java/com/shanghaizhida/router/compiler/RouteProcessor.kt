package com.shanghaizhida.router.compiler

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import java.io.OutputStreamWriter

/**
 * 路由注解 KSP 符号处理器
 *
 * 核心设计：通过字符串全限定名解耦，无需直接依赖 Android Library，保持纯 JVM 极致轻量
 */
class RouteProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    companion object {
        private const val ROUTE_ANNOTATION_NAME = "com.shanghaizhida.router.Route"
    }

    private var hasProcessed = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (hasProcessed) return emptyList()

        val routeSymbols = resolver.getSymbolsWithAnnotation(ROUTE_ANNOTATION_NAME)
        val validSymbols = routeSymbols.filterIsInstance<KSClassDeclaration>().toList()

        if (validSymbols.isEmpty()) {
            return emptyList()
        }

        hasProcessed = true
        generateRouteTable(validSymbols)
        return emptyList()
    }

    private fun generateRouteTable(classes: List<KSClassDeclaration>) {
        val packageName = "com.shanghaizhida.router.generated"
        val className = "AppRouteTableLoader"

        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true, sources = classes.mapNotNull { it.containingFile }.toTypedArray()),
            packageName = packageName,
            fileName = className
        )

        OutputStreamWriter(file, Charsets.UTF_8).use { writer ->
            writer.write("package $packageName\n\n")
            writer.write("import com.shanghaizhida.router.IRouteLoader\n\n")
            writer.write("/**\n")
            writer.write(" * 由 KSP 路由注解处理器自动生成，请勿手动修改！\n")
            writer.write(" */\n")
            writer.write("class $className : IRouteLoader {\n")
            writer.write("    override fun loadInto(registry: MutableMap<String, Class<*>>) {\n")

            classes.forEach { classDecl ->
                val routeAnnotation = classDecl.annotations.firstOrNull {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == ROUTE_ANNOTATION_NAME ||
                    it.shortName.asString() == "Route"
                }

                val pathArg = routeAnnotation?.arguments?.firstOrNull { it.name?.asString() == "path" }
                val rawPath = pathArg?.value as? String ?: ""
                val targetClass = classDecl.qualifiedName?.asString() ?: ""

                if (rawPath.isNotEmpty() && targetClass.isNotEmpty()) {
                    writer.write("        registry[\"$rawPath\"] = $targetClass::class.java\n")
                    logger.info("📍 [RouteProcessor] 映射已捕获: $rawPath -> $targetClass")
                }
            }

            writer.write("    }\n")
            writer.write("}\n")
        }
    }
}
