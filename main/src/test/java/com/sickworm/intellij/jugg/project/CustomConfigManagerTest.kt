package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/** Verifies that incomplete project JSON preserves configured compiler extensions. */
@Suppress("DEPRECATION")
class CustomConfigManagerTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `custom compiler config without build rules retains compiler`() {
        val configDir = temporaryFolder.newFolder()
        configDir.resolve("custom_config.json").writeText("""
            {"customCompilers":[{"jarFileName":"adapter.jar","path":"adapter.jar","md5":"abc"}]}
        """.trimIndent())

        val config = CustomConfigManager(configDir, Mockito.mock(Logger::class.java)).config!!

        assertEquals(emptyList<String>(), config.buildFileList)
        assertEquals(emptyList<String>(), config.buildFileRules)
        assertEquals(emptyList<String>(), config.dontFilterIgnoredFileRules)
        assertEquals("adapter.jar", config.customCompilers!!.single().path)
    }

    @Test
    fun `complete config retains its build rules`() {
        val configDir = temporaryFolder.newFolder()
        configDir.resolve("custom_config.json").writeText("""
            {"buildFileList":["old.gradle"],"buildFileRules":["*.gradle"],
             "dontFilterIgnoredFileRules":["generated.gradle"]}
        """.trimIndent())

        val config = CustomConfigManager(configDir, Mockito.mock(Logger::class.java)).config!!

        assertEquals(listOf("old.gradle"), config.buildFileList)
        assertEquals(listOf("*.gradle"), config.buildFileRules)
        assertEquals(listOf("generated.gradle"), config.dontFilterIgnoredFileRules)
    }
}
