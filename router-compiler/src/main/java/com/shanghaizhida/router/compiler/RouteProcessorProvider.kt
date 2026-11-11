package com.shanghaizhida.router.compiler

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

class RouteProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return RouteProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger
        )
    }
}
