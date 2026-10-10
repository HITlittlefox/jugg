package com.sickworm.intellij.jugg.compiler.source.apt.processors

import com.android.tools.build.jetifier.core.config.ConfigParser
import com.android.tools.build.jetifier.processor.FileMapping
import com.android.tools.build.jetifier.processor.Processor
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.subContext
import com.sickworm.intellij.jugg.compiler.source.apt.IJuggAptProcessor
import com.sickworm.intellij.jugg.compiler.source.JavaCompilerInvoker
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerInvoker
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.FieldVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.MethodVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import com.sickworm.intellij.jugg.org.objectweb.asm.Type
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.jar.JarFile
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject

/** Runs the module's ARouter processors before language compilation, using its Gradle baseline. */
class ARouterJuggAptProcessor : IJuggAptProcessor {
    override val id = "ARouter"
    override val failClosed = true
    private val generatedRoots = linkedSetOf<File>()

    private data class ProjectProcessor(val jar: File, val names: List<String>)

    override fun ownsGeneratedFile(file: CompileFile): Boolean =
        generatedRoots.any { file.file.canonicalFile.toPath().startsWith(it.toPath()) }

    override fun process(
        context: ICompileContext,
        module: ModuleInfo,
        allCompileFiles: List<CompileFile>,
    ): List<CompileFile> {
        if (context.customCompilers.filterIsInstance<IJuggAptProcessor>().any { it.id == id }) {
            context.logger.debug("ARouter built-in processor skipped for ${module.name}: external processor configured")
            return emptyList()
        }
        try {
            return processModule(context, module, allCompileFiles)
        } catch (failure: Throwable) {
            context.logger.warn("ARouter processor failed for ${module.name}: ${failure.message}", failure)
            // Keep project details in the processor log, outside the shared compile error path.
            throw IllegalStateException("ARouter processor failed; see the ARouter warning log")
        }
    }

