package com.mineinabyss.keepup

import com.mineinabyss.keepup.config_sync.Inventory
import com.mineinabyss.keepup.config_sync.templating.Templater
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DirectAliasTest {
    
    @Test
    fun `should directly load feature yml using reflection`() {
        val configsRoot = Path("src/test/resources/configs-source")
        val inventory = Inventory(mapOf()) // Empty inventory
        
        // Call the private method using reflection to test it directly
        val method = Inventory::class.java.getDeclaredMethod("getConfigFromDirectory", String::class.java, Path::class.java)
        method.isAccessible = true
        
        val config = method.invoke(inventory, "feature-alias-test", configsRoot)
        assertNotNull(config, "Should load config from feature.yml")
        println("Loaded config: $config")
        
        if (config is com.mineinabyss.keepup.config_sync.ConfigDefinition) {
            assertEquals("feature-value", config.variables["featureVar"])
        }
    }
}