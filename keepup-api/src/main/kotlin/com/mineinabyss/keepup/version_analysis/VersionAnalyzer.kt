package com.mineinabyss.keepup.version_analysis

import com.mineinabyss.keepup.downloads.parsing.DownloadSource
import kotlinx.serialization.Serializable

/**
 * Represents a version update for a project
 */
@Serializable
data class VersionUpdate(
    val project: String,
    val oldVersion: String,
    val newVersion: String,
)

/**
 * Result of version analysis containing all detected updates
 */
data class VersionAnalysisResult(
    val updates: Map<String, VersionUpdate>,
)

/**
 * Analyzes download sources to detect version updates
 * Extracts versions from download source URLs and compares them
 * Can also resolve latest versions from remote sources
 */
class VersionAnalyzer(
    private val versionResolvers: List<VersionResolver> = emptyList(),
) {
    /**
     * Analyzes a map of download sources and returns projects with newer versions available
     *
     * @param currentSources Map of project names to their current download sources
     * @param catalogSources Map of project names to their latest download sources from catalog
     * @return VersionAnalysisResult containing a map of projects with available updates
     */
    suspend fun analyzeVersions(
        currentSources: Map<String, DownloadSource>,
        catalogSources: Map<String, DownloadSource>,
    ): VersionAnalysisResult {
        val updates = mutableMapOf<String, VersionUpdate>()

        for ((project, currentSource) in currentSources) {
            val catalogSource = catalogSources[project] ?: continue

            val currentVersion = extractVersion(currentSource.query)
            
            // Try to resolve the latest version using available resolvers
            var newVersion = extractVersion(catalogSource.query)
            if (newVersion == null) {
                newVersion = resolveLatestVersion(catalogSource)
            }

            if (currentVersion != null && newVersion != null && currentVersion != newVersion) {
                if (isNewerVersion(currentVersion, newVersion)) {
                    updates[project] = VersionUpdate(
                        project = project,
                        oldVersion = currentVersion,
                        newVersion = newVersion,
                    )
                }
            }
        }

        return VersionAnalysisResult(updates)
    }

    /**
     * Attempts to resolve the latest version of a source using available version resolvers
     */
    private suspend fun resolveLatestVersion(source: DownloadSource): String? {
        for (resolver in versionResolvers) {
            val version = resolver.resolveLatestVersion(source)
            if (version != null) {
                return version
            }
        }
        return null
    }

    /**
     * Extracts version string from a download source URL or reference
     * Supports GitHub releases, GitLab, HTTP URLs, and other formats
     *
     * @param query The download source query string
     * @return The extracted version, or null if no version could be determined
     */
    fun extractVersion(query: String): String? {
        // Handle GitHub releases format: github:owner/repo:version:artifactRegex
        if (query.startsWith("github:")) {
            val parts = query.split(":")
            if (parts.size >= 3) {
                return parts[2]
            }
        }

        // Handle GitLab format: gitlab:owner/repo:version:artifactRegex
        if (query.startsWith("gitlab:")) {
            val parts = query.split(":")
            if (parts.size >= 3) {
                return parts[2]
            }
        }

        // Handle Keeper format: keeper:namespace/artifact:version
        if (query.startsWith("keeper:")) {
            val parts = query.split(":")
            if (parts.size >= 3) {
                return parts[2]
            }
        }

        // Handle Nexus format: nexus:groupId:artifactId:version[:classifier[:extension]]
        if (query.startsWith("nexus:")) {
            val parts = query.split(":")
            if (parts.size >= 4) {
                return parts[3]
            }
        }

        // Handle HTTP URLs with version patterns
        // Try to extract version from common patterns like /v1.2.3 or -1.2.3
        val versionPattern = """[/-]v?(\d+\.\d+(?:\.\d+)?(?:-[a-zA-Z0-9]+)?)""".toRegex()
        val match = versionPattern.find(query)
        if (match != null) {
            return match.groupValues[1]
        }

        // Try to extract from filename patterns
        val filenameVersionPattern = """[\w-]+[\.-]v?(\d+\.\d+(?:\.\d+)?(?:-[a-zA-Z0-9]+)?)""".toRegex()
        val filenameMatch = filenameVersionPattern.find(query)
        if (filenameMatch != null) {
            return filenameMatch.groupValues[1]
        }

        return null
    }

    /**
     * Compares two version strings and returns true if newVersion is newer than currentVersion
     * Uses semantic versioning comparison when possible
     *
     * @param currentVersion The current version string
     * @param newVersion The new version string to compare against
     * @return true if newVersion is newer, false otherwise
     */
    fun isNewerVersion(currentVersion: String, newVersion: String): Boolean {
        // Quick path: if versions are equal, no update
        if (currentVersion == newVersion) return false

        // Try to parse as semantic versions
        val currentParts = parseSemanticsVersion(currentVersion)
        val newParts = parseSemanticsVersion(newVersion)

        if (currentParts != null && newParts != null) {
            // Compare major version
            if (newParts.major > currentParts.major) return true
            if (newParts.major < currentParts.major) return false

            // Compare minor version
            if (newParts.minor > currentParts.minor) return true
            if (newParts.minor < currentParts.minor) return false

            // Compare patch version
            if (newParts.patch > currentParts.patch) return true
            if (newParts.patch < currentParts.patch) return false

            // Compare prerelease (if current has prerelease but new doesn't, new is newer)
            if (currentParts.prerelease != null && newParts.prerelease == null) return true
            if (currentParts.prerelease == null && newParts.prerelease != null) return false

            if (currentParts.prerelease != null && newParts.prerelease != null) {
                return newParts.prerelease > currentParts.prerelease
            }

            return false
        }

        // Fallback to lexicographic comparison
        return newVersion > currentVersion
    }

    private data class SemanticVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val prerelease: String? = null,
    )

    private fun parseSemanticsVersion(version: String): SemanticVersion? {
        val cleanVersion = version.removePrefix("v")
        
        // Match pattern like 1.2.3 or 1.2.3-alpha or 1.2.3-alpha.1
        val pattern = """(\d+)\.(\d+)(?:\.(\d+))?(?:-(.+))?""".toRegex()
        val match = pattern.matchEntire(cleanVersion) ?: return null

        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = if (match.groupValues[3].isNotEmpty()) match.groupValues[3].toIntOrNull() ?: 0 else 0
        val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }

        return SemanticVersion(major, minor, patch, prerelease)
    }
}
