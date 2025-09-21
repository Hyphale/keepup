package com.mineinabyss.keepup

import com.mineinabyss.keepup.config_sync.Inventory
import com.mineinabyss.keepup.config_sync.templating.Templater
import org.junit.Test
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class ComprehensiveAliasTest {
    
    @Test
    fun `should work with multiple alias files and relative paths`() {
        val tempDir = createTempDirectory("comprehensive-test")
        
        try {
            // Create feature.yml with relative paths
            val featureDir = tempDir / "feature-config"
            featureDir.createDirectories()
            (featureDir / "feature.yml").writeText("""
                variables:
                  featureVar: "from-feature"
                copyPaths:
                  - source: relative-file.txt
                    dest: output
                  - relative-dir
            """.trimIndent())
            
            // Create the relative files
            (featureDir / "relative-file.txt").writeText("Feature content")
            (featureDir / "relative-dir").createDirectories()
            (featureDir / "relative-dir" / "nested.txt").writeText("Nested content")
            
            // Create server.yml 
            val serverDir = tempDir / "server-config"
            serverDir.createDirectories()
            (serverDir / "server.yml").writeText("""
                variables:
                  serverVar: "from-server"
                copyPaths:
                  - source: config.yml
            """.trimIndent())
            (serverDir / "config.yml").writeText("Server config")
            
            val inventoryYaml = """
                test-host:
                  variables:
                    hostVar: "from-host"
                  include:
                    - feature-config
                    - server-config
            """.trimIndent()
            
            val templater = Templater()
            val inventory = Inventory.from(
                templater = templater,
                inputStream = inventoryYaml.byteInputStream(),
                environment = mapOf(),
                enableDockerSecrets = false
            )
            
            // Test basic alias functionality
            val configs = inventory.getOrCreateConfigs("test-host", tempDir)
            val reduced = com.mineinabyss.keepup.config_sync.ConfigDefinition.reduce(configs)
            
            assertTrue("featureVar" in reduced.variables)
            assertEquals("from-feature", reduced.variables["featureVar"])
            assertTrue("serverVar" in reduced.variables)
            assertEquals("from-server", reduced.variables["serverVar"])
            
            // Test base path functionality
            val configsWithBasePaths = inventory.getOrCreateConfigsWithBasePaths("test-host", tempDir)
            assertTrue(configsWithBasePaths.size >= 2, "Should have at least 2 configs with base paths")
            
            // Find the feature config and verify its base path
            val featureConfig = configsWithBasePaths.find { 
                it.first.variables.containsKey("featureVar") 
            }
            assertTrue(featureConfig != null, "Should find feature config")
            assertTrue(featureConfig!!.second != null, "Feature config should have base path")
            assertTrue(featureConfig.second!!.endsWith("feature-config"), "Base path should point to feature-config directory")
            
            println("✅ Alias support: Multiple file types (feature.yml, server.yml) are detected and loaded")
            println("✅ Relative path support: Base paths are correctly tracked for directory-based includes") 
            
        } finally {
            tempDir.deleteRecursively()
        }
    }
}