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
        assertEquals(contents, generate().associate { it.name to it.readText() })
        assertTrue(first.all { it.lastModified() == 1234567890000L })
        source = writeSource("@Route(path = \"/demo/other\")")
        assertTrue(generate().any { it.readText().contains("/demo/other") })
        assertFalse(generate().any { it.readText().contains("/demo/first") })
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
        val baseline = temporary.newFolder("baseline")
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(listOf(baseline))
        val generated = generate()
        generated.forEach { file ->
            val relative = file.relativeTo(File(context.incrementalDataDir, "arouter/routes/debug/sources"))
            File(baseline, relative.path).apply { parentFile.mkdirs(); writeBytes(file.readBytes()) }
        }
        source = writeSource("@Route(path = \"/other/first\")")
        assertFailsWith<JuggAptFatalException> { generate() }
        baseline.deleteRecursively()
        baseline.mkdirs()
        File(baseline, "com/alibaba/android/arouter/routes/ARouter\$\$Group\$\$other.java").apply {
            parentFile.mkdirs()
            writeText("// Refreshed Gradle group for /other/first")
        }
        assertTrue(generate().any { it.name == "ARouter\$\$Group\$\$other.java" })
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
        val baseline = temporary.newFolder("baseline")
        File(baseline, "com/alibaba/android/arouter/routes/ARouter\$\$Group\$\$removed.java").apply {
            parentFile.mkdirs()
            writeText("// Previous Gradle route group")
        }
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(listOf(baseline))
        assertFailsWith<JuggAptFatalException> { generate() }
    }

    @Test
    fun removingLastDeclarationDoesNotSkipGradleBaselineDeletionCheck() {
        val baseline = temporary.newFolder("baseline")
        File(baseline, "ARouter\$\$Root\$\$routes.java").writeText("// Previous Gradle root")
        whenever(context.getGeneratedSourcePaths(any())).thenReturn(listOf(baseline))
        source = writeSource("")
        assertFailsWith<JuggAptFatalException> { generate() }
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
        whenever(apt.process(any(), any(), any())).thenReturn(listOf(CompileFile(CompileFile.Type.Java, file, temporary.root, module)))
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
        source.file.delete()
        val kotlin = File(module.sourceDirs.single(), "demo/KotlinScreen.kt").apply {
            writeText("""
                package demo
                import com.alibaba.android.arouter.facade.annotation.Route as Destination
                import com.alibaba.android.arouter.facade.annotation.Autowired
                @Destination(path = "/demo/kotlin")
                class KotlinScreen : android.app.Activity() {
                    @field:Autowired @JvmField var title: String? = null
                }
            """.trimIndent())
        }
        source = CompileFile(CompileFile.Type.Kotlin, kotlin, module.sourceDirs.single(), module)
        updateModule()
        val generated = generate()
        assertTrue(generated.any { it.readText().contains("/demo/kotlin") })
        assertTrue(generated.any { it.name.contains("Autowired") && it.readText().contains("title") })
    }

    @Test
    fun generatedJavaErrorRemainsVisibleInsteadOfRetryingWithoutARouter() {
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
