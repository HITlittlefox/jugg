package com.sickworm.intellij.jugg.compiler.source.apt.processors

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.source.apt.*
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import java.util.Locale

/** Compares current-round ARouter semantics before running the project's aggregate processors. */
class ARouterJuggAptProcessor : BaseJuggAptProcessor() {
    override val id = "ARouter"
    override val failClosed = true
    private val generatedRoots = linkedSetOf<File>()
    private val gson = Gson()
    private val compatibility = ARouterProcessorCompatibility()
    private val oldInputs = ARouterBaseline()

    private data class Inputs(val version: Int, val environment: String, val sources: Map<String, SourceInput>)

    private data class SourceInput(val identities: List<String>, val fingerprint: String)

    override fun ownsGeneratedFile(file: CompileFile): Boolean =
        generatedRoots.any { file.file.canonicalFile.toPath().startsWith(it.toPath()) }

    override fun process(context: ICompileContext, module: ModuleInfo, allCompileFiles: List<CompileFile>,
        analysis: AptSourceAnalysis): List<CompileFile> {
        if (context.customCompilers.filterIsInstance<IJuggAptProcessor>().any { it.id == id }) {
            context.logger.debug("ARouter built-in processor skipped for ${module.name}: external processor configured")
            return emptyList()
        }
        val checkpoint = GeneratedSourceCheckpoint(File(context.incrementalDataDir, "arouter/${module.name}/${module.buildVariant}"))
        generatedRoots += checkpoint.sourceRoot.canonicalFile
        val currentFiles = allCompileFiles.filter { it.file.isFile && it.type in listOf(CompileFile.Type.Java, CompileFile.Type.Kotlin) && !ownsGeneratedFile(it) }
        if (currentFiles.isEmpty()) return emptyList()
        val prepared = try {
            prepare(context, module, currentFiles, checkpoint, analysis) ?: return emptyList()
        } catch (failure: Throwable) {
            warnSkipped(context, module, currentFiles.map { it.file }, failure)
            return emptyList()
        }
        try {
            return runGeneration(context, module, allCompileFiles, checkpoint, prepared)
        } catch (failure: Throwable) {
            context.logger.warn("ARouter processor failed for ${module.name}: ${failure.message}", failure)
            throw IllegalStateException("ARouter processor failed; see the ARouter warning log")
        }
    }

    private data class Preparation(val sources: List<File>, val inputs: Inputs, val baseline: String, val priorNames: List<String>)

