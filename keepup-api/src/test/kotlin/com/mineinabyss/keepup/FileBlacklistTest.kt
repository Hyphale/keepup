package com.mineinabyss.keepup

import com.mineinabyss.keepup.config_sync.FileBlacklist
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileBlacklistTest {
    @Test
    fun `should blacklist jar files`() {
        val jarPath = Path("plugins/test.jar")
        assertTrue(FileBlacklist.isBlacklisted(jarPath))
    }

    @Test
    fun `should not blacklist non-jar files`() {
        val txtPath = Path("config.txt")
        assertFalse(FileBlacklist.isBlacklisted(txtPath))
        
        val ymlPath = Path("config.yml")
        assertFalse(FileBlacklist.isBlacklisted(ymlPath))
        
        val jsonPath = Path("config.json")
        assertFalse(FileBlacklist.isBlacklisted(jsonPath))
    }

    @Test
    fun `should handle uppercase extensions`() {
        val jarPath = Path("plugins/TEST.JAR")
        assertTrue(FileBlacklist.isBlacklisted(jarPath))
    }

    @Test
    fun `should filter blacklisted files from map`() {
        val files = mapOf(
            Path("dest/config.yml") to Path("source/config.yml"),
            Path("dest/plugin.jar") to Path("source/plugin.jar"),
            Path("dest/data.txt") to Path("source/data.txt"),
            Path("dest/another.jar") to Path("source/another.jar")
        )
        
        val filtered = FileBlacklist.filterBlacklisted(files)
        
        assertEquals(2, filtered.size)
        assertTrue(filtered.containsKey(Path("dest/config.yml")))
        assertTrue(filtered.containsKey(Path("dest/data.txt")))
        assertFalse(filtered.containsKey(Path("dest/plugin.jar")))
        assertFalse(filtered.containsKey(Path("dest/another.jar")))
    }

    @Test
    fun `should filter when source is jar even if dest is not`() {
        val files = mapOf(
            Path("dest/renamed-file") to Path("source/plugin.jar")
        )
        
        val filtered = FileBlacklist.filterBlacklisted(files)
        
        assertEquals(0, filtered.size)
    }

    @Test
    fun `should filter when dest is jar even if source is not`() {
        val files = mapOf(
            Path("dest/plugin.jar") to Path("source/renamed-file")
        )
        
        val filtered = FileBlacklist.filterBlacklisted(files)
        
        assertEquals(0, filtered.size)
    }
}
