package com.sickworm.intellij.jugg.compiler.source.apt

import com.intellij.openapi.Disposable
import com.android.tools.build.jetifier.core.config.ConfigParser
import com.android.tools.build.jetifier.processor.FileMapping
import com.android.tools.build.jetifier.processor.Processor
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ICompiler
import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.ChangedFile
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.Mockito
import java.io.File
import java.util.jar.JarFile
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the built-in APT pipeline with the project's real ARouter processor artifacts. */
class ARouterJuggAptProcessorTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: ICompileContext
    private lateinit var module: ModuleInfo
    private lateinit var source: CompileFile
    private lateinit var compiler: JuggAptCompiler
    private lateinit var processorJars: List<File>
    private lateinit var libraryJars: List<File>
    private lateinit var supportFragment: File

    @Before
    fun setUp() {
        processorJars = System.getProperty("arouter.test.processors").split(File.pathSeparator).map(::File)
        libraryJars = System.getProperty("arouter.test.libraries").split(File.pathSeparator).map(::File).map { file ->
            if (file.extension != "aar") file else JarFile(file).use { jar ->
                File(temporary.root, "${file.nameWithoutExtension}.jar").apply {
                    writeBytes(jar.getInputStream(jar.getJarEntry("classes.jar")).use { it.readBytes() })
                }
            }
        }
        supportFragment = libraryJars.single { it.name.startsWith("support-fragment") }
        libraryJars = libraryJars - supportFragment
        val sources = temporary.newFolder("src")
        module = ModuleInfo.virtualModule.copy(
            name = "routes", projectRootDir = temporary.root, moduleRootDir = temporary.root,
            sourceDirs = listOf(sources),
            buildPathInfo = ModuleBuildPathInfo(temporary.root, temporary.root, "debug", buildDirRelativePath = "build"),
            annotationProcessorDependencies = processorJars.map { LibraryDependency(it.name, it) },
            javaAnnotationProcessorOptions = mapOf("AROUTER_MODULE_NAME" to "routes"),
        )
        context = mock {
            on { logger } doReturn StdLogger("ARouterTest")
            on { tempCompileDir } doReturn temporary.newFolder("work")
            on { incrementalDataDir } doReturn temporary.newFolder("incremental")
            on { customCompilers } doReturn emptyList()
            on { modulesWithOrder } doReturn listOf(module)
            on { modules } doReturn mapOf(module.name to module)
            on { isSingleApk } doReturn true
            on { tempModule } doReturn ModuleInfo.virtualModule
            on { tempModuleDir } doReturn File(temporary.root, "temp-module")
            on { cmdCompileEnv } doReturn emptyList()
            on { projectDir } doReturn temporary.root
        }
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(emptyList())
        whenever(context.containsApkClass(any())).thenReturn(emptyList())
        val androidJar = File(System.getenv("ANDROID_HOME"), "platforms/android-30/android.jar")
        assertTrue(androidJar.isFile, "Tests require Android SDK platform 30")
        whenever(context.androidHome).thenReturn(File(System.getenv("ANDROID_HOME")))
        whenever(context.androidJar).thenReturn(androidJar)
        whenever(context.getModuleDependencies(any(), any())).thenAnswer {
            listOf(androidJar.path) + libraryJars.map { it.path } + processorJars.map { it.path }
        }
        source = writeSource("@Route(path = \"/demo/first\")")
        compiler = JuggAptCompiler(context, object : Disposable { override fun dispose() = Unit })
    }

    @Test
    fun defaultPipelineGeneratesRoutesForAndroidXWithoutCustomCompiler() {
        val generated = generate()
        assertEquals(3, generated.size)
        assertTrue(generated.any { it.readText().contains("/demo/first") })
        assertTrue(generated.all { it.isFile })
    }

    @Test
    fun unchangedDeclarationsReuseSourcesAndRouteChangesRegenerate() {
        val first = generate()
        val contents = first.associate { it.name to it.readText() }
        first.forEach { assertTrue(it.setLastModified(1234567890000L)) }
        assertTrue(generate().isEmpty())
        assertEquals(contents, first.associate { it.name to it.readText() })
        assertTrue(first.all { it.lastModified() == 1234567890000L })
        source = writeSource("@Route(path = \"/demo/other\")")
        val changed = generate()
        assertTrue(changed.any { it.readText().contains("/demo/other") })
        assertFalse(changed.any { it.readText().contains("/demo/first") })
    }

    @Test
    fun spacedRouteAnnotationGeneratesAndInvalidatesCache() {
        source = writeSource("@Route (path = \"/demo/first\")")
        assertTrue(generate().any { it.readText().contains("/demo/first") })
        source = writeSource("@Route (path = \"/demo/other\")")
        val generated = generate()
        assertTrue(generated.any { it.readText().contains("/demo/other") })
        assertFalse(generated.any { it.readText().contains("/demo/first") })
    }

    @Test
    fun relatedParentTypeChangesRegenerateTheRouteKind() {
        val parent = File(source.baseDir, "BaseScreen.java")
        parent.writeText("package demo; class BaseScreen extends android.app.Activity {}")
        source.file.writeText(source.file.readText().replace("extends android.app.Activity", "extends BaseScreen"))
        assertTrue(generate().any { it.readText().contains("RouteType.ACTIVITY") })
        parent.writeText("""
            package demo;
            class BaseScreen extends android.app.Service {
                public android.os.IBinder onBind(android.content.Intent intent) { return null; }
            }
        """.trimIndent())
        assertTrue(generate().any { it.readText().contains("RouteType.SERVICE") })
    }

    @Test
    fun javaAutowiredAndInterceptorGenerateTheirOwnArtifacts() {
        source.file.writeText(source.file.readText().replace("extends android.app.Activity {}", """
            extends android.app.Activity {
                @com.alibaba.android.arouter.facade.annotation.Autowired public String user;
            }
        """.trimIndent()))
        File(source.baseDir, "DemoInterceptor.java").writeText("""
            package demo;
            import com.alibaba.android.arouter.facade.annotation.Interceptor;
            import com.alibaba.android.arouter.facade.Postcard;
            import com.alibaba.android.arouter.facade.callback.InterceptorCallback;
            import com.alibaba.android.arouter.facade.template.IInterceptor;
            @Interceptor(priority = 1)
            public class DemoInterceptor implements IInterceptor {
                public void init(android.content.Context context) {}
                public void process(Postcard postcard, InterceptorCallback callback) { callback.onContinue(postcard); }
            }
        """.trimIndent())
        val generated = generate()
        assertTrue(generated.any { it.name.contains("Autowired") && it.readText().contains("substitute.user") })
        assertTrue(generated.any { it.name.contains("Interceptors") && it.readText().contains("DemoInterceptor.class") })
    }

    @Test
    fun whitespaceInsideAnnotationLiteralsIsPartOfTheCacheInput() {
        source = writeSource("@Route(path = \"/demo/with space\")")
        assertTrue(generate().any { it.readText().contains("/demo/with space") })
        source = writeSource("@Route(path = \"/demo/withspace\")")
        assertTrue(generate().any { it.readText().contains("/demo/withspace") })
        assertFalse(generate().any { it.readText().contains("/demo/with space") })
    }

    @Test
    fun methodBodyChangesReuseSourcesButReferencedConstantsRegenerate() {
        val constants = File(source.baseDir, "Paths.java")
        constants.writeText("package demo; class Paths { static final String ROUTE = \"/demo/constant\"; }")
        source = writeSource("@Route(path = Paths.ROUTE)")
        val generated = generate()
        assertTrue(generated.any { it.readText().contains("/demo/constant") })
        generated.forEach { assertTrue(it.setLastModified(1234567890000L)) }
        source.file.writeText(source.file.readText().replace("extends android.app.Activity {", "extends android.app.Activity { void work() { int changed = 1; }"))
        assertTrue(generate().all { it.lastModified() == 1234567890000L })
        constants.writeText(constants.readText().replace("/demo/constant", "/demo/modified"))
        assertTrue(generate().any { it.readText().contains("/demo/modified") })
    }

    @Test
    fun changedModuleOptionsRegenerateTheModuleIndexes() {
        generate().forEach { assertTrue(it.setLastModified(1234567890000L)) }
        module = module.copy(javaAnnotationProcessorOptions = module.javaAnnotationProcessorOptions.orEmpty() + ("AROUTER_MODULE_NAME" to "renamed"))
        updateModule()
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun aRefreshedGradleBaselineAllowsRebuildingAfterAnIndexWasRemoved() {
        establishGradleBaseline()
        source = writeSource("@Route(path = \"/other/first\")")
        assertFailsWith<JuggAptFatalException> { generate() }
        establishGradleBaseline()
        assertTrue(generate().isEmpty())
        source = writeSource("@Route(path = \"/other/next\")")
        assertTrue(generate().any { it.readText().contains("/other/next") })
    }

    @Test
    fun missingFragmentFailsInsteadOfSilentlyKeepingBaselineRoutes() {
        libraryJars = libraryJars.filterNot { it.name.startsWith("fragment-") }
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun missingModuleNameFailsBeforePublishingGeneratedSources() {
        module = module.copy(javaAnnotationProcessorOptions = emptyMap())
        source = source.copy(module = module)
        whenever(context.modulesWithOrder).thenReturn(listOf(module))
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun firstIncrementalRoundCannotRemoveGradleBaselineClasses() {
        establishGradleBaseline()
        source = writeSource("@Route(path = \"/other/first\")")
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun removingLastDeclarationDoesNotSkipGradleBaselineDeletionCheck() {
        establishGradleBaseline()
        source = writeSource("")
        source.file.writeText(source.file.readText().replace("import com.alibaba.android.arouter.facade.annotation.Route;", ""))
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun firstRoundMethodBodyChangeDoesNotRunApt() {
        establishGradleBaseline()
        source.file.writeText(source.file.readText().replace("Activity {}", "Activity { void changed() {} }"))
        assertTrue(generate().isEmpty())
        assertFalse(File(context.incrementalDataDir, "arouter/routes/debug/checkpoint.txt").exists())
    }

    @Test
    fun missingOriginalClassesSkipsDetectionWithoutPublishingACheckpoint() {
        val baseline = temporary.newFolder("baseline")
        File(baseline, "ARouter\$\$Root\$\$routes.java").writeText("// Unknown or incomplete baseline")
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(listOf(baseline))
        assertTrue(generate().isEmpty())
        assertFalse(File(context.incrementalDataDir, "arouter/routes/debug/checkpoint.txt").exists())
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(emptyList())
        assertTrue(generate().any { it.readText().contains("/demo/first") })
    }

    @Test
    fun ordinaryProjectDoesNotRequireARouterOptionsOrProcessor() {
        module = module.copy(annotationProcessorDependencies = emptyList(), javaAnnotationProcessorOptions = emptyMap())
        source = writeSource("")
        updateModule()
        assertTrue(generate().isEmpty())
    }

    @Test
    fun actualSpiCanRegisterOnlyTwoProcessorsAndIgnoreUnrelatedEntries() {
        val original = compilerJar()
        val replacement = rewriteJar(original, "two-processors.jar", spi = """
            # Project processor registration
            com.alibaba.android.arouter.compiler.processor.RouteProcessor # route
            com.alibaba.android.arouter.compiler.processor.InterceptorProcessor
            java.lang.String
        """.trimIndent())
        replaceCompiler(original, replacement)
        module = module.copy(kaptDependencies = module.annotationProcessorDependencies)
        updateModule()
        assertEquals(3, generate().size)
    }

    @Test
    fun distinctCompilerCandidatesFailInsteadOfClasspathOrderChoosingOne() {
        val duplicate = File(temporary.root, "duplicate.jar")
        compilerJar().copyTo(duplicate)
        module = module.copy(annotationProcessorDependencies = module.annotationProcessorDependencies + LibraryDependency("duplicate", duplicate))
        updateModule()
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun missingRegisteredProcessorClassFailsInsteadOfSkippingGeneration() {
        val original = compilerJar()
        replaceCompiler(original, rewriteJar(original, "broken.jar", spi = "com.alibaba.android.arouter.compiler.processor.Missing"))
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun supportOnlyBaselineUsesSupportProcessorWithoutAndroidX() {
        useSupportClasspath()
        whenever(context.containsApkClass(any())).thenReturn(listOf(fragmentNode("android/support/v4/app/Fragment")))
        assertEquals(3, generate().size)
    }

    @Test
    fun ambiguousAndMultiApkBaselinesDefaultToAndroidX() {
        useSupportClasspath()
        val support = fragmentNode("android/support/v4/app/Fragment")
        val androidx = fragmentNode("androidx/fragment/app/Fragment")
        whenever(context.containsApkClass(any())).thenReturn(listOf(support, androidx))
        assertFailsWith<JuggAptFatalException> { generate() }
        whenever(context.containsApkClass(any())).thenReturn(listOf(support))
        whenever(context.isSingleApk).thenReturn(false)
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun alreadyJetifiedProcessorGeneratesRoutesWithoutAnotherNamespaceChange() {
        jetifyCompiler()
        whenever(context.containsApkClass(any())).thenReturn(listOf(fragmentNode("androidx/fragment/app/Fragment")))
        assertEquals(3, generate().size)
    }

    @Test
    fun reverseJetifierProducesWorkingProcessorForSupportBaseline() {
        jetifyCompiler()
        useSupportClasspath()
        whenever(context.containsApkClass(any())).thenReturn(listOf(fragmentNode("android/support/v4/app/Fragment")))
        assertEquals(3, generate().size)
    }

    @Test
    fun processorContentChangeWithSameSizeAndTimestampInvalidatesGeneratedSources() {
        val original = compilerJar()
        val replacement = rewriteJar(original, "local-compiler.jar", comment = "before")
        replaceCompiler(original, replacement)
        val files = generate()
        files.forEach { assertTrue(it.setLastModified(1234567890000L)) }
        val size = replacement.length()
        val timestamp = replacement.lastModified()
        val edited = rewriteJar(replacement, "edited.jar", comment = "after!")
        edited.copyTo(replacement, overwrite = true)
        assertTrue(replacement.setLastModified(timestamp))
        assertEquals(size, replacement.length())
        assertTrue(generate().all { it.lastModified() != 1234567890000L })
    }

    @Test
    fun externalARouterConfigurationRunsOnlyTheExternalGenerator() {
        val legacy = Mockito.mock(ICompiler::class.java, Mockito.withSettings().extraInterfaces(IJuggAptProcessor::class.java))
        val apt = legacy as IJuggAptProcessor
        whenever(apt.id).thenReturn("ARouter")
        whenever(apt.failClosed).thenReturn(true)
        val file = File(temporary.root, "LegacyRoute.java").apply { writeText("class LegacyRoute {}") }
        whenever(apt.process(any(), any(), any(), any())).thenReturn(listOf(CompileFile(CompileFile.Type.Java, file, temporary.root, module)))
        whenever(context.customCompilers).thenReturn(listOf(legacy))
        assertEquals(listOf(file), generate())
    }

    @Test
    fun builtinGeneratedSourcesAreOwnedByFailClosedProcessor() {
        val files = generate()
        assertTrue(files.all { compiler.isFailClosedGeneratedFile(CompileFile(CompileFile.Type.Java, it, it.parentFile, module)) })
        assertFalse(compiler.isFailClosedGeneratedFile(source))
    }

    @Test
    fun kotlinAliasAndAutowiredGenerateFromKaptStubs() {
        val toolchain = System.getProperty("arouter.test.kotlin").split(File.pathSeparator).map(::File)
        module = module.copy(kotlinPlugins = toolchain, kotlinJvmTarget = "1.8")
        libraryJars = libraryJars + toolchain.filter { it.name.startsWith("kotlin-stdlib-") || it.name.startsWith("annotations-") }
        File(module.sourceDirs.single(), "Page.kt").writeText("package com.tencent.kuikly.core.annotations; annotation class Page(val route: String)")
        val entry = File(module.buildPathInfo.generatedKspSourcePath, "KuiklyCoreEntry.kt").apply {
            parentFile.mkdirs()
            writeText("class KuiklyCoreEntry { private fun triggerRegisterPages() {} }")
        }
        val kotlin = File(module.sourceDirs.single(), "demo/KotlinScreen.kt").apply {
            writeText("""
                package demo
                import com.alibaba.android.arouter.facade.annotation.Route as Destination
                import com.alibaba.android.arouter.facade.annotation.Autowired
                import com.tencent.kuikly.core.annotations.Page
                @Page("kuikly_screen")
                @Destination(path = "/demo/kotlin")
                class KotlinScreen : android.app.Activity() {
                    @field:Autowired @JvmField var title: String? = null
                }
            """.trimIndent())
        }
        source = CompileFile(CompileFile.Type.Kotlin, kotlin, module.sourceDirs.single(), module)
        updateModule()
        val generated = generate()
        assertTrue(generated.any { it.readText().contains("/demo/kotlin") && it.readText().contains("/demo/first") })
        assertTrue(entry.readText().contains("BridgeManager.registerPageRouter(\"kuikly_screen\")"))
        assertTrue(generated.any { it.name.contains("Autowired") && it.readText().contains("title") })
    }

    @Test
    fun generatedJavaErrorRetainsPendingSourcesAndRetryCompletesWithoutRerunningApt() {
        val executions = installCountingProcessor()
        configureSourceCompilation()
        source.file.writeText("""
            package demo;
            import com.alibaba.android.arouter.facade.annotation.Route;
            import com.alibaba.android.arouter.facade.annotation.Autowired;
            @Route(path = "/demo/first")
            public class Screen extends android.app.Activity {
                @Autowired public final String value = "";
            }
        """.trimIndent())
        val trackedFiles = linkedSetOf<File>()
        val trackingContext = object : ICompileContext by context {
            override fun addChangedFile(files: List<ChangedFile>) { trackedFiles += files.map { it.file } }
            override fun removeChangedFile(files: List<File>) { trackedFiles -= files.toSet() }
        }
        val sourceCompiler = SourceCompiler(trackingContext, object : Disposable { override fun dispose() = Unit })
        val result = sourceCompiler.compile(CompileTask(listOf(source), File(temporary.root, "classes"), CompileStatusHolder.DEFAULT))
        assertFalse(result.isAllSuccess)
        assertTrue(trackedFiles.any { it.name.contains("Autowired") }, "A failed ARouter language stage must retain generated changed files")
        val count = executions.readLines().size
        source.file.writeText(source.file.readText().replace("public final String", "public String"))
        val root = File(context.incrementalDataDir, "arouter/routes/debug/sources")
        val pending = trackedFiles.map { CompileFile(CompileFile.Type.Java, it, root, module) }
        val retry = sourceCompiler.compile(CompileTask(listOf(source) + pending, File(temporary.root, "retry-dex"), CompileStatusHolder.DEFAULT))
        assertTrue(retry.isAllSuccess, retry.failedFiles.toString())
        assertTrue(retry.outputs.any { it.file.name.contains("ARouter") })
        assertEquals(count, executions.readLines().size)
    }

    @Test
    fun changingOnlyConstantImportMustRegenerateRoute() {
        for ((pkg, route) in listOf("first" to "/demo/first", "second" to "/demo/second")) {
            File(source.baseDir, "$pkg/Paths.java").apply {
                parentFile.mkdirs()
                writeText("package $pkg; public class Paths { public static final String PATH = \"$route\"; }")
            }
        }
        source = writeSource("@Route(path = Paths.PATH)")
        source.file.writeText(source.file.readText().replace("package demo;", "package demo;\nimport first.Paths;"))
        assertTrue(generate().any { it.readText().contains("/demo/first") })
        source.file.writeText(source.file.readText().replace("import first.Paths;", "import second.Paths;"))
        val reused = generate().any { it.readText().contains("/demo/second") }
        assertTrue(reused, "Import-only change incorrectly reused the previous route")
    }

    @Test
    fun changingOnlyAutowiredTypeImportMustRegenerateCast() {
        source.file.writeText("""
            package demo;
            import java.util.Date;
            import com.alibaba.android.arouter.facade.annotation.Route;
            import com.alibaba.android.arouter.facade.annotation.Autowired;
            @Route(path = "/demo/first")
            public class Screen extends android.app.Activity {
                @Autowired public Date value;
            }
        """.trimIndent())
        assertTrue(generate().any { it.readText().contains("java.util.Date") })
        source.file.writeText(source.file.readText().replace("import java.util.Date;", "import java.sql.Date;"))
        val reused = generate().any { it.readText().contains("java.sql.Date") }
        assertTrue(reused, "Import-only change incorrectly reused the previous injection cast")
    }

    @Test
    fun oneHundredBodyEditsAndEquivalentAnnotationValuesDoNotExecuteApt() {
        val executions = installCountingProcessor()
        generate()
        val before = executions.readLines().size
        val original = source.file.readText()
        repeat(100) { iteration ->
            source.file.writeText(original.replace("Activity {}", "Activity { void work() { int value = $iteration; } }"))
            assertTrue(generate().isEmpty())
        }
        File(source.baseDir, "demo/Paths.java").writeText("package demo; class Paths { static final String PATH = \"/demo/\" + \"first\"; }")
        source = writeSource("@Route(path = Paths.PATH, group = \"demo\", priority = -1, extras = Integer.MIN_VALUE, name = \"documentation only\")")
        assertTrue(generate().isEmpty())
        assertEquals(before, executions.readLines().size, "Apt init must not run for equivalent semantics")
        source = writeSource("@Route(path = \"/demo/changed\")")
        assertTrue(generate().any { it.readText().contains("/demo/changed") })
        assertEquals(before + 1, executions.readLines().size)
    }

    @Test
    fun ordinaryRoundDoesNotReturnCompletedIndexes() {
        generate()
        val ordinary = File(source.baseDir, "demo/Ordinary.java").apply { writeText("package demo; class Ordinary {}") }
        val input = CompileFile(CompileFile.Type.Java, ordinary, source.baseDir, module)
        val result = compiler.compile(CompileTask(listOf(input), File(temporary.root, "ordinary-out"), CompileStatusHolder.DEFAULT))
        assertTrue(result.outputs.isEmpty())
    }

    @Test
    fun aggregateFailureDoesNotPublishPartialIndexesAndTheNextRoundCanRecover() {
        val second = File(source.baseDir, "demo/Second.java").apply {
            writeText("package demo; import com.alibaba.android.arouter.facade.annotation.Route; @Route(path=\"/demo/second\") public class Second extends android.app.Activity {}")
        }
        generate()
        val checkpoint = File(context.incrementalDataDir, "arouter/routes/debug/inputs.json")
        val before = checkpoint.readText()
        source = writeSource("@Route(path = \"/demo/changed\")")
        val valid = second.readText()
        second.writeText(valid.replace("path=\"/demo/second\"", "path=unknown()"))
        assertTrue(generate().isEmpty())
        assertEquals(before, checkpoint.readText())
        second.writeText(valid)
        val result = generate()
        assertTrue(result.any { it.readText().contains("/demo/changed") && it.readText().contains("/demo/second") })
    }

    @Test
    fun constantImpactFlowCompilesGeneratedIndexesToDexAndResolvesTheNewRoute() {
        File(temporary.root, ".git").mkdirs()
        val paths = File(source.baseDir, "demo/Paths.java").apply {
            writeText("package demo; public class Paths { public static final String PATH = \"/demo/first\"; }")
        }
        source = writeSource("@Route(path = Paths.PATH)")
        establishGradleBaseline()
        assertEquals(setOf("/demo/first"), resolvedRoutes())
        configureSourceCompilation()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val engine = com.sickworm.intellij.jugg.compiler.constref.ConstRefEngine(
            analyzer = com.sickworm.intellij.jugg.compiler.constref.ConstRefAnalyzer(context.logger),
            database = com.sickworm.intellij.jugg.compiler.constref.ConstRefCacheDatabase(File(temporary.root, "constref.db"), context.logger),
            logger = context.logger,
            backgroundTaskRunner = com.sickworm.intellij.jugg.project.CoroutineBackgroundTaskRunner(scope),
            repoSharedFingerprintStore = com.sickworm.intellij.jugg.compiler.constref.RepoSharedFingerprintStore(context.logger, File(temporary.root, "fingerprints.db")),
        )
        try {
            engine.initializeFullScan(module.sourceDirs.map { it.canonicalFile })
            engine.awaitAnalysis(listOf(paths.canonicalPath, source.file.canonicalPath), timeoutMs = 10_000)
            engine.getEffectedFiles(listOf(paths.canonicalPath))
            engine.acknowledgeEffectedFilesAfterDeployCommit()
            paths.writeText(paths.readText().replace("/demo/first", "/demo/changed"))
            val sourceCompiler = SourceCompiler(context, object : Disposable { override fun dispose() = Unit })
            val constant = CompileFile(CompileFile.Type.Java, paths, source.baseDir, module)
            val first = sourceCompiler.compile(CompileTask(listOf(constant), File(temporary.root, "constant-dex"), CompileStatusHolder.DEFAULT))
            assertTrue(first.isAllSuccess, first.failedFiles.toString())
            assertFalse(first.outputs.any { it.file.name.contains("ARouter") })
            engine.onFileSaved(paths.canonicalPath)
            engine.awaitAnalysis(listOf(paths.canonicalPath), timeoutMs = 10_000)
            val effected = engine.getEffectedFiles(listOf(paths.canonicalPath))
            assertEquals(setOf(source.file.canonicalPath), effected.map { File(it.refFilePath).canonicalPath }.toSet())
            val unchanged = source.file.readText()
            val followup = effected.map { CompileFile(CompileFile.Type.Java, File(it.refFilePath), source.baseDir.canonicalFile, module) }
            val result = sourceCompiler.compile(CompileTask(followup, File(temporary.root, "route-dex"), CompileStatusHolder.DEFAULT))
            assertTrue(result.isAllSuccess, result.failedFiles.toString())
            assertTrue(result.outputs.any { it.type == com.sickworm.intellij.jugg.compiler.CompileOutput.Type.Dex && it.file.name.contains("ARouter") })
            assertEquals(unchanged, source.file.readText())
            assertEquals(setOf("/demo/changed"), resolvedRoutes())
        } finally {
            engine.dispose()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun firstBaselineRecoversMultipleAndNestedSourceIdentities() {
        source.file.writeText(source.file.readText().replace("Activity {}", """Activity {
            @com.alibaba.android.arouter.facade.annotation.Route(path="/demo/nested")
            public static class NestedScreen extends android.app.Activity {}
        }
        class UnrelatedHelper {}
        """.trimIndent()))
        establishGradleBaseline()
        source.file.appendText("\n// ordinary body and formatting edit")
        assertTrue(generate().isEmpty())
        source.file.writeText(source.file.readText().replace("path=\"/demo/nested\"", "path=\"/demo/moved\""))
        assertTrue(generate().any { it.readText().contains("/demo/moved") && it.readText().contains("/demo/first") })
    }

    @Test
    fun aConstantInAnotherClassOfTheCurrentFileUsesItsCurrentValue() {
        source = writeSource("@Route(path = Paths.PATH)")
        source.file.appendText("\nclass Paths { static final String PATH = \"/demo/first\"; }")
        assertTrue(generate().any { it.readText().contains("/demo/first") })
        source.file.writeText(source.file.readText().replace("/demo/first", "/demo/changed"))
        assertTrue(generate().any { it.readText().contains("/demo/changed") })
    }

    @Test
    fun firstBaselineRecoversStringAndReferenceInjectionTypes() {
        source.file.writeText(source.file.readText().replace("Activity {}", """Activity {
            @com.alibaba.android.arouter.facade.annotation.Autowired public String user;
            @com.alibaba.android.arouter.facade.annotation.Autowired public java.util.Date date;
        }""".trimIndent()))
        establishGradleBaseline()
        source.file.appendText("\n// ordinary edit")
        assertTrue(generate().isEmpty())
        source.file.writeText(source.file.readText().replace("/demo/first", "/demo/changed"))
        val result = generate()
        assertTrue(result.any { it.readText().contains("/demo/changed") })
        assertTrue(result.any { it.name.contains("Autowired") && it.readText().contains("Date") })
    }

    @Test
    fun strippedSourceIdentityWarnsInsteadOfTreatingTheLastAnnotationAsAbsent() {
        establishGradleBaseline()
        val file = File(module.buildPathInfo.javaClassPath, "demo/Screen.class")
        val writer = com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter(0)
        com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader(file.readBytes()).accept(writer,
            com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader.SKIP_DEBUG)
        file.writeBytes(writer.toByteArray())
        val logger = org.mockito.kotlin.spy(context.logger)
        whenever(context.logger).thenReturn(logger)
        source.file.writeText("package demo; public class Screen extends android.app.Activity {}")
        assertTrue(generate().isEmpty())
        org.mockito.kotlin.verify(logger).warn(org.mockito.kotlin.argThat<String> { contains("SourceFile identities are missing") }, any<Throwable>())
        assertFalse(File(context.incrementalDataDir, "arouter/routes/debug/inputs.json").exists())
    }

    @Test
    fun missingGeneratedBaselineDoesNotRegenerateAnExistingAnnotatedClass() {
        establishGradleBaseline()
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(emptyList())
        assertTrue(generate().isEmpty())
        assertFalse(File(context.incrementalDataDir, "arouter/routes/debug/inputs.json").exists())
    }

    @Test
    fun movingARecordedSourceDoesNotRegenerateAndItsRemovalStillFailsClosed() {
        generate()
        val moved = File(source.baseDir, "moved/Screen.java").apply { parentFile.mkdirs() }
        assertTrue(source.file.renameTo(moved))
        source = source.copy(file = moved)
        assertTrue(generate().isEmpty())
        moved.writeText("package demo; public class Screen extends android.app.Activity {}")
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun missingLatestGenerationRecordDoesNotFallBackToOriginalGradleInputs() {
        val executions = installCountingProcessor()
        establishGradleBaseline()
        source = writeSource("@Route(path = \"/demo/latest\")")
        val latest = generate().associateWith { it.readText() }
        val before = executions.readLines().size
        assertTrue(File(context.incrementalDataDir, "arouter/routes/debug/inputs.json").delete())
        compiler = JuggAptCompiler(context, object : Disposable { override fun dispose() = Unit })
        assertTrue(generate().isEmpty(), "Missing latest metadata must skip detection instead of regenerating from the old Gradle baseline")
        assertEquals(before, executions.readLines().size)
        assertEquals(latest, latest.keys.associateWith { it.readText() })
    }

    @Test
    fun missingModernRecordWarnsEvenWhenOutputsMatchTheGradleBaseline() {
        establishGradleBaseline()
        source = writeSource("@Route(path = \"/demo/latest\")")
        generate()
        source = writeSource("@Route(path = \"/demo/first\")")
        assertTrue(generate().any { it.readText().contains("/demo/first") })
        val logger = org.mockito.kotlin.spy(context.logger)
        whenever(context.logger).thenReturn(logger)
        assertTrue(File(context.incrementalDataDir, "arouter/routes/debug/inputs.json").delete())
        assertTrue(generate().isEmpty())
        org.mockito.kotlin.verify(logger).warn(org.mockito.kotlin.argThat<String> {
            contains("Latest ARouter input record is missing")
        }, any<Throwable>())
    }

    @Test
    fun latestGeneratedSourcesRemainTheBaselineWithoutMatchingLanguageClasses() {
        val executions = installCountingProcessor()
        establishGradleBaseline()
        source = writeSource("@Route(path = \"/demo/latest\")")
        val latest = generate().associateWith { it.readText() }
        val before = executions.readLines().size
        module.buildPathInfo.javaClassPath.deleteRecursively()
        compiler = JuggAptCompiler(context, object : Disposable { override fun dispose() = Unit })
        assertTrue(generate().isEmpty())
        assertEquals(before, executions.readLines().size)
        assertEquals(latest, latest.keys.associateWith { it.readText() })
        source = writeSource("@Route(path = \"/demo/next\")")
        assertTrue(generate().any { it.readText().contains("/demo/next") })
        assertEquals(before + 1, executions.readLines().size)
        val overlay = com.sickworm.intellij.jugg.compiler.CompileOutput(
            com.sickworm.intellij.jugg.compiler.CompileOutput.Type.Dex, File(temporary.root, "overlay.dex"), temporary.root)
        whenever(context.deployedFiles).thenReturn(listOf(overlay))
        val second = File(source.baseDir, "demo/Second.java").apply {
            writeText("package demo; import com.alibaba.android.arouter.facade.annotation.Route; @Route(path=\"/demo/second\") public class Second extends android.app.Activity {}")
        }
        source = CompileFile(CompileFile.Type.Java, second, source.baseDir, module)
        assertTrue(generate().any { it.readText().contains("/demo/next") && it.readText().contains("/demo/second") })
        assertEquals(before + 2, executions.readLines().size)
    }

    @Test
    fun legacyDiskInputsRemainUsableWithoutFallingBackToOldClasses() {
        val executions = installCountingProcessor()
        establishGradleBaseline()
        source = writeSource("@Route(path = \"/demo/latest\")")
        generate()
        val folder = File(context.incrementalDataDir, "arouter/routes/debug")
        val inputFile = File(folder, "inputs.json")
        val gson = com.google.gson.Gson()
        val header = com.google.gson.JsonParser.parseString(inputFile.readText()).asJsonObject
        val checkpoint = File(folder, "checkpoint.txt")
        val lines = checkpoint.readLines()
        val baseline = lines.first().substringBefore(':')
        val semantic = gson.toJson(listOf(sortedMapOf("extras" to Int.MIN_VALUE, "group" to "demo",
            "path" to "/demo/latest", "priority" to -1), "ACTIVITY", emptyList<String>()))
        val legacy = gson.toJson(linkedMapOf("version" to 2, "baseline" to baseline,
            "environment" to header["environment"].asString,
            "sources" to mapOf(source.file.canonicalPath to mapOf("demo.Screen@Route" to semantic))))
        inputFile.writeText(legacy)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(legacy.toByteArray())
            .joinToString("") { "%02x".format(it) }
        checkpoint.writeText((listOf("$baseline:${header["environment"].asString}:$hash:${lines.first().substringAfterLast(':')}") + lines.drop(1)).joinToString("\n"))
        module.buildPathInfo.javaClassPath.deleteRecursively()
        compiler = JuggAptCompiler(context, object : Disposable { override fun dispose() = Unit })
        val before = executions.readLines().size
        assertTrue(generate().isEmpty())
        assertEquals(before, executions.readLines().size)
        source = writeSource("@Route(path = \"/demo/next\")")
        assertTrue(generate().any { it.readText().contains("/demo/next") })
        assertEquals(before + 1, executions.readLines().size)
    }

    @Test
    fun damagedLatestGenerationSkipsAptAndCanRecoverInTheNextRound() {
        val executions = installCountingProcessor()
        establishGradleBaseline()
        source = writeSource("@Route(path = \"/demo/latest\")")
        val latest = generate().associateWith { it.readText() }
        val inputFile = File(context.incrementalDataDir, "arouter/routes/debug/inputs.json")
        val record = inputFile.readText()
        val before = executions.readLines().size
        inputFile.writeText("{\"version\":999}")
        assertTrue(generate().isEmpty())
        assertEquals(before, executions.readLines().size)
        inputFile.writeText(record)
        val generated = latest.keys.first()
        generated.appendText("\n// damaged output")
        assertTrue(generate().isEmpty())
        assertEquals(before, executions.readLines().size)
        generated.writeText(latest.getValue(generated))
        assertTrue(generate().isEmpty())
        source = writeSource("@Route(path = \"/demo/next\")")
        assertTrue(generate().any { it.readText().contains("/demo/next") })
        assertEquals(before + 1, executions.readLines().size)
    }

    @Test
    fun mismatchedOriginalClassPriorityDoesNotCreateANewGenerationRecord() {
        establishGradleBaseline()
        val modified = source.file.readText().replace("path = \"/demo/first\"", "path = \"/demo/first\", priority = 1")
        source.file.writeText(modified)
        val javac = javax.tools.ToolProvider.getSystemJavaCompiler()
        val paths = context.getModuleDependencies(module, CompileTask(listOf(source), temporary.root, CompileStatusHolder.DEFAULT))
        assertEquals(0, javac.run(null, null, null, "-proc:none", "-classpath", paths.joinToString(File.pathSeparator),
            "-d", module.buildPathInfo.javaClassPath.path, source.file.path))
        source.file.writeText(modified.replace(", priority = 1", ""))
        assertTrue(generate().isEmpty())
        assertFalse(File(context.incrementalDataDir, "arouter/routes/debug/inputs.json").exists())
    }

    private fun configureSourceCompilation() {
        val classpath = context.getModuleDependencies(module, CompileTask(listOf(source), temporary.root, CompileStatusHolder.DEFAULT))
        whenever(context.getModuleDependencies(any(), any())).thenReturn(classpath + module.buildPathInfo.javaClassPath.path)
        whenever(context.moduleBelongsApkMap).thenReturn(com.sickworm.intellij.jugg.ModuleApkBelongs(emptyMap(), emptyMap()))
        whenever(context.getDesugarInfo(any<com.sickworm.intellij.jugg.compiler.ClassPreparation>(), any(), any())).thenReturn(com.sickworm.intellij.jugg.compiler.DesugarInfo.EMPTY)
    }

    private fun resolvedRoutes(): Set<String> {
        val paths = listOf(module.buildPathInfo.javaClassPath, context.androidJar) + libraryJars + processorJars
        java.net.URLClassLoader(paths.map { it.toURI().toURL() }.toTypedArray(), null).use { loader ->
            val type = loader.loadClass("com.alibaba.android.arouter.routes.ARouter\$\$Group\$\$demo")
            val routes = linkedMapOf<String, Any>()
            type.getMethod("loadInto", Map::class.java).invoke(type.getDeclaredConstructor().newInstance(), routes)
            return routes.keys
        }
    }

    /** Counts real RouteProcessor initialization through its public JSR 269 extension point. */
    private fun installCountingProcessor(): File {
        val original = compilerJar()
        val root = temporary.newFolder("counting-processor")
        val counter = File(root, "executions.txt")
        val java = File(root, "CountingRouteProcessor.java").apply {
            writeText("""
                package com.alibaba.android.arouter.compiler.processor;
                @javax.annotation.processing.SupportedAnnotationTypes("com.alibaba.android.arouter.facade.annotation.Route")
                public class CountingRouteProcessor extends RouteProcessor {
                    @Override public synchronized void init(javax.annotation.processing.ProcessingEnvironment environment) {
                        try (java.io.FileWriter writer = new java.io.FileWriter(environment.getOptions().get("arouterCounter"), true)) {
                            writer.write("init\n");
                        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                        super.init(environment);
                    }
                }
            """.trimIndent())
        }
        val cp = processorJars.joinToString(File.pathSeparator) { it.path }
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-proc:none", "-classpath", cp, "-d", root.path, java.path))
        val replacement = File(root, "counted.jar")
        JarFile(original).use { jar ->
            JarOutputStream(replacement.outputStream()).use { output ->
                jar.entries().asSequence().forEach { entry ->
                    output.putNextEntry(JarEntry(entry.name))
                    val content = jar.getInputStream(entry).use { it.readBytes() }
                    output.write(if (entry.name == "META-INF/services/javax.annotation.processing.Processor") {
                        String(content).replace("processor.RouteProcessor", "processor.CountingRouteProcessor").toByteArray()
                    } else content)
                    output.closeEntry()
                }
                val path = "com/alibaba/android/arouter/compiler/processor/CountingRouteProcessor.class"
                output.putNextEntry(JarEntry(path))
                output.write(File(root, path).readBytes())
                output.closeEntry()
            }
        }
        replaceCompiler(original, replacement)
        module = module.copy(javaAnnotationProcessorOptions = module.javaAnnotationProcessorOptions.orEmpty() + ("arouterCounter" to counter.path))
        updateModule()
        return counter
    }

    private fun establishGradleBaseline() {
        val generated = File(temporary.root, "gradle-generated").apply { deleteRecursively(); mkdirs() }
        val classes = module.buildPathInfo.javaClassPath.apply { deleteRecursively(); mkdirs() }
        val javac = javax.tools.ToolProvider.getSystemJavaCompiler()
        val paths = context.getModuleDependencies(module, CompileTask(listOf(source), classes, CompileStatusHolder.DEFAULT)) + supportFragment.path
        javac.getStandardFileManager(null, null, null).use { manager ->
            val sources = module.sourceDirs.flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "java" }.toList() }
            val arguments = listOf("-classpath", paths.joinToString(File.pathSeparator), "-processorpath", paths.joinToString(File.pathSeparator),
                "-processor", "com.alibaba.android.arouter.compiler.processor.AutowiredProcessor,com.alibaba.android.arouter.compiler.processor.InterceptorProcessor,com.alibaba.android.arouter.compiler.processor.RouteProcessor",
                "-AAROUTER_MODULE_NAME=routes", "-s", generated.path, "-d", classes.path)
            assertTrue(javac.getTask(null, manager, null, arguments, null, manager.getJavaFileObjectsFromFiles(sources)).call())
        }
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(listOf(generated))
        compiler = JuggAptCompiler(context, object : Disposable { override fun dispose() = Unit })
    }

    private fun compilerJar(): File = processorJars.single { it.name.startsWith("arouter-compiler-") || it.parentFile == temporary.root }

    private fun updateModule() {
        source = source.copy(module = module)
        whenever(context.modulesWithOrder).thenReturn(listOf(module))
        whenever(context.modules).thenReturn(mapOf(module.name to module))
    }

    private fun replaceCompiler(original: File, replacement: File) {
        processorJars = processorJars.map { if (it == original) replacement else it }
        module = module.copy(annotationProcessorDependencies = processorJars.map { LibraryDependency(it.name, it) })
        updateModule()
    }

    private fun useSupportClasspath() {
        libraryJars = libraryJars.filterNot { it.name.startsWith("fragment-") } + supportFragment
    }

    private fun fragmentNode(name: String) = ClassNode("", "L$name;", 0, emptyList(), emptyList(), emptyList(), "", null)

    private fun jetifyCompiler() {
        val original = compilerJar()
        val converted = File(temporary.root, "androidx-compiler.jar")
        Processor.createProcessor4(checkNotNull(ConfigParser.loadDefaultConfig()))
            .transform2(setOf(FileMapping(original, converted)), skipLibsWithAndroidXReferences = false)
        replaceCompiler(original, converted)
    }

    private fun rewriteJar(original: File, name: String, spi: String? = null, comment: String = ""): File {
        val result = File(temporary.root, name)
        JarFile(original).use { input ->
            JarOutputStream(result.outputStream()).use { output ->
                output.setComment(comment)
                input.entries().asSequence().forEach { entry ->
                    output.putNextEntry(JarEntry(entry.name).apply { time = entry.time })
                    val content = if (spi != null && entry.name == "META-INF/services/javax.annotation.processing.Processor") {
                        spi.toByteArray()
                    } else input.getInputStream(entry).use { it.readBytes() }
                    output.write(content)
                    output.closeEntry()
                }
            }
        }
        return result
    }

    private fun generate(): List<File> = compiler.compile(
        CompileTask(listOf(source), File(temporary.root, "out"), CompileStatusHolder.DEFAULT),
    ).outputs.map { it.file }

    private fun writeSource(annotation: String): CompileFile {
        val root = module.sourceDirs.single()
        val file = File(root, "demo/Screen.java").apply {
            parentFile.mkdirs()
            writeText("""
                package demo;
                import com.alibaba.android.arouter.facade.annotation.Route;
                $annotation
                public class Screen extends android.app.Activity {}
            """.trimIndent())
        }
        return CompileFile(CompileFile.Type.Java, file, root, module)
    }
}
