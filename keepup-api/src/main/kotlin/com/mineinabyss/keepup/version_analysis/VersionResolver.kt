package com.mineinabyss.keepup.version_analysis

import com.mineinabyss.keepup.downloads.parsing.DownloadSource

/**
 * Interface for resolving the latest version from various version sources
 */
interface VersionResolver {
    /**
     * Attempts to resolve the latest version for a download source
     * 
     * @param source The download source to resolve
     * @return The resolved version string, or null if this resolver cannot handle this source
     */
    suspend fun resolveLatestVersion(source: DownloadSource): String?
}
