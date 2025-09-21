package com.mineinabyss.keepup

import com.mineinabyss.keepup.config_sync.Inventory
import com.mineinabyss.keepup.config_sync.templating.Templater
import org.junit.Test
import kotlin.io.path.*
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class SimpleAliasTest {
    
    @Test
    fun `should load config from feature yml alias`() {
        val tempDir = createTempDirectory("simple-alias-test")
        
        try {
            val testIncludeDir = tempDir / "feature-test"
            testIncludeDir.createDirectories()
            
            // Create feature.yml
            (testIncludeDir / "feature.yml").writeText("""
                variables:
                  testVar: "from-feature-yml"
                copyPaths:
                  - source: test-file
            """.trimIndent())
            
            val inventoryYaml = """
                test-config:
                  include:
                    - feature-test
            """.trimIndent()
            
            val templater = Templater()
            val inventory = Inventory.from(
                templater = templater,
                inputStream = inventoryYaml.byteInputStream(),
                environment = mapOf(),
                enableDockerSecrets = false
            )
            
            val configs = inventory.getOrCreateConfigs("test-config", tempDir)
            println("Found ${configs.size} configs")
            configs.forEach { config ->
                println("Config variables: ${config.variables}")
                println("Config copyPaths: ${config.copyPaths}")
            }
            
            val reduced = com.mineinabyss.keepup.config_sync.ConfigDefinition.reduce(configs)
            println("Reduced variables: ${reduced.variables}")
            
            assertTrue("testVar" in reduced.variables, "Should include variables from feature.yml")
            
        } finally {
            tempDir.deleteRecursively()
        }
    }
}