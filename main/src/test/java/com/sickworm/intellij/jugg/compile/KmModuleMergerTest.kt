package com.sickworm.intellij.jugg.compile

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.source.kotlin.IKmModuleMergerForCompilation
import com.sickworm.intellij.jugg.compiler.source.kotlin.KmModuleMerger
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.io.File
import kotlin.metadata.jvm.JvmMetadataVersion
import kotlin.metadata.jvm.KmModule
import kotlin.metadata.jvm.KmPackageParts
import kotlin.metadata.jvm.KotlinModuleMetadata
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Regression coverage for module indexes used to resolve top-level Kotlin declarations. */
class KmModuleMergerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val logger = mock(Logger::class.java)

    @Test
    fun `Kotlin 2_4 baseline survives warm up and consecutive incremental outputs`() {
        val classpath = temporaryFolder.newFolder()
        val version = JvmMetadataVersion(2, 4)
        val baseline = moduleFile(classpath, "app", moduleBytes(version, "TimeExtensionsKt"))
        val warmUpMerger = IKmModuleMergerForCompilation.create("2.4.10", classpath, logger)
        warmUpMerger.loadAndMerge()

        val incremental = moduleFile(classpath, "app_googlePlayDebug", moduleBytes(version))
        warmUpMerger.loadAndMerge()
        warmUpMerger.save()
        assertModule(baseline, version, "TimeExtensionsKt")

        listOf("FirstExtensionsKt", "SecondExtensionsKt").forEachIndexed { index, facade ->
            val merger = IKmModuleMergerForCompilation.create("2.4.10", classpath, logger)
            merger.loadAndMerge()
            incremental.writeBytes(moduleBytes(version, facade))
            merger.loadAndMerge()
            merger.save()

            assertModule(baseline, version, "TimeExtensionsKt")
            assertModule(incremental, version, *listOf("FirstExtensionsKt", "SecondExtensionsKt")
                .take(index + 1).toTypedArray())
        }
    }

    @Test
    fun `older modern metadata retains its version and declarations after merging`() {
        listOf(2, 3).forEach { minor ->
            val classpath = temporaryFolder.newFolder()
            val version = JvmMetadataVersion(2, minor)
            val file = moduleFile(classpath, "app", moduleBytes(version, "OldExtensionsKt"))
            val merger = IKmModuleMergerForCompilation.create("2.$minor.0", classpath, logger)
            merger.loadAndMerge()
            file.writeBytes(moduleBytes(version, "NewExtensionsKt"))
            merger.loadAndMerge()
            merger.save()

            assertModule(file, version, "OldExtensionsKt", "NewExtensionsKt")
        }
    }

    @Test
    fun `legacy metadata retains declarations after a serialization round trip`() {
        val assets = File("../idea/src/test/assets/kotlin/kotlin_module")
        val merger = KmModuleMerger()
        merger.merge(File(assets, "app_debug_new.kotlin_module"))
        merger.merge(File(assets, "app_debug_old.kotlin_module"))
        val output = temporaryFolder.newFile("merged.kotlin_module")
        merger.writeTo(output)
        val reloaded = KmModuleMerger()
        reloaded.merge(output)

        assertEquals(setOf(
            "Lcom/sickworm/jugg/demo/testcase/ktextension/ExtClass1Kt;",
            "Lcom/sickworm/jugg/demo/testcase/ktextension/ExtClass3Kt;",
        ), reloaded.getExtensionClasses().toSet())
    }

    @Test
    fun `unreadable indexes survive compiler overwrite without blocking valid indexes`() {
        val unsupported = moduleBytes(JvmMetadataVersion(3, 0), "FutureExtensionsKt")
        listOf(byteArrayOf(1, 2, 3), unsupported).forEach { original ->
            val classpath = temporaryFolder.newFolder()
            val version = JvmMetadataVersion(2, 2)
            val unreadable = moduleFile(classpath, "unreadable", original)
            val valid = moduleFile(classpath, "valid", moduleBytes(version, "OldExtensionsKt"))
            val merger = IKmModuleMergerForCompilation.create("2.2.21", classpath, logger)
            merger.loadAndMerge()
            unreadable.writeBytes(moduleBytes(version))
            valid.writeBytes(moduleBytes(version, "NewExtensionsKt"))
            merger.loadAndMerge()
            merger.save()

            assertContentEquals(original, unreadable.readBytes())
            assertModule(valid, version, "OldExtensionsKt", "NewExtensionsKt")
        }
    }

    @Test
    fun `failed read after a valid load restores the original index`() {
        val classpath = temporaryFolder.newFolder()
        val original = moduleBytes(JvmMetadataVersion(2, 2), "TimeExtensionsKt")
        val file = moduleFile(classpath, "app", original)
        val merger = IKmModuleMergerForCompilation.create("2.2.21", classpath, logger)
        merger.loadAndMerge()
        file.writeBytes(byteArrayOf(1, 2, 3))
        merger.loadAndMerge()
        merger.save()

        assertContentEquals(original, file.readBytes())
    }

    @Test
    fun `legacy unreadable index is preserved after compiler overwrite`() {
        val classpath = temporaryFolder.newFolder()
        val original = byteArrayOf(1, 2, 3)
        val file = moduleFile(classpath, "app", original)
        val merger = IKmModuleMergerForCompilation.create("1.9.23", classpath, logger)
        merger.loadAndMerge()
        File("../idea/src/test/assets/kotlin/kotlin_module/app_debug_new.kotlin_module").copyTo(file, overwrite = true)
        merger.loadAndMerge()
        merger.save()

        assertContentEquals(original, file.readBytes())
    }

    private fun moduleFile(classpath: File, name: String, bytes: ByteArray): File {
        return File(classpath, "META-INF/$name.kotlin_module").apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun moduleBytes(version: JvmMetadataVersion, vararg facades: String): ByteArray {
        val module = KmModule()
        if (facades.isNotEmpty()) {
            module.packageParts["example"] = KmPackageParts(
                facades.map { "example/$it" }.toMutableList(), mutableMapOf(),
            )
        }
        return KotlinModuleMetadata(module, version).write()
    }

    private fun assertModule(file: File, version: JvmMetadataVersion, vararg facades: String) {
        val metadata = KotlinModuleMetadata.read(file.readBytes())
        assertEquals(version, metadata.version)
        assertEquals(facades.map { "example/$it" }.toSet(),
            metadata.kmModule.packageParts.values.flatMap { it.fileFacades }.toSet())
    }
}
