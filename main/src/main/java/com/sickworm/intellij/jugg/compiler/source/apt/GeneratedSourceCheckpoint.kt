package com.sickworm.intellij.jugg.compiler.source.apt

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Publishes generated sources and their content fingerprint, retaining the existing checkpoint format. */
class GeneratedSourceCheckpoint(val folder: File) {
    val sourceRoot = File(folder, "sources")
    fun read(): Pair<String, List<String>>? {
        val lines = File(folder, "checkpoint.txt").takeIf { it.isFile }?.readLines() ?: return null
        return lines.firstOrNull()?.let { it to lines.drop(1) }
    }
    fun valid(prior: Pair<String, List<String>>, signature: String): Boolean {
        if (prior.first.substringBeforeLast(':') != signature) return false
        val files = prior.second.map { File(sourceRoot, it) }
        return files.all { it.isFile } && prior.first.substringAfterLast(':') == digest(files)
    }
    fun publish(generated: File, signature: String, names: List<String>, inputRecord: String) {
        val pendingSources = File(folder, "sources.pending")
        pendingSources.deleteRecursively()
        generated.copyRecursively(pendingSources, overwrite = true)
        val pendingRecord = File(folder, "inputs.pending").apply { writeText(inputRecord) }
        sourceRoot.deleteRecursively()
        Files.move(pendingSources.toPath(), sourceRoot.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val pending = File(folder, "checkpoint.pending")
        pending.writeText((listOf("$signature:${digest(names.map { File(sourceRoot, it) })}") + names).joinToString("\n"))
        Files.move(pending.toPath(), File(folder, "checkpoint.txt").toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.move(pendingRecord.toPath(), File(folder, "inputs.json").toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    companion object {
        /** Hashes canonical paths and bytes, including same-size edits to local processor JARs. */
        fun digest(files: List<File>): String {
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
    }
}