    /** Resolves the environment before a checkpoint can suppress processor execution. */
    private fun processModule(
        context: ICompileContext,
        module: ModuleInfo,
        allCompileFiles: List<CompileFile>,
    ): List<CompileFile> {
        val sources = module.sourceDirs.filter { it.exists() }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension in listOf("java", "kt") }.toList()
        }.distinctBy { it.canonicalPath }
        val checkpoint = File(context.incrementalDataDir, "arouter/${module.name}/${module.buildVariant}")
        val prior = readCheckpoint(checkpoint)
        val baselineSources = baselineSources(context, module)
        val declarations = sources.mapNotNull { source ->
            declarationFacts(source.readText()).takeIf { it.isNotEmpty() }?.let { source to it }
        }
        if (declarations.isEmpty() && prior == null && baselineSources.isEmpty()) return emptyList()
        val processorPath = (module.annotationProcessorDependencies + module.kaptDependencies)
            .map { it.file.canonicalFile }.distinct()
        val original = projectProcessor(processorPath, module)
        val options = module.javaAnnotationProcessorOptions.orEmpty() + module.kaptArguments.orEmpty()
        // Earlier ARouter uses moduleName; its other historical differences are not specially adapted.
        check(options.containsKey("AROUTER_MODULE_NAME") || options.containsKey("moduleName")) {
            "AROUTER_MODULE_NAME or moduleName is missing for ${module.name}"
        }
        val work = File(context.tempCompileDir, "arouter/${module.name}")
        val task = CompileTask(allCompileFiles, work, CompileStatusHolder.DEFAULT)
        val selected = selectProcessor(context, module, original, processorPath, task)
        val paths = replaceProcessorJar(processorPath, original.jar, selected.jar)
        val effectiveContext = processorContext(context, original.jar, selected.jar)
        val references = declarations.joinToString("|") { it.second }
        val facts = constantFacts(sources, references) + hierarchyFacts(sources)
        val baseline = sourceDigest(baselineSources.values.toList())
        val signature = "$baseline:${signature(declarations, facts, paths, selected, module, options)}"
        val priorNames = baselineSources.keys + if (prior?.first?.substringBefore(':') == baseline) prior.second else emptyList()
        generatedRoots += File(checkpoint, "sources").canonicalFile
        if (isCached(checkpoint, prior, signature)) {
            context.logger.debug("ARouter processor skipped for ${module.name}: declarations unchanged")
            return generatedFiles(checkpoint, prior!!.second, module)
        }
        return generate(effectiveContext, module, sources, declarations.map { it.first }, selected, paths,
            options, checkpoint, signature, priorNames.toList())
    }

    private fun projectProcessor(paths: List<File>, module: ModuleInfo): ProjectProcessor {
        val candidates = paths.mapNotNull { jar ->
            val names = processorNames(jar)
            if (names.isEmpty()) null else ProjectProcessor(jar, names)
        }
        check(candidates.size == 1) {
            "Expected one ARouter processor JAR for ${module.name}, found ${candidates.map { it.jar }}; paths=$paths"
        }
        return candidates.single()
    }

    private fun processorNames(file: File): List<String> {
        if (!file.isFile || !file.extension.equals("jar", ignoreCase = true)) return emptyList()
        return JarFile(file).use { jar ->
            val entry = jar.getJarEntry("META-INF/services/javax.annotation.processing.Processor") ?: return@use emptyList()
            val names = jar.getInputStream(entry).bufferedReader().use { reader ->
                reader.readLines().map { it.substringBefore('#').trim() }
                    .filter { it.startsWith("com.alibaba.android.arouter.compiler.processor.") }.distinct()
            }
            names.forEach { name ->
                check(jar.getJarEntry("${name.replace('.', '/')}.class") != null) {
                    "ARouter SPI class $name is missing from $file"
                }
            }
            names
        }
    }

    private fun selectProcessor(
        context: ICompileContext,
        module: ModuleInfo,
        original: ProjectProcessor,
        paths: List<File>,
        task: CompileTask,
    ): ProjectProcessor {
        val references = fragmentReferences(original.jar)
        if (references.isEmpty()) return original
        check(references.size == 1) { "ARouter processor has ambiguous Fragment references in ${original.jar}: $references" }
        val target = fragmentTarget(context, module)
        val classpath = context.getModuleDependencies(module, task).map(::File) + paths
        check(classpath.any { containsClass(it, "$target.class") }) {
            "ARouter requires $target for ${module.name}, absent from javac classpath: $classpath"
        }
        if (references.single() == target) return original
        val converted = transformProcessor(context, original.jar, target)
        return ProjectProcessor(converted, original.names)
    }

    private fun fragmentTarget(context: ICompileContext, module: ModuleInfo): String {
        val descriptors = listOf("L$ANDROIDX_FRAGMENT;", "L$SUPPORT_FRAGMENT;")
        val found = context.containsApkClass(descriptors).map { it.className }.toSet()
        val androidx = descriptors[0] in found
        val support = descriptors[1] in found
        val target = if (context.isSingleApk && support && !androidx) SUPPORT_FRAGMENT else ANDROIDX_FRAGMENT
        context.logger.debug("ARouter Fragment selection for ${module.name}: androidx=$androidx, support=$support, " +
                "singleApk=${context.isSingleApk}, target=$target; an empty query can also mean no database result")
        return target
    }

    private fun containsClass(file: File, name: String): Boolean {
        if (file.isDirectory) return File(file, name).isFile
        if (!file.isFile || !file.extension.equals("jar", ignoreCase = true)) return false
        return JarFile(file).use { it.getJarEntry(name) != null }
    }

    /** Inspects actual class references and reflection strings rather than artifact names or versions. */
    private fun fragmentReferences(file: File): Set<String> {
        val found = linkedSetOf<String>()
        fun record(value: String?) {
            listOf(ANDROIDX_FRAGMENT, SUPPORT_FRAGMENT).forEach { type ->
                if (value == type || value == type.replace('/', '.') || value?.contains("L$type;") == true) found += type
            }
        }
        val visitor = object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                record(superName)
                interfaces.orEmpty().forEach(::record)
                record(signature)
            }
            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                record(descriptor)
                record(signature)
                if (value is String) record(value)
                return null
            }
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                record(descriptor)
                record(signature)
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitLdcInsn(value: Any) {
                        record(if (value is Type) value.descriptor else value as? String)
                    }
                    override fun visitTypeInsn(opcode: Int, type: String) = record(type)
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        record(owner)
                        record(descriptor)
                    }
                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                        record(owner)
                        record(descriptor)
                    }
                }
            }
        }
        JarFile(file).use { jar ->
            jar.entries().asSequence().filter { it.name.startsWith("com/alibaba/android/arouter/compiler/") && it.name.endsWith(".class") }
                .forEach { entry -> jar.getInputStream(entry).use { ClassReader(it).accept(visitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) } }
        }
        return found
    }

    private fun transformProcessor(context: ICompileContext, original: File, target: String): File {
        val key = "${sourceDigest(listOf(original))}-$target-$JETIFIER_VERSION".replace('/', '_')
        val output = File(context.incrementalDataDir, "arouter/jetifier/$key/compiler.jar")
        if (output.isFile && fragmentReferences(output) == setOf(target) && processorNames(output) == processorNames(original)) return output
        output.parentFile.mkdirs()
        val pending = File(output.parentFile, "compiler.pending.jar")
        try {
            val config = checkNotNull(ConfigParser.loadDefaultConfig()) { "Jetifier default mapping is missing" }
            val processor = Processor.createProcessor4(config, reversedMode = target == SUPPORT_FRAGMENT)
            processor.transform2(setOf(FileMapping(original, pending)), skipLibsWithAndroidXReferences = false)
            check(pending.isFile && fragmentReferences(pending) == setOf(target)) {
                "Jetifier could not produce an ARouter processor for $target from $original"
            }
            check(processorNames(pending) == processorNames(original)) { "Jetifier changed ARouter SPI in $original" }
            Files.move(pending.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            context.logger.debug("ARouter processor converted for $target: $original -> $output")
            return output
        } finally {
            pending.delete()
        }
    }

    private fun replaceProcessorJar(paths: List<File>, original: File, selected: File): List<File> =
        paths.map { if (it.canonicalFile == original.canonicalFile) selected else it }.distinctBy { it.canonicalPath }

    private fun processorContext(context: ICompileContext, original: File, selected: File): ICompileContext {
        if (original == selected) return context
        return object : ICompileContext by context {
            override fun getModuleDependencies(moduleInfo: ModuleInfo, task: CompileTask): List<String> =
                replaceProcessorJar(context.getModuleDependencies(moduleInfo, task).map(::File), original, selected).map { it.path }
        }
    }

    /** Publishes a checkpoint only after generation and removal checks have succeeded. */
    private fun generate(
        context: ICompileContext,
        module: ModuleInfo,
        sources: List<File>,
        declarations: List<File>,
        processor: ProjectProcessor,
        paths: List<File>,
        options: Map<String, String>,
        checkpoint: File,
        signature: String,
        priorNames: List<String>,
    ): List<CompileFile> {
        val moduleName = options["AROUTER_MODULE_NAME"] ?: options.getValue("moduleName")
        val start = System.nanoTime()
        context.logger.info("ARouter processor started for $moduleName (${module.name})")
        try {
            val work = File(context.tempCompileDir, "arouter/${module.name}").apply { deleteRecursively(); mkdirs() }
            val kotlinSources = declarations.filter { it.extension == "kt" }
            val stubs = if (kotlinSources.isEmpty()) emptyList() else generateStubs(context, module, kotlinSources, paths, work)
            val input = (sources.filter { it.extension == "java" } + stubs).distinct()
            val generated = File(work, "sources").apply { mkdirs() }
            if (input.isNotEmpty()) runProcessors(context, module, input, paths, processor.names, generated, work, options)
            val names = generated.walkTopDown().filter { it.isFile && it.extension == "java" }
                .map { it.relativeTo(generated).invariantSeparatorsPath }.sorted().toList()
            val removed = priorNames - names.toSet()
            check(removed.isEmpty()) { "ARouter generated classes were removed (${removed.joinToString()}); run a full Gradle build" }
            saveCheckpoint(checkpoint, generated, signature, names)
            context.logger.info("ARouter generated ${names.size} Java sources for $moduleName")
            names.forEach { context.logger.info("ARouter generated source: ${File(checkpoint, "sources/$it").absolutePath}") }
            return generatedFiles(checkpoint, names, module)
        } finally {
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            context.logger.info("ARouter processor finished for ${module.name} in " +
                    "${String.format(Locale.ROOT, "%.2f", seconds)} seconds")
        }
    }

    /** Builds Java stubs for annotated Kotlin inputs with the module's compiler plugins. */
    private fun generateStubs(
        context: ICompileContext,
        module: ModuleInfo,
        sources: List<File>,
        processorPath: List<File>,
        work: File,
    ): List<File> {
        val stubContext = context.subContext("arouter_kapt")
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
        check(stubs.isNotEmpty()) { "KAPT produced no Java stubs for ARouter Kotlin sources in ${module.name}" }
        return stubs
    }

    /** Runs only the discovered ARouter processors with the selected project classpath. */
    private fun runProcessors(
        context: ICompileContext,
        module: ModuleInfo,
        sources: List<File>,
        processorPath: List<File>,
        processorNames: List<String>,
        generated: File,
        work: File,
        options: Map<String, String>,
    ) {
        val compiler = JavaCompilerInvoker.getJavaCompiler(context.logger)
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, null).use { manager ->
            val units = manager.getJavaFileObjectsFromFiles(sources)
            val classpath = context.getModuleDependencies(
                module, CompileTask(emptyList(), work, CompileStatusHolder.DEFAULT),
            ) + File(work, "kotlin_classes").path + processorPath.map { it.path }
            val classpathOption = classpath.joinToString(File.pathSeparator)
            val args = mutableListOf(
                "-proc:only", "-classpath", classpathOption,
                "-processorpath", classpathOption,
                "-processor", processorNames.joinToString(","),
                "-s", generated.path, "-d", File(work, "classes").apply { mkdirs() }.path,
                "-encoding", "UTF-8",
            )
            options.forEach { (key, value) -> args += "-A$key=$value" }
            val success = compiler.getTask(null, manager, diagnostics, args, null, units).call()
            check(success == true) {
                diagnostics.diagnostics.joinToString("\n") { "${it.kind}: ${it.getMessage(null)}" }
            }
        }
    }

    private fun generatedFiles(checkpoint: File, names: List<String>, module: ModuleInfo): List<CompileFile> {
        val root = File(checkpoint, "sources")
        return names.map { CompileFile(CompileFile.Type.Java, File(root, it), root, module) }
    }

    private fun readCheckpoint(folder: File): Pair<String, List<String>>? {
        val lines = File(folder, "checkpoint.txt").takeIf { it.isFile }?.readLines() ?: return null
        return lines.firstOrNull()?.let { it to lines.drop(1) }
    }

    private fun isCached(folder: File, prior: Pair<String, List<String>>?, signature: String): Boolean {
        if (prior?.first?.substringBeforeLast(':') != signature) return false
        val files = prior.second.map { File(folder, "sources/$it") }
        return files.all { it.isFile } && prior.first.substringAfterLast(':') == sourceDigest(files)
    }

    private fun saveCheckpoint(folder: File, generated: File, signature: String, names: List<String>) {
        val target = File(folder, "sources")
        target.deleteRecursively()
        generated.copyRecursively(target, overwrite = true)
        val pending = File(folder, "checkpoint.pending")
        pending.writeText((listOf("$signature:${sourceDigest(names.map { File(target, it) })}") + names).joinToString("\n"))
        Files.move(pending.toPath(), File(folder, "checkpoint.txt").toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Hashes paths and actual bytes so same-size processor edits cannot reuse cached generation. */
    private fun sourceDigest(files: List<File>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.distinctBy { it.canonicalPath }.sortedBy { it.canonicalPath }.forEach { file ->
            digest.update(file.canonicalPath.toByteArray())
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var length = input.read(buffer)
                while (length >= 0) {
                    digest.update(buffer, 0, length)
                    length = input.read(buffer)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Fingerprints source facts and the resolved processor environment for this module and variant. */
    private fun signature(
        declarations: List<Pair<File, String>>,
        facts: List<String>,
        paths: List<File>,
        processor: ProjectProcessor,
        module: ModuleInfo,
        options: Map<String, String>,
    ): String {
        val text = buildString {
            append("builtin-arouter-1:").append(module.name).append(':').append(module.buildVariant).append('\n')
            declarations.sortedBy { it.first.path }.forEach { (file, fact) -> append(file.path).append(':').append(fact).append('\n') }
            facts.forEach { append(it).append('\n') }
            append(sourceDigest(paths.filter { it.isFile })).append('\n')
            append(processor.jar.path).append(':').append(processor.names.joinToString(",")).append('\n')
            // Dependency names can be arbitrary for local files; track all library content fingerprints.
            module.libraryDependencies.sortedBy { it.file.path }.forEach { append(it.file.path).append(':').append(it.crc32).append('\n') }
            options.toSortedMap().forEach { (key, value) -> append(key).append('=').append(value).append('\n') }
            append(JETIFIER_VERSION)
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun baselineSources(context: ICompileContext, module: ModuleInfo): Map<String, File> =
        context.getGeneratedSourcePaths(module).flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "java" && it.name.contains("ARouter") }
                .map { it.relativeTo(root).invariantSeparatorsPath to it }.toList()
        }.toMap()

    private fun declarationFacts(source: String): String {
        val cleaned = stripComments(source)
        val matches = annotationMatches(cleaned).map { match ->
            normalizedFact(match.value + headerAfterAnnotation(cleaned, match))
        }.toList()
        if (matches.isEmpty()) return ""
        val packageName = Regex("(?m)^\\s*package\\s+[^;\\n]+")
            .find(cleaned)?.value?.replace(WHITESPACE, "").orEmpty()
        val imports = cleaned.lineSequence().map { it.trim() }
            .filter { it.startsWith("import ") && ROUTE_NAMES.containsMatchIn(it) }
            .map { it.replace(WHITESPACE, "") }.toList()
        return (matches + imports + packageName).sorted().joinToString("|")
    }

    /** Whitespace in literals is semantic input even when surrounding formatting is irrelevant. */
    private fun normalizedFact(source: String): String = buildString {
        var offset = 0
        STRINGS.findAll(source).forEach { literal ->
            append(source.substring(offset, literal.range.first).replace(WHITESPACE, ""))
            append(literal.value)
            offset = literal.range.last + 1
        }
        append(source.substring(offset).replace(WHITESPACE, ""))
    }

    private fun headerAfterAnnotation(source: String, annotation: MatchResult): String {
        val following = source.substring(annotation.range.last + 1).trimStart()
            .replaceFirst(OTHER_ANNOTATIONS, "")
        val firstLine = following.substringBefore('{').substringBefore(';').substringBefore('\n')
        if (CLASS_KEYWORD.containsMatchIn(firstLine)) {
            val continuation = following.substringAfter('\n', "").trimStart()
            if (continuation.startsWith("extends ") || continuation.startsWith("implements ") ||
                continuation.startsWith(":")) return firstLine + " " + continuation.substringBefore('{').substringBefore('\n')
            return firstLine
        }
        return firstLine.substringBefore('=')
    }

    /** Tracks local parent declarations reachable from annotation-bearing declarations. */
    private fun hierarchyFacts(sources: List<File>): List<String> {
        val headers = sources.flatMap { source ->
            val cleaned = stripComments(source.readText())
            val strings = STRINGS.findAll(cleaned).map { it.range }.toList()
            CLASS_HEADERS.findAll(cleaned).filter { match -> strings.none { match.range.first in it } }
                .map { it.groupValues[1] to it.value }.toList()
        }.groupBy({ it.first }, { it.second })
        val related = linkedSetOf<String>()
        val pending = sources.flatMap { source ->
            val cleaned = stripComments(source.readText())
            annotationMatches(cleaned).flatMap { parentNames(headerAfterAnnotation(cleaned, it)) }
        }.toMutableList()
        while (pending.isNotEmpty()) {
            val name = pending.removeAt(pending.lastIndex)
            if (!related.add(name)) continue
            headers[name].orEmpty().forEach { pending += parentNames(it) }
        }
        return related.flatMap { headers[it].orEmpty() }.map(::normalizedFact).sorted()
    }

    private fun parentNames(header: String): List<String> = PARENTS.findAll(header).map { match ->
        (match.groupValues[1].ifEmpty { match.groupValues[2] }).substringAfterLast('.')
    }.toList()

    private fun annotationMatches(source: String): List<MatchResult> {
        val aliases = ALIASES.findAll(source).map { it.groupValues[2] }.toList()
        val aliasMatches = aliases.flatMap { alias ->
            Regex("@(?:field:|get:|set:|property:)?${Regex.escape(alias)}\\b(?:\\s*\\([^)]*\\))?")
                .findAll(source).toList()
        }
        val imports = ROUTE_IMPORTS.findAll(source).map { it.groupValues[1] }.toSet()
        val wildcard = source.contains("import com.alibaba.android.arouter.facade.annotation.*")
        val strings = STRINGS.findAll(source).map { it.range }.toList()
        val directMatches = ANNOTATIONS.findAll(source).filter { match ->
            val name = match.value.substringAfter('@').substringBefore('(').substringAfter(':').trim()
            if (name.contains('.')) name.startsWith("com.alibaba.android.arouter.facade.annotation.")
            else name in imports || wildcard || source.contains("package com.alibaba.android.arouter.facade.annotation")
        }.toList()
        return (directMatches + aliasMatches)
            .filter { match -> strings.none { match.range.first in it } }
            .distinctBy { it.range.first }
    }

    /** Removes comments while preserving literals and line boundaries for declaration scanning. */
    private fun stripComments(source: String): String {
        val result = StringBuilder(source.length)
        var offset = 0
        while (offset < source.length) {
            when {
                source.startsWith("\"\"\"", offset) -> {
                    val end = source.indexOf("\"\"\"", offset + 3).let { if (it < 0) source.length else it + 3 }
                    result.append(source, offset, end)
                    offset = end
                }
                source[offset] == '"' || source[offset] == '\'' -> offset = appendLiteral(source, offset, result)
                source.startsWith("//", offset) -> {
                    offset = source.indexOf('\n', offset).let { if (it < 0) source.length else it }
                    result.append(' ')
                }
                source.startsWith("/*", offset) -> {
                    val end = source.indexOf("*/", offset + 2).let { if (it < 0) source.length else it }
                    result.append("\n".repeat(source.substring(offset, end).count { it == '\n' }))
                    offset = (end + 2).coerceAtMost(source.length)
                    result.append(' ')
                }
                else -> result.append(source[offset++])
            }
        }
        return result.toString()
    }

    private fun appendLiteral(source: String, start: Int, result: StringBuilder): Int {
        val quote = source[start]
        result.append(quote)
        var offset = start + 1
        while (offset < source.length) {
            val char = source[offset++]
            result.append(char)
            if (char == '\\' && offset < source.length) result.append(source[offset++])
            else if (char == quote) break
        }
        return offset
    }

    /** Tracks local constant definitions referenced by annotations or other selected constants. */
    private fun constantFacts(sources: List<File>, references: String): List<String> {
        val definitions = sources.flatMap { source ->
            val cleaned = stripComments(source.readText())
            val strings = STRINGS.findAll(cleaned).map { it.range }.toList()
            CONSTANTS.findAll(cleaned).filter { match -> strings.none { match.range.first in it } }.map {
                it.groupValues[1].ifEmpty { it.groupValues[2] } to completeConstant(cleaned, it)
            }.toList()
        }
        val selected = linkedSetOf<String>()
        var visible = references
        do {
            val next = definitions.filter { (name, _) ->
                name !in selected && Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(visible)
            }
            next.forEach { selected += it.first }
            visible += next.joinToString("|") { it.second }
        } while (next.isNotEmpty())
        return definitions.filter { it.first in selected }.map { normalizedFact(it.second) }.sorted()
    }

    private fun completeConstant(source: String, match: MatchResult): String {
        val value = StringBuilder(match.value)
        var offset = match.range.last + 1
        while (value.toString().trimEnd().endsWith('+') && offset < source.length) {
            val next = source.substring(offset).substringBefore('\n')
            value.append(next)
            offset += next.length + 1
        }
        return value.toString()
    }

    companion object {
        private const val ANDROIDX_FRAGMENT = "androidx/fragment/app/Fragment"
        private const val SUPPORT_FRAGMENT = "android/support/v4/app/Fragment"
        private const val JETIFIER_VERSION = "1.0.0-beta10"
        private val ANNOTATIONS = Regex("@(?:field:|get:|set:|property:)?(?:[A-Za-z_][A-Za-z_0-9]*\\.)*(Route|Autowired|Interceptor)\\b(?:\\s*\\([^)]*\\))?")
        private val ALIASES = Regex("(?m)^\\s*import\\s+com\\.alibaba\\.android\\.arouter\\.facade\\.annotation\\.(Route|Autowired|Interceptor)\\s+as\\s+(\\w+)")
        private val ROUTE_IMPORTS = Regex("(?m)^\\s*import\\s+com\\.alibaba\\.android\\.arouter\\.facade\\.annotation\\.(Route|Autowired|Interceptor)\\b")
        private val STRINGS = Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'")
        private val OTHER_ANNOTATIONS = Regex("^(?:@[A-Za-z_][A-Za-z_0-9.]*\\b(?:\\s*\\([^)]*\\))?\\s*)+")
        private val ROUTE_NAMES = Regex("\\b(Route|Autowired|Interceptor)\\b")
        private val WHITESPACE = Regex("\\s+")
        private val CONSTANTS = Regex("(?:const\\s+val\\s+(\\w+)(?:\\s*:\\s*[\\w.<>?]+)?|static\\s+final\\s+[\\w.<>?]+\\s+(\\w+))\\s*=\\s*[^;\\n]+")
        private val CLASS_HEADERS = Regex("\\b(?:class|interface)\\s+(\\w+)[^\\n{]*(?:\\n\\s*(?:extends|implements|:)[^\\n{]*)?")
        private val CLASS_KEYWORD = Regex("\\b(class|interface|object)\\b")
        private val PARENTS = Regex("\\b(?:extends|implements)\\s+([A-Za-z_][A-Za-z_0-9.]*)|:\\s*([A-Za-z_][A-Za-z_0-9.]*)")
    }
}
