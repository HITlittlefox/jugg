package com.sickworm.intellij.jugg.compiler.source.apt

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.JavaCompilerInvoker
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerInvoker
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/** Runs mixed-language aggregation through the existing KAPT and javac invokers. */
class AnnotationProcessorRunner {
    fun generate(context: ICompileContext, module: ModuleInfo, sources: List<File>,
        processorPath: List<File>, processorNames: List<String>, options: Map<String, String>, work: File): File {
        work.deleteRecursively()
        work.mkdirs()
        val kotlin = sources.filter { it.extension == "kt" }
        val stubs = if (kotlin.isEmpty()) emptyList() else generateStubs(context, module, kotlin, processorPath, work)
        val input = (sources.filter { it.extension == "java" } + stubs).distinctBy { it.canonicalPath }
        val generated = File(work, "sources").apply { mkdirs() }
        if (input.isEmpty()) return generated
        val files = input.map { CompileFile(CompileFile.Type.Java, it, it.parentFile, module) }
        val task = CompileTask(files, File(work, "classes").apply { mkdirs() }, CompileStatusHolder.DEFAULT)
        val dependencies = context.getModuleDependencies(module, task) + File(work, "kotlin_classes").apply { mkdirs() }.path + processorPath.map { it.path }
        val result = JavaCompilerInvoker().compile(context, module, task, context.logger, JavaCompilerInvoker.Options(
            isEnableApt = true, isAptOnly = true, isCanAutoRetry = false,
            aptPaths = dependencies.map(::File), aptOptions = options, dependencies = dependencies,
            processorNames = processorNames, generatedSourceDir = generated,
        ))
        check(result.isAllSuccess) { "Annotation generation failed for ${module.name}: ${result.failedFiles}" }
        return generated
    }

    /** Builds Java stubs for annotated Kotlin inputs with the module's compiler plugins. */
    private fun generateStubs(
        context: ICompileContext,
        module: ModuleInfo,
        sources: List<File>,
        processorPath: List<File>,
        work: File,
    ): List<File> {
        val stubContext = context.subContext("apt_stubs")
        val files = sources.map { source ->
            val root = module.sourceDirs.filter { source.toPath().startsWith(it.toPath()) }
                .maxByOrNull { it.path.length } ?: module.moduleRootDir
            CompileFile(CompileFile.Type.Kotlin, source, root, module)
        }
        val task = CompileTask(files, File(work, "kotlin_classes"), CompileStatusHolder.DEFAULT)
        val result = KotlinCompilerInvoker().compile(
            stubContext, module, task, context.logger,
            KotlinCompilerInvoker.Options(
                isEnableKapt = true,
                kaptMode = "stubs",
                kaptDependencies = processorPath,
                isNeedCompileCompose = module.isUseCompose == true,
                kotlinPlugins = module.kotlinPlugins.orEmpty(),
                kotlinExtensions = module.kotlinExtensions.orEmpty(),
                kotlinPluginOptions = module.kotlinPluginOptions,
            ),
        )
        check(result.isAllSuccess) { "KAPT stub generation failed for ${module.name}: ${result.failedFiles}" }
        val stubDir = File(stubContext.tempCompileDir, "kapt/stubs")
        val stubs = stubDir.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        check(stubs.isNotEmpty()) { "KAPT produced no Java stubs for annotated Kotlin sources in ${module.name}" }
        return stubs
    }

}
