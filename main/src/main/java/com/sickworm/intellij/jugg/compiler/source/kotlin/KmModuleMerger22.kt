
package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.diagnostic.Logger
import kotlin.metadata.jvm.*
import java.io.File

/**
 * Read, write and merge .kotlin_module files in classPaths.
 * @see {@link https://github.com/Kotlin/binary-compatibility-validator/issues/241}
 */
class KmModuleMergerForCompilation22(
    private val classPath: File,
    private val logger: Logger,
) : IKmModuleMergerForCompilation {

    private val metaInfDir get() = File(classPath, "META-INF")
    private val kmModuleFileMap = mutableMapOf<String, KmModuleMerger22>()

    override fun loadAndMerge() {
        metaInfDir.listFiles()
            ?.filter { it.extension == "kotlin_module" }
            ?.forEach {
                var merger = kmModuleFileMap[it.absolutePath]
                if (merger == null) {
                    merger = KmModuleMerger22(logger)
                    kmModuleFileMap[it.absolutePath] = merger
                }
                merger.merge(it)
            }
    }

    override fun save(targetVersion: kotlinx.metadata.jvm.JvmMetadataVersion?) {
        val convertTargetVersion = targetVersion?.let {
            JvmMetadataVersion(targetVersion.major, targetVersion.minor)
        }
        save(convertTargetVersion)
    }

    private fun save(targetVersion: JvmMetadataVersion?) {
        kmModuleFileMap.forEach { (filePath, merger) ->
            val destFile = File(filePath)
            if (targetVersion == null) {
                merger.writeTo(destFile)
            } else {
                merger.writeTo(destFile, targetVersion)
            }
        }
    }

    fun getExtensionClasses(): List<String> {
        val result = mutableListOf<String>()
        kmModuleFileMap.values.forEach { merger ->
            result.addAll(merger.getExtensionClasses())
        }
        return result
    }
}

/**
 * Read, write and merge .kotlin_module files.
 */
class KmModuleMerger22(private val logger: Logger) {

    private val kmModule = KmModule()
    private var version = JvmMetadataVersion.LATEST_STABLE_SUPPORTED
    private var originalBytes: ByteArray? = null
    private var preserveOriginal = false

    /**
     * merge kotlin module metadata into [kmModule]
     */
    fun merge(kmModuleFile: File) {
        if (preserveOriginal) return
        val bytes = kmModuleFile.readBytes()
        if (originalBytes == null) originalBytes = bytes
        val metadata = try {
            KotlinModuleMetadata.read(bytes)
        } catch (e: Exception) {
            preserveOriginal = true
            logger.warn("Cannot read Kotlin module metadata: $kmModuleFile. " +
                    "Keeping its original bytes; new top-level declarations may not be resolved.", e)
            return
        }
        val kmModule = metadata.kmModule
        version = metadata.version
        this.kmModule.merge(kmModule)
    }

    /**
     * write kotlin module metadata to file
     */
    fun writeTo(destFile: File, targetVersion: JvmMetadataVersion) {
        // A compiler invocation can replace the index even when the baseline could not be parsed.
        val metadata = if (preserveOriginal) checkNotNull(originalBytes) else KotlinModuleMetadata(kmModule, targetVersion).write()
        destFile.also {
            it.parentFile?.mkdirs()
            it.writeBytes(metadata)
        }
    }

    fun writeTo(destFile: File) {
        // why not optional argument? KmModuleMergerTest will have compilation error:
        // Cannot access class 'kotlinx.metadata.jvm.JvmMetadataVersion'.
        // Check your module classpath for missing or conflicting dependencies
        writeTo(destFile, version)
    }

    fun getExtensionClasses(): List<String> {
        val result = mutableListOf<String>()
        kmModule.packageParts.forEach { (_, value) ->
            value.fileFacades.forEach { facade ->
                val className = "L$facade;"
                result.add(className)
            }
        }
        return result
    }

    override fun toString(): String {
        val builder = StringBuilder()
        builder.appendLine("optionalAnnotationClasses: ${kmModule.optionalAnnotationClasses}")
        builder.appendLine("packageParts:")
        kmModule.packageParts.forEach { (key, value) ->
            builder.appendLine("   key: $key")
            builder.appendLine("   fileFacades: ${value.fileFacades}")
            builder.appendLine("   multiFileClassParts:")
            value.multiFileClassParts.forEach { (t, u) ->
                builder.appendLine("        [$t: $u]")
            }
        }
        return builder.toString()
    }

    private fun KmModule.merge(ktModule: KmModule) {
        // annotations are not writable
        // optionalAnnotationClasses are not writable
        this.packageParts.addIfNotExist(ktModule.packageParts)

        ktModule.packageParts.entries.forEach { (key, value) ->
            val packagePart = this.packageParts[key]
            packagePart?.fileFacades?.addIfNotExist(value.fileFacades)
            packagePart?.multiFileClassParts?.addIfNotExist(value.multiFileClassParts)
        }
    }

    private fun <T> MutableList<T>.addIfNotExist(values: List<T>) {
        values.forEach {
            if (!this.contains(it)) {
                add(it)
            }
        }
    }

    private fun <K, V> MutableMap<K, V>.addIfNotExist(values: Map<K, V>) {
        values.forEach { (key, value) ->
            if (!this.containsKey(key)) {
                this[key] = value
            }
        }
    }
}
