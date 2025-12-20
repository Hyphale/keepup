package com.mineinabyss.keepup

import com.mineinabyss.keepup.api.Keepup
import com.mineinabyss.keepup.config_sync.Inventory
import com.mineinabyss.keepup.config_sync.templating.Templater
import org.junit.Test
import kotlin.io.path.*
import kotlin.test.assertEquals

class ConfigCopyTest {
    @Test
    fun `should pass integration test with expected file structure`() {
        val keepup = Keepup()
        val tempDir = createTempDirectory("keepup")
        val inventoryFile = Path("src/test/resources/inventory.yml")
        val configsRoot = Path("src/test/resources/configs-source")
        val dest = tempDir / "destRoot"
        val shouldMatch = Path("src/test/resources/expected-output")
        val templater = Templater()

        keepup.configSync(
            inventory = Inventory.from(
                templater = templater, 
                inputStream = inventoryFile.inputStream(), 
                environment = mapOf("TEST_VAR" to "world"),
                enableDockerSecrets = false // Disable for test consistency
            ),
        ).sync(
            host = "example-host",
            configsRoot = configsRoot,
            templateCacheDir = tempDir / "cacheDir",
            destRoot = dest,
        )

        println("Output: $dest")
        val expectedFiles = shouldMatch.walk()
            .map { it.relativeTo(shouldMatch) }
            .sorted().toList()
        val actualFiles = dest.walk()
            .map { it.relativeTo(dest) }
            .sorted().toList()
        assertEquals(expectedFiles, actualFiles)
        expectedFiles.indices.forEach {
            val expected = shouldMatch / expectedFiles[it]
            val actual = dest / actualFiles[it]
            assertEquals(expected.readText(), actual.readText())
        }
    }

    @Test
    fun `should blacklist jar files from being copied`() {
        val keepup = Keepup()
        val tempDir = createTempDirectory("keepup-blacklist")
        val inventoryFile = Path("src/test/resources/inventory.yml")
        val configsRoot = Path("src/test/resources/configs-source")
        val dest = tempDir / "destRoot"
        val shouldMatch = Path("src/test/resources/expected-output-blacklist")
        val templater = Templater()

        keepup.configSync(
            inventory = Inventory.from(
                templater = templater,
                inputStream = inventoryFile.inputStream(),
                enableDockerSecrets = false
            ),
        ).sync(
            host = "blacklist-test-host",
            configsRoot = configsRoot,
            templateCacheDir = tempDir / "cacheDir",
            destRoot = dest,
        )

        println("Output: $dest")
        val expectedFiles = shouldMatch.walk()
            .map { it.relativeTo(shouldMatch) }
            .sorted().toList()
        val actualFiles = dest.walk()
            .map { it.relativeTo(dest) }
            .sorted().toList()
        
        // Verify the structure matches
        assertEquals(expectedFiles, actualFiles, "File structure should match expected output")
        
        // Verify no jar files were copied
        val copiedFiles = dest.walk().filter { it.isRegularFile() }.toList()
        val jarFiles = copiedFiles.filter { it.extension == "jar" }
        assertEquals(0, jarFiles.size, "No .jar files should be copied")
        
        // Verify that non-jar files were copied correctly
        expectedFiles.indices.forEach {
            val expected = shouldMatch / expectedFiles[it]
            val actual = dest / actualFiles[it]
            assertEquals(expected.readText(), actual.readText())
        }
    }

    @Test
    fun `should copy directory contents correctly based on trailing slash`() {
        val keepup = Keepup()
        val tempDir = createTempDirectory("keepup-directory-test")
        val configsRoot = Path("src/test/resources/configs-source")
        val dest = tempDir / "destRoot"
        val templater = Templater()

        val inventoryContent = """
            directory-test-host:
              copyPaths:
                - from: should-copy-directory-behavior/abc
                  to: test
                - from: should-copy-directory-behavior/abc
                  to: test/
        """.trimIndent()

        keepup.configSync(
            inventory = Inventory.from(
                templater = templater,
                inputStream = inventoryContent.byteInputStream(),
                enableDockerSecrets = false
            ),
        ).sync(
            host = "directory-test-host",
            configsRoot = configsRoot,
            templateCacheDir = tempDir / "cacheDir",
            destRoot = dest,
        )

        // When to is "test" (no trailing slash), files should be copied directly to test/
        val testFile1 = dest / "test" / "file1.txt"
        val testFile2 = dest / "test" / "file2.txt"
        assert(testFile1.exists()) { "file1.txt should exist in test/ when to='test'" }
        assert(testFile2.exists()) { "file2.txt should exist in test/ when to='test'" }
        assertEquals("content1\n", testFile1.readText())
        assertEquals("content2\n", testFile2.readText())

        // When to is "test/" (with trailing slash), files should be copied to test/abc/
        val testAbcFile1 = dest / "test" / "abc" / "file1.txt"
        val testAbcFile2 = dest / "test" / "abc" / "file2.txt"
        assert(testAbcFile1.exists()) { "file1.txt should exist in test/abc/ when to='test/'" }
        assert(testAbcFile2.exists()) { "file2.txt should exist in test/abc/ when to='test/'" }
        assertEquals("content1\n", testAbcFile1.readText())
        assertEquals("content2\n", testAbcFile2.readText())
    }
}
