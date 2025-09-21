package com.mineinabyss.keepup.helpers

import com.mineinabyss.keepup.t
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Utility class for reading Docker Swarm secrets from the filesystem.
 * In Docker Swarm, secrets are mounted as files in the `/run/secrets` directory by default.
 */
object DockerSecrets {
    private const val DEFAULT_SECRETS_PATH = "/run/secrets"
    
    /**
     * Reads all Docker Swarm secrets from the specified secrets directory.
     * 
     * @param secretsPath The path to the directory containing secret files. Defaults to `/run/secrets`.
     * @param enableLogging Whether to log informational messages about secret reading. Defaults to true.
     * @return A map containing a "secret" key with nested secret values
     */
    fun readSecrets(
        secretsPath: Path = Path(DEFAULT_SECRETS_PATH),
        enableLogging: Boolean = true
    ): Map<String, Any> {
        val secrets = mutableMapOf<String, String>()
        
        if (!secretsPath.exists()) {
            if (enableLogging) {
                t.println("${MSG.info} Docker secrets directory not found: $secretsPath")
            }
            return mapOf("secret" to emptyMap<String, String>())
        }
        
        if (!secretsPath.isDirectory()) {
            if (enableLogging) {
                t.println("${MSG.warn} Docker secrets path is not a directory: $secretsPath")
            }
            return mapOf("secret" to emptyMap<String, String>())
        }
        
        try {
            val secretFiles = secretsPath.listDirectoryEntries().filter { it.isRegularFile() }
            
            if (enableLogging && secretFiles.isNotEmpty()) {
                t.println("${MSG.info} Found ${secretFiles.size} Docker secrets in $secretsPath")
            }
            
            secretFiles.forEach { secretFile ->
                try {
                    val secretName = secretFile.fileName.toString()
                    val secretValue = secretFile.readText().trim() // Remove any trailing newlines
                    secrets[secretName] = secretValue
                    
                    if (enableLogging) {
                        t.println("${MSG.info} Loaded Docker secret: $secretName")
                    }
                } catch (e: Exception) {
                    if (enableLogging) {
                        t.println("${MSG.error} Failed to read Docker secret ${secretFile.fileName}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            if (enableLogging) {
                t.println("${MSG.error} Failed to list Docker secrets directory $secretsPath: ${e.message}")
            }
        }
        
        return mapOf("secret" to secrets)
    }
    
    /**
     * Reads a specific Docker Swarm secret by name.
     * 
     * @param secretName The name of the secret to read
     * @param secretsPath The path to the directory containing secret files. Defaults to `/run/secrets`.
     * @return The secret value, or null if the secret doesn't exist or cannot be read
     */
    fun readSecret(
        secretName: String,
        secretsPath: Path = Path(DEFAULT_SECRETS_PATH)
    ): String? {
        val secretFile = secretsPath / secretName
        
        return try {
            if (secretFile.exists() && secretFile.isRegularFile()) {
                secretFile.readText().trim()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}