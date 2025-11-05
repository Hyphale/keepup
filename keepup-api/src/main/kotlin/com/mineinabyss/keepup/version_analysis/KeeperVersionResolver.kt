package com.mineinabyss.keepup.version_analysis

import com.mineinabyss.keepup.downloads.keeper.KeeperConfig
import com.mineinabyss.keepup.downloads.parsing.DownloadSource
import com.mineinabyss.keepup.helpers.RepositoryArtifact
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

/**
 * Resolves the latest version for a Keeper artifact by querying the Keeper API
 * Format: keeper:project:version:regex (version can be "latest" to fetch dynamically)
 */
class KeeperVersionResolver(
    val client: HttpClient,
    val config: KeeperConfig,
) : VersionResolver {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetches the latest version for a Keeper artifact
     * 
     * @param source The download source in format keeper:project:version:regex
     * @return The resolved latest version string, or null if unable to resolve
     */
    override suspend fun resolveLatestVersion(source: DownloadSource): String? {
        if (!source.query.startsWith("keeper:")) {
            return null
        }

        if (config.baseUrl == null) {
            return null
        }

        val artifact = RepositoryArtifact.from("keeper:", source)
        val baseUrl = config.baseUrl.trimEnd('/')
        val project = artifact.repo

        // Query the API with "latest" to get the most recent version
        return try {
            val response = client.get {
                timeout {
                    requestTimeoutMillis = 30.seconds.inWholeMilliseconds
                }
                url("$baseUrl/api/v1/search?project=$project&version=latest")
                headers {
                    if (config.authToken != null)
                        append(HttpHeaders.Authorization, "Bearer ${config.authToken}")
                }
            }

            if (response.status != HttpStatusCode.OK) {
                return null
            }

            val body = response.bodyAsText()
            val results = json.parseToJsonElement(body).jsonArray

            if (results.isEmpty()) {
                return null
            }

            // Extract versions from results and find the latest
            val versions = results
                .mapNotNull { item ->
                    item.jsonObject["version"]?.jsonPrimitive?.content
                }
                .distinct()

            // Return the first (most recent) version if available
            versions.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
