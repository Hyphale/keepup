package com.mineinabyss.keepup.downloads.nexus

import com.github.zafarkhaja.semver.Version
import com.mineinabyss.keepup.downloads.DownloadResult
import com.mineinabyss.keepup.downloads.Downloader
import com.mineinabyss.keepup.downloads.parsing.DownloadSource
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.createDirectories

/**
 * Configuration for Nexus Download, including optional basic auth.
 */
data class NexusConfig(
    val baseUrl: String = "nexus.example.com",
    val username: String? = null,
    val password: String? = null,
    val defaultClassifier: String? = null,
    val defaultExtension: String = "jar"
)

/**
 * Parsed Maven artifact coordinates for Nexus.
 */
data class NexusArtifact(
    val group: String,
    val name: String,
    val version: String,
    var classifier: String? = null,
    var extension: String = "jar"
) {
    companion object {
        /**
         * Expect query in form "nexus:group:artifact:version[:classifier][@extension]".
         */
        fun from(source: DownloadSource): NexusArtifact {
            val coord = source.query.removePrefix("nexus:")
            val partsExt = coord.split("@", limit = 2)
            val left = partsExt[0]
            val ext = partsExt.getOrNull(1).orEmpty()
            val tokens = left.split(":")
            require(tokens.size in 3..4) { "Invalid Nexus coordinate: $coord" }
            return NexusArtifact(
                group = tokens[0],
                name = tokens[1],
                version = tokens[2],
                classifier = tokens.getOrNull(3),
                extension = ext.ifBlank { "jar" }
            )
        }
    }

    /**
     * Builds the repository path for the artifact.
     */
    fun path(): String = listOf(
        group.replace('.', '/'),
        name,
        version,
        "${name}-${version}${classifier?.let { "-" + it }.orEmpty()}.${extension}"
    ).joinToString("/")
}

/**
 * Downloader that fetches artifacts from Nexus3 repositories via the REST API, supporting basic auth.
 */
class NexusDownload(
    private val client: HttpClient,
    private val config: NexusConfig,
    private val source: DownloadSource,
    private val targetDir: Path
) : Downloader {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun download(): List<DownloadResult> {
        val artifact = NexusArtifact.from(source)
        artifact.classifier = artifact.classifier ?: config.defaultClassifier
        artifact.extension = artifact.extension.ifBlank { config.defaultExtension }

        // Build basic auth header if credentials provided
        val authHeader = if (!config.username.isNullOrBlank() && config.password != null) {
            val creds = "${config.username}:${config.password}"
            "Basic " + Base64.getEncoder().encodeToString(creds.toByteArray())
        } else null

        // Construct search URL
        val base = if (config.baseUrl.startsWith("http")) config.baseUrl else "https://${config.baseUrl}"
        val searchUri = URLBuilder("${base}/service/rest/v1/search").apply {
            parameters.append("group", artifact.group)
            parameters.append("name", artifact.name)
            parameters.append("version", artifact.version)
        }.buildString()

        // Perform search with optional auth
        val resp = client.get(searchUri) {
            authHeader?.let { header(HttpHeaders.Authorization, it) }
        }
        if (resp.status != HttpStatusCode.OK) {
            return listOf(
                DownloadResult.Failure(
                    "Nexus search failed: HTTP ${resp.status.value}",
                    source.keyInConfig
                )
            )
        }

        val items = json.parseToJsonElement(resp.bodyAsText())
            .jsonObject["items"]?.jsonArray.orEmpty()
        if (items.isEmpty()) {
            return listOf(
                DownloadResult.Failure(
                    "No artifacts found for ${artifact.group}:${artifact.name}:${artifact.version}",
                    source.keyInConfig
                )
            )
        }

        // Select highest version via downloadUrl
        var bestVersion: Version? = null
        var bestAssetUrl: String? = null
        for (item in items) {
            val verStr = item.jsonObject["version"]?.jsonPrimitive?.content ?: continue
            val v = runCatching { Version.valueOf(verStr) }.getOrNull() ?: continue
            var candidateUrl: String? = null
            item.jsonObject["assets"]?.jsonArray.orEmpty().forEach { asset ->
                val urlField = asset.jsonObject["downloadUrl"]?.jsonPrimitive?.content ?: return@forEach
                val filename = urlField.substringAfterLast("/")
                if (!filename.endsWith(".${artifact.extension}")) return@forEach
                if (!artifact.classifier.isNullOrBlank() && !filename.contains("-${artifact.classifier}.")) return@forEach
                candidateUrl = urlField
                return@forEach
            }
            if (candidateUrl != null && (bestVersion == null || v.greaterThan(bestVersion))) {
                bestVersion = v
                bestAssetUrl = candidateUrl
            }
        }

        val downloadUrl = bestAssetUrl ?: return listOf(
            DownloadResult.Failure(
                "No matching asset for ${artifact.group}:${artifact.name}:${artifact.version}" +
                        " (classifier=${artifact.classifier}, extension=${artifact.extension})",
                source.keyInConfig
            )
        )

        // Prepare output
        val filename = downloadUrl.substringAfterLast("/")
        targetDir.createDirectories()
        val outFile = targetDir.resolve(filename)

        // Stream download with auth
        val channel = client.get(downloadUrl) {
            authHeader?.let { header(HttpHeaders.Authorization, it) }
        }.bodyAsChannel()

        withContext(Dispatchers.IO) {
            Files.newOutputStream(outFile)
        }.use { channel.copyTo(it) }

        return listOf(DownloadResult.Downloaded(outFile, source.keyInConfig))
    }
}