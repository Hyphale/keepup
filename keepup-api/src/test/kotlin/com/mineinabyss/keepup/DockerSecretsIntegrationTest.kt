package com.mineinabyss.keepup

import com.mineinabyss.keepup.config_sync.Inventory
import com.mineinabyss.keepup.config_sync.templating.Templater
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class DockerSecretsIntegrationTest {
    
    @Test
    fun `should include Docker secrets in inventory templating`() {
        val tempSecretsDir = createTempDirectory("docker-secrets-integration")
        
        try {
            // Create test secret files
            (tempSecretsDir / "db_password").writeText("mysecretpassword")
            (tempSecretsDir / "api_token").writeText("abc123token")
            
            // Create a test inventory that uses Docker secrets
            val inventoryYaml = """
                test-config:
                  variables:
                    dbPassword: "{{ secret.db_password }}"
                    apiToken: "{{ secret.api_token }}"
                    regularVar: "regular_value"
                  copyPaths:
                    - source: test-file
                      dest: dest-file
            """.trimIndent()
            
            val templater = Templater()
            val inventory = Inventory.from(
                templater = templater,
                inputStream = ByteArrayInputStream(inventoryYaml.toByteArray()),
                environment = mapOf("TEST_ENV" to "env_value"),
                enableDockerSecrets = true,
                dockerSecretsPath = tempSecretsDir
            )
            
            // Get the configuration and verify secrets were templated
            val configs = inventory.getOrCreateConfigs("test-config")
            assertEquals(1, configs.size)
            
            val config = configs[0]
            assertEquals("mysecretpassword", config.variables["dbPassword"])
            assertEquals("abc123token", config.variables["apiToken"])
            assertEquals("regular_value", config.variables["regularVar"])
            
            // Verify copy paths are also preserved
            assertEquals(1, config.copyPaths.size)
            assertEquals("test-file", config.copyPaths[0].source)
            assertEquals("dest-file", config.copyPaths[0].dest)
        } finally {
            tempSecretsDir.deleteRecursively()
        }
    }
    
    @Test
    fun `should work without Docker secrets when disabled`() {
        val tempSecretsDir = createTempDirectory("docker-secrets-disabled")
        
        try {
            // Create test secret files (these should be ignored)
            (tempSecretsDir / "db_password").writeText("mysecretpassword")
            
            val inventoryYaml = """
                test-config:
                  variables:
                    dbPassword: "{{ secret.db_password | default('fallback_password') }}"
                    regularVar: "regular_value"
            """.trimIndent()
            
            val templater = Templater()
            val inventory = Inventory.from(
                templater = templater,
                inputStream = ByteArrayInputStream(inventoryYaml.toByteArray()),
                environment = mapOf(),
                enableDockerSecrets = false,
                dockerSecretsPath = tempSecretsDir
            )
            
            val configs = inventory.getOrCreateConfigs("test-config")
            val config = configs[0]
            
            // Should use fallback since Docker secrets are disabled
            assertEquals("fallback_password", config.variables["dbPassword"])
            assertEquals("regular_value", config.variables["regularVar"])
        } finally {
            tempSecretsDir.deleteRecursively()
        }
    }
    
    @Test
    fun `should handle missing secrets gracefully`() {
        val tempSecretsDir = createTempDirectory("missing-secrets-test")
        
        try {
            // Only create one secret, leave another missing
            (tempSecretsDir / "existing_secret").writeText("exists")
            
            val inventoryYaml = """
                test-config:
                  variables:
                    existingSecret: "{{ secret.existing_secret }}"
                    missingSecret: "{{ secret.missing_secret | default('default_value') }}"
            """.trimIndent()
            
            val templater = Templater()
            val inventory = Inventory.from(
                templater = templater,
                inputStream = ByteArrayInputStream(inventoryYaml.toByteArray()),
                environment = mapOf(),
                enableDockerSecrets = true,
                dockerSecretsPath = tempSecretsDir
            )
            
            val configs = inventory.getOrCreateConfigs("test-config")
            val config = configs[0]
            
            assertEquals("exists", config.variables["existingSecret"])
            assertEquals("default_value", config.variables["missingSecret"])
        } finally {
            tempSecretsDir.deleteRecursively()
        }
    }
    
    @Test
    fun `should work with environment variables and Docker secrets together`() {
        val tempSecretsDir = createTempDirectory("env-and-secrets-test")
        
        try {
            (tempSecretsDir / "secret_key").writeText("secret_from_docker")
            
            val inventoryYaml = """
                test-config:
                  variables:
                    secretVar: "{{ secret.secret_key }}"
                    envVar: "{{ ENV_VAR }}"
                    combined: "env={{ ENV_VAR }}, secret={{ secret.secret_key }}"
            """.trimIndent()
            
            val templater = Templater()
            val inventory = Inventory.from(
                templater = templater,
                inputStream = ByteArrayInputStream(inventoryYaml.toByteArray()),
                environment = mapOf("ENV_VAR" to "env_value"),
                enableDockerSecrets = true,
                dockerSecretsPath = tempSecretsDir
            )
            
            val configs = inventory.getOrCreateConfigs("test-config")
            val config = configs[0]
            
            assertEquals("secret_from_docker", config.variables["secretVar"])
            assertEquals("env_value", config.variables["envVar"])
            assertEquals("env=env_value, secret=secret_from_docker", config.variables["combined"])
        } finally {
            tempSecretsDir.deleteRecursively()
        }
    }
}