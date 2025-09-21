package com.mineinabyss.keepup

import com.mineinabyss.keepup.api.Keepup
import com.mineinabyss.keepup.config_sync.Inventory
import com.mineinabyss.keepup.config_sync.templating.Templater
import org.junit.Test
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncludeDirectoryTest {
    
    @Test
    fun `should load config from directory with include yml file`() {
        val keepup = Keepup()
        val tempDir = createTempDirectory("keepup-dir-include")
        val inventoryFile = Path("src/test/resources/inventory-with-dir-includes.yml")
        val configsRoot = Path("src/test/resources/configs-source")
        val dest = tempDir / "destRoot"
        val templater = Templater()

        val inventory = Inventory.from(
            templater = templater, 
            inputStream = inventoryFile.inputStream(), 
            environment = mapOf("TEST_VAR" to "world"),
            enableDockerSecrets = false // Disable for test consistency
        )
        
        // Test that directory-based includes are loaded correctly
        val configs = inventory.getOrCreateConfigs("dir-include-host", configsRoot)
        
        // Should have global + dir-include-test + another-dir-include + included feat/example
        assertTrue(configs.size >= 3, "Should have loaded multiple configs including directory-based ones")
        
        // Check that variables from directory includes are present
        val reduced = com.mineinabyss.keepup.config_sync.ConfigDefinition.reduce(configs)
        assertTrue("fromDirInclude" in reduced.variables, "Should include variables from directory include")
        assertEquals("directory-value", reduced.variables["fromDirInclude"])
        assertTrue("anotherVar" in reduced.variables, "Should include variables from another directory include")
        assertEquals("another-value", reduced.variables["anotherVar"])
        
        // Check that copy paths from directory includes are present
        val copyPathSources = reduced.copyPaths.map { it.source }
        assertTrue(copyPathSources.contains("should-correctly-set-dest"), "Should include copy paths from directory include")
        assertTrue(copyPathSources.contains("should-correctly-default-dest"), "Should include copy paths from directory include")
        assertTrue(copyPathSources.contains("should-template-inventory-with-env"), "Should include copy paths from another directory include")
    }
    
    @Test
    fun `should handle mixed named and directory includes`() {
        val templater = Templater()
        val inventoryFile = Path("src/test/resources/inventory-with-dir-includes.yml")
        val configsRoot = Path("src/test/resources/configs-source")

        val inventory = Inventory.from(
            templater = templater,
            inputStream = inventoryFile.inputStream(),
            environment = mapOf("TEST_VAR" to "world"),
            enableDockerSecrets = false
        )

        val configs = inventory.getOrCreateConfigs("mixed-include-host", configsRoot)
        val reduced = com.mineinabyss.keepup.config_sync.ConfigDefinition.reduce(configs)
        
        // Should include variables from both named config (feat/example) and directory includes
        assertTrue("testVar" in reduced.variables, "Should include variables from host")
        assertTrue("fromDirInclude" in reduced.variables, "Should include variables from directory include")
        
        // Should include copy paths from all sources
        val copyPathSources = reduced.copyPaths.map { it.source }
        assertTrue(copyPathSources.contains("should-correctly-set-dest"), "Should include paths from named config")
        assertTrue(copyPathSources.contains("should-correctly-default-dest"), "Should include paths from directory include")
    }
    
    @Test
    fun `should fall back to source path when directory has no include yml`() {
        val templater = Templater()
        val inventoryFile = Path("src/test/resources/inventory-with-dir-includes.yml")
        val configsRoot = Path("src/test/resources/configs-source")

        val inventory = Inventory.from(
            templater = templater,
            inputStream = inventoryFile.inputStream(),
            environment = mapOf("TEST_VAR" to "world"),
            enableDockerSecrets = false
        )

        val configs = inventory.getOrCreateConfigs("mixed-include-host", configsRoot)
        val reduced = com.mineinabyss.keepup.config_sync.ConfigDefinition.reduce(configs)
        
        // Should include the non-existent include as a source path fallback
        val copyPathSources = reduced.copyPaths.map { it.source }
        assertTrue(copyPathSources.contains("non-existent-fallback-to-directory"), 
                   "Should fallback to using include name as source path when directory has no include.yml")
    }
    
    @Test
    fun `should sync files using directory-based includes`() {
        val keepup = Keepup()
        val tempDir = createTempDirectory("keepup-dir-sync")
        val inventoryFile = Path("src/test/resources/inventory-with-dir-includes.yml")
        val configsRoot = Path("src/test/resources/configs-source")
        val dest = tempDir / "destRoot"
        val templater = Templater()

        keepup.configSync(
            inventory = Inventory.from(
                templater = templater, 
                inputStream = inventoryFile.inputStream(), 
                environment = mapOf("TEST_VAR" to "world"),
                enableDockerSecrets = false
            ),
        ).sync(
            host = "dir-include-host",
            configsRoot = configsRoot,
            templateCacheDir = tempDir / "cacheDir",
            destRoot = dest,
        )

        // Verify that files from directory-based includes were copied
        assertTrue((dest / "dir-included" / "example" / "example1.yml").exists(), 
                   "Should copy files to destination from directory include")
        assertTrue((dest / "plugins" / "example" / "example2.yml").exists(), 
                   "Should copy files from default dest in directory include")
        assertTrue((dest / "another-dir" / "template" / "templated-inventory.yml").exists(), 
                   "Should copy files from another directory include")
    }
}
