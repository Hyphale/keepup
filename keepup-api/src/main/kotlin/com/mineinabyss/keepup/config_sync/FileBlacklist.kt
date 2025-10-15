package com.mineinabyss.keepup.config_sync

import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Manages blacklist rules for files that should not be copied during config sync.
 * This is used to prevent certain file types from being copied to/from destinations.
 */
object FileBlacklist {
    /**
     * List of file extensions that should be blacklisted from copying.
     * These files will be excluded from both source and destination operations.
     */
    private val blacklistedExtensions = setOf("jar")

    /**
     * Checks if a file should be blacklisted based on its extension.
     * 
     * @param path The path to check
     * @return true if the file should be blacklisted, false otherwise
     */
    fun isBlacklisted(path: Path): Boolean {
        return path.extension.lowercase() in blacklistedExtensions
    }

    /**
     * Filters a map of files, removing any blacklisted entries.
     * 
     * @param files Map of destination paths to source paths
     * @return Filtered map with blacklisted files removed
     */
    fun filterBlacklisted(files: Map<Path, Path>): Map<Path, Path> {
        return files.filterNot { (dest, source) ->
            isBlacklisted(dest) || isBlacklisted(source)
        }
    }
}
