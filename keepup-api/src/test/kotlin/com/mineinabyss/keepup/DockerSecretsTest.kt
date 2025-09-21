package com.mineinabyss.keepup

import com.mineinabyss.keepup.helpers.DockerSecrets
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class DockerSecretsTest {
    
    @Test
    fun `should read Docker secrets from directory`() {
        val tempDir = createTempDirectory("docker-secrets-test")
        
        try {
            // Create test secret files
            (tempDir / "db_password").writeText("supersecret123")
            (tempDir / "api_key").writeText("abc123xyz")
            (tempDir / "config_token").writeText("token-with-newline\n")
            
            val result = DockerSecrets.readSecrets(tempDir, enableLogging = false)
            
            assertEquals(1, result.size)
            assertTrue("secret" in result)
            
            val secrets = result["secret"] as Map<*, *>
            assertEquals(3, secrets.size)
            assertEquals("supersecret123", secrets["db_password"])
            assertEquals("abc123xyz", secrets["api_key"])
            assertEquals("token-with-newline", secrets["config_token"]) // Should trim newlines
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    @Test
    fun `should return empty map when secrets directory does not exist`() {
        val nonExistentPath = Path("/does/not/exist")
        val result = DockerSecrets.readSecrets(nonExistentPath, enableLogging = false)
        
        assertEquals(1, result.size)
        assertTrue("secret" in result)
        val secrets = result["secret"] as Map<*, *>
        assertTrue(secrets.isEmpty())
    }
    
    @Test
    fun `should return empty map when secrets path is not a directory`() {
        val tempFile = createTempFile("not-a-directory")
        
        try {
            tempFile.writeText("this is a file, not a directory")
            val result = DockerSecrets.readSecrets(tempFile, enableLogging = false)
            
            assertEquals(1, result.size)
            assertTrue("secret" in result)
            val secrets = result["secret"] as Map<*, *>
            assertTrue(secrets.isEmpty())
        } finally {
            tempFile.deleteIfExists()
        }
    }
    
    @Test
    fun `should handle empty secrets directory`() {
        val tempDir = createTempDirectory("empty-secrets")
        
        try {
            val result = DockerSecrets.readSecrets(tempDir, enableLogging = false)
            assertEquals(1, result.size)
            assertTrue("secret" in result)
            val secrets = result["secret"] as Map<*, *>
            assertTrue(secrets.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    @Test
    fun `should read specific secret by name`() {
        val tempDir = createTempDirectory("specific-secret-test")
        
        try {
            (tempDir / "my_secret").writeText("secret_value")
            (tempDir / "other_secret").writeText("other_value")
            
            val secretValue = DockerSecrets.readSecret("my_secret", tempDir)
            assertEquals("secret_value", secretValue)
            
            val nonExistentSecret = DockerSecrets.readSecret("does_not_exist", tempDir)
            assertEquals(null, nonExistentSecret)
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    @Test
    fun `should ignore subdirectories when reading secrets`() {
        val tempDir = createTempDirectory("ignore-subdirs-test")
        
        try {
            (tempDir / "valid_secret").writeText("valid_value")
            (tempDir / "subdir").createDirectory()
            (tempDir / "subdir" / "nested_secret").writeText("nested_value")
            
            val result = DockerSecrets.readSecrets(tempDir, enableLogging = false)
            
            assertEquals(1, result.size)
            assertTrue("secret" in result)
            val secrets = result["secret"] as Map<*, *>
            assertEquals(1, secrets.size)
            assertEquals("valid_value", secrets["valid_secret"])
            assertTrue("nested_secret" !in secrets)
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    @Test
    fun `should handle secrets with special characters in names`() {
        val tempDir = createTempDirectory("special-chars-test")
        
        try {
            (tempDir / "secret-with-dashes").writeText("dash_value")
            (tempDir / "secret_with_underscores").writeText("underscore_value")
            (tempDir / "secret.with.dots").writeText("dot_value")
            
            val result = DockerSecrets.readSecrets(tempDir, enableLogging = false)
            
            assertEquals(1, result.size)
            assertTrue("secret" in result)
            val secrets = result["secret"] as Map<*, *>
            assertEquals(3, secrets.size)
            assertEquals("dash_value", secrets["secret-with-dashes"])
            assertEquals("underscore_value", secrets["secret_with_underscores"])
            assertEquals("dot_value", secrets["secret.with.dots"])
        } finally {
            tempDir.deleteRecursively()
        }
    }
}