    /** Aggregation is allowed only after reliable current-round comparison confirms a change. */
    private fun prepare(context: ICompileContext, module: ModuleInfo, files: List<CompileFile>,
        checkpoint: GeneratedSourceCheckpoint, analysis: AptSourceAnalysis): Preparation? {
        val generated = baselineSources(context, module)
        val baseline = GeneratedSourceCheckpoint.digest(generated.values.toList())
        val prior = checkpoint.read()
        val record = readInputs(checkpoint, baseline, generated, prior)
        val options = module.javaAnnotationProcessorOptions.orEmpty() + module.kaptArguments.orEmpty()
        val resolver = AptSemanticResolver(context, module, analysis)
        val semantics = ARouterInputSemantics(analysis, resolver, options)
        if (!hasChanges(context, module, files, analysis, resolver, semantics, baseline, generated, record, checkpoint)) {
            context.logger.debug("ARouter processor skipped for ${module.name}: current-round semantics unchanged")
            return null
        }
        val sources = module.sourceDirs.filter { it.exists() }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension in listOf("java", "kt") }.toList()
        }.distinctBy { it.canonicalPath }
        val facts = sources.mapNotNull { source ->
            if (!analysis.mentions(source, ARouterInputSemantics.PACKAGE)) return@mapNotNull null
            semantics.current(source).takeIf { it.isNotEmpty() }?.let { source.canonicalPath to sourceInput(it) }
        }.toMap()
        val inputs = Inputs(3, environment(module), facts)
        val priorNames = generated.keys + if (prior?.first?.substringBefore(':') == baseline) prior.second else emptyList()
        return Preparation(sources, inputs, baseline, priorNames.toList())
    }

    private fun hasChanges(context: ICompileContext, module: ModuleInfo, files: List<CompileFile>, analysis: AptSourceAnalysis,
        resolver: AptSemanticResolver, semantics: ARouterInputSemantics, baseline: String,
        generated: Map<String, File>, record: Inputs?, checkpoint: GeneratedSourceCheckpoint): Boolean {
        var related = false
        val changed = files.any { input ->
            val file = input.file
            val candidate = analysis.mentions(file, ARouterInputSemantics.PACKAGE)
            val current = if (candidate) semantics.current(file) else null
            val known = recordedInput(record, file, current)
            if (!candidate && known == null && (record != null || generated.isEmpty())) return@any false
            check(record != null || generated.isEmpty() || optionsAllowBaselineRecovery(module)) {
                "Original documentation inputs cannot be recovered from ARouter Java indexes"
            }
            // A valid generation record is complete; never mix its inputs with older Gradle classes.
            val old = known ?: sourceInput(if (record != null) emptyMap() else
                oldInputs.recover(context, module, file, baseline, generated, analysis, resolver, semantics))
            val now = current ?: if (old.identities.isEmpty()) emptyMap() else semantics.current(file)
            if (record != null && (old.identities.isNotEmpty() || now.isNotEmpty())) {
                check(checkpoint.read()?.let { checkpoint.valid(it, it.first.substringBeforeLast(':')) } == true) {
                    "Generated ARouter sources no longer match the input record"
                }
            }
            related = related || old.identities.isNotEmpty() || now.isNotEmpty()
            sourceInput(now).fingerprint != old.fingerprint
        }
        return changed || related && record != null && record.environment != environment(module)
    }

    private fun optionsAllowBaselineRecovery(module: ModuleInfo): Boolean =
        (module.javaAnnotationProcessorOptions.orEmpty() + module.kaptArguments.orEmpty())["AROUTER_GENERATE_DOC"] != "enable"

    /** Preserves identity across a source-root move or a filename change with unchanged declarations. */
    private fun recordedInput(record: Inputs?, file: File, current: Map<String, String>?): SourceInput? {
        if (record == null) return null
        record.sources[file.canonicalPath]?.let { return it }
        val identities = current?.let { sourceInput(it).identities }.orEmpty().toSet()
        val matches = record.sources.filter { (path, facts) ->
            File(path).name == file.name || facts.identities.any { it in identities }
        }.values.toList()
        check(matches.size <= 1) { "Ambiguous recorded source identity for $file" }
        return matches.singleOrNull()
    }

    private fun runGeneration(context: ICompileContext, module: ModuleInfo, files: List<CompileFile>,
        checkpoint: GeneratedSourceCheckpoint, prepared: Preparation): List<CompileFile> {
        val options = module.javaAnnotationProcessorOptions.orEmpty() + module.kaptArguments.orEmpty()
        val paths = (module.annotationProcessorDependencies + module.kaptDependencies).map { it.file.canonicalFile }.distinct()
        val original = compatibility.projectProcessor(paths, module)
        check(options.containsKey("AROUTER_MODULE_NAME") || options.containsKey("moduleName")) { "AROUTER_MODULE_NAME or moduleName is missing for ${module.name}" }
        val work = File(context.tempCompileDir, "arouter/${module.name}")
        val selected = compatibility.selectProcessor(context, module, original, paths, CompileTask(files, work, CompileStatusHolder.DEFAULT))
        val effectivePaths = compatibility.replaceProcessorJar(paths, original.jar, selected.jar)
        val effectiveContext = compatibility.processorContext(context, original.jar, selected.jar)
        return generate(effectiveContext, module, prepared, selected, effectivePaths, options, checkpoint)
    }

    /** Restores compact inputs only when bound to the latest generation; legacy disk data is recoverable. */
    private fun readInputs(checkpoint: GeneratedSourceCheckpoint, baseline: String, generated: Map<String, File>,
        prior: Pair<String, List<String>>?): Inputs? {
        val file = File(checkpoint.folder, "inputs.json")
        if (prior == null) {
            check(!file.exists() && !checkpoint.sourceRoot.exists()) { "Latest ARouter generation checkpoint is missing" }
            return null
        }
        if (prior.first.substringBefore(':') != baseline) return null
        if (!file.isFile) {
            // Three-part manifests predate input records. Managed generations must not borrow Gradle inputs.
            check(prior.first.split(':').size == 3 && checkpoint.valid(prior, prior.first.substringBeforeLast(':')) && prior.second.toSet() == generated.keys &&
                prior.second.all { File(checkpoint.sourceRoot, it).readBytes().contentEquals(generated.getValue(it).readBytes()) }) {
                "Latest ARouter input record is missing; original Gradle inputs cannot replace it"
            }
            return null
        }
        val json = JsonParser.parseString(file.readText()).asJsonObject
        val version = json.get("version").asInt
        check(version in 2..3) { "Unknown ARouter input record" }
        if (version == 2) check(json.get("baseline").asString == baseline) { "Mismatched ARouter input record" }
        val environment = json.get("environment").asString
        val expected = if (version == 2) "$baseline:$environment:${digest(gson.toJson(json))}" else "$baseline:inputs:${digest(gson.toJson(json))}"
        check(prior.first.substringBeforeLast(':') == expected) { "Latest ARouter input record no longer matches its generation" }
        if (version == 3) return gson.fromJson(json, Inputs::class.java)
        val sources = json.getAsJsonObject("sources").entrySet().associate { (path, facts) ->
            path to sourceInput(facts.asJsonObject.entrySet().associate { it.key to it.value.asString })
        }
        return Inputs(3, environment, sources)
    }

    private fun signature(baseline: String, inputs: Inputs): String = "$baseline:inputs:${digest(gson.toJson(inputs))}"

    /** Generated indexes are lossy, so comparison needs an identity and a digest, not a semantic copy. */
    private fun sourceInput(facts: Map<String, String>): SourceInput = SourceInput(
        facts.keys.map { it.substringBefore('@').substringBefore('#') }.distinct().sorted(),
        digest(gson.toJson(facts.toSortedMap())),
    )

    private fun digest(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun baselineSources(context: ICompileContext, module: ModuleInfo): Map<String, File> =
        context.getGeneratedSourcePaths(module).flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "java" && it.name.contains("ARouter") }
                .map { it.relativeTo(root).invariantSeparatorsPath to it }.toList()
        }.toMap()

    private fun environment(module: ModuleInfo): String = buildString {
        append(GeneratedSourceCheckpoint.digest((module.annotationProcessorDependencies + module.kaptDependencies).map { it.file }.filter { it.isFile }))
        append((module.javaAnnotationProcessorOptions.orEmpty() + module.kaptArguments.orEmpty()).toSortedMap())
        append(module.libraryDependencies.map { "${it.file.path}:${it.crc32}" }.sorted())
        append(ARouterProcessorCompatibility.JETIFIER_VERSION)
    }.let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) } }

    private fun warnSkipped(context: ICompileContext, module: ModuleInfo, files: List<File>, failure: Throwable) {
        context.logger.warn("ARouter detection failed for ${module.name}, files=$files: ${failure.message}; " +
                "generation skipped for this module round; annotation outputs may be stale", failure)
    }

    /** Generation failures and class removal retain the existing fail-closed contract. */
    private fun generate(context: ICompileContext, module: ModuleInfo, prepared: Preparation,
        processor: ARouterProcessorCompatibility.ProjectProcessor, paths: List<File>, options: Map<String, String>,
        checkpoint: GeneratedSourceCheckpoint): List<CompileFile> {
        val start = System.nanoTime()
        val moduleName = options["AROUTER_MODULE_NAME"] ?: options.getValue("moduleName")
        context.logger.info("ARouter processor started for $moduleName (${module.name})")
        try {
            val generated = AnnotationProcessorRunner().generate(context, module, prepared.sources, paths, processor.names, options,
                File(context.tempCompileDir, "arouter/${module.name}"))
            val names = generated.walkTopDown().filter { it.isFile && it.extension == "java" }
                .map { it.relativeTo(generated).invariantSeparatorsPath }.sorted().toList()
            val removed = prepared.priorNames - names.toSet()
            check(removed.isEmpty()) { "ARouter generated classes were removed (${removed.joinToString()}); run a full Gradle build" }
            checkpoint.publish(generated, signature(prepared.baseline, prepared.inputs), names, gson.toJson(prepared.inputs))
            context.logger.info("ARouter generated ${names.size} Java sources for $moduleName")
            return names.map { name ->
                val file = File(checkpoint.sourceRoot, name)
                context.logger.info("ARouter generated source: ${file.absolutePath}")
                CompileFile(CompileFile.Type.Java, file, checkpoint.sourceRoot, module)
            }
        } finally {
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            context.logger.info("ARouter processor finished for ${module.name} in " +
                    "${String.format(Locale.ROOT, "%.2f", seconds)} seconds")
        }
    }
}
