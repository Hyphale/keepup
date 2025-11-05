package com.mineinabyss.keepup.version_analysis

import com.mineinabyss.keepup.downloads.parsing.DownloadSource
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionAnalyzerTest {
    private val analyzer = VersionAnalyzer()

    @Test
    fun `extractVersion should extract GitHub release versions`() {
        val version = analyzer.extractVersion("github:MineInAbyss/Idofront:v0.20.6:*.jar")
        assertEquals("v0.20.6", version)
    }

    @Test
    fun `extractVersion should extract GitLab release versions`() {
        val version = analyzer.extractVersion("gitlab:group/project:1.2.3:*.jar")
        assertEquals("1.2.3", version)
    }

    @Test
    fun `extractVersion should extract Keeper versions`() {
        val version = analyzer.extractVersion("keeper:namespace/artifact:2.0.0")
        assertEquals("2.0.0", version)
    }

    @Test
    fun `extractVersion should extract Nexus versions`() {
        val version = analyzer.extractVersion("nexus:com.example:artifact:1.0.0:all")
        assertEquals("1.0.0", version)
    }

    @Test
    fun `extractVersion should extract versions from HTTP URLs with version patterns`() {
        val version = analyzer.extractVersion("https://example.com/plugin-v1.2.3.jar")
        assertEquals("1.2.3", version)
    }

    @Test
    fun `extractVersion should extract versions with prerelease suffixes`() {
        val version = analyzer.extractVersion("github:owner/repo:v1.0.0-alpha:*.jar")
        assertEquals("v1.0.0-alpha", version)
    }

    @Test
    fun `extractVersion should return null for unsupported formats`() {
        val version = analyzer.extractVersion("unsupported:format:here")
        assertNull(version)
    }

    @Test
    fun `isNewerVersion should correctly compare semantic versions`() {
        assertTrue(analyzer.isNewerVersion("1.0.0", "1.1.0"))
        assertTrue(analyzer.isNewerVersion("1.0.0", "2.0.0"))
        assertTrue(analyzer.isNewerVersion("1.2.0", "1.2.1"))
    }

    @Test
    fun `isNewerVersion should handle prerelease versions`() {
        assertTrue(analyzer.isNewerVersion("1.0.0-alpha", "1.0.0"))
        assertTrue(analyzer.isNewerVersion("1.0.0-alpha", "1.0.0-beta"))
    }

    @Test
    fun `isNewerVersion should return false for older versions`() {
        assertTrue(!analyzer.isNewerVersion("2.0.0", "1.0.0"))
        assertTrue(!analyzer.isNewerVersion("1.2.0", "1.1.0"))
    }

    @Test
    fun `isNewerVersion should return false for equal versions`() {
        assertTrue(!analyzer.isNewerVersion("1.0.0", "1.0.0"))
    }

    @Test
    fun `analyzeVersions should detect updates correctly`() {
        val currentSources = mapOf(
            "plugin1" to DownloadSource("plugin1", "github:owner/plugin1:v1.0.0:*.jar"),
            "plugin2" to DownloadSource("plugin2", "github:owner/plugin2:v2.0.0:*.jar"),
        )

        val catalogSources = mapOf(
            "plugin1" to DownloadSource("plugin1", "github:owner/plugin1:v1.1.0:*.jar"),
            "plugin2" to DownloadSource("plugin2", "github:owner/plugin2:v2.0.0:*.jar"),
        )

        val result = runBlocking {
            analyzer.analyzeVersions(currentSources, catalogSources)
        }

        assertEquals(1, result.updates.size)
        assertEquals("plugin1", result.updates["plugin1"]?.project)
        assertEquals("v1.0.0", result.updates["plugin1"]?.oldVersion)
        assertEquals("v1.1.0", result.updates["plugin1"]?.newVersion)
    }

    @Test
    fun `analyzeVersions should not include projects without catalog sources`() {
        val currentSources = mapOf(
            "plugin1" to DownloadSource("plugin1", "github:owner/plugin1:v1.0.0:*.jar"),
        )

        val catalogSources = mapOf<String, DownloadSource>()

        val result = runBlocking {
            analyzer.analyzeVersions(currentSources, catalogSources)
        }

        assertEquals(0, result.updates.size)
    }

    @Test
    fun `analyzeVersions should not include projects without version changes`() {
        val currentSources = mapOf(
            "plugin1" to DownloadSource("plugin1", "github:owner/plugin1:v1.0.0:*.jar"),
        )

        val catalogSources = mapOf(
            "plugin1" to DownloadSource("plugin1", "github:owner/plugin1:v1.0.0:*.jar"),
        )

        val result = runBlocking {
            analyzer.analyzeVersions(currentSources, catalogSources)
        }

        assertEquals(0, result.updates.size)
    }
}
