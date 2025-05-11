package com.mineinabyss.keepup.downloads.nexus

import com.github.zafarkhaja.semver.Version
import com.mineinabyss.keepup.downloads.DownloadResult
import com.mineinabyss.keepup.downloads.Downloader
import com.mineinabyss.keepup.downloads.parsing.DownloadSource
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Configuration for Nexus download with optional basic auth.
 */
data class NexusConfig(
    val baseUrl: String = "ignored",
    val username: String? = null,
    val password: String? = null,
    val defaultClassifier: String? = null,
    val defaultExtension: String = "jar",
)

/**
 * Represents a Maven artifact coordinate.
 */
data class NexusArtifact(
    val group: String,
    val name: String,
    val version: String,
    var classifier: String? = null,
    var extension: String = "jar",
) {

    fun toFileName(): String {
        val baseName = "$name-$version"
        val classifierSuffix = classifier?.let { "-$it" } ?: ""
        return "$baseName$classifierSuffix.$extension"
    }

    companion object {
        /**
         * Parses "nexus:group:artifact:version[:classifier][@extension]".
         */
        fun from(source: DownloadSource): NexusArtifact {
            val coord = source.query.removePrefix("nexus:")
            val parts = coord.split("@", limit = 2)
            val tokens = parts[0].split(":")
            require(tokens.size in 3..4) { "Invalid Nexus coordinate: $coord" }
            val ext = parts.getOrNull(1).orEmpty().ifBlank { "jar" }
            return NexusArtifact(
                group = tokens[0],
                name = tokens[1],
                version = tokens[2],
                classifier = tokens.getOrNull(3),
                extension = ext
            )
        }
    }
}

/**
 * Downloads artifacts from Nexus3, picking the highest semver version matching classifier and extension.
 * Skips if the target file already exists in targetDir.
 */
class NexusDownload(
    private val client: HttpClient,
    private val config: NexusConfig,
    private val source: DownloadSource,
    private val targetDir: Path,
) : Downloader {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun download(): List<DownloadResult> {
        val artifact = NexusArtifact.from(source).apply {
            classifier = classifier ?: config.defaultClassifier
            extension = extension.ifBlank { config.defaultExtension }
        }

        val initFile = targetDir.resolve(artifact.toFileName())

        // 1. Skip if target already exists
        if (initFile.exists()) {
            return listOf(DownloadResult.SkippedBecauseCached(initFile, source.keyInConfig))
        }

        // Basic auth header if provided
        val authHeader = config.username?.takeIf { it.isNotBlank() }?.let { user ->
            config.password?.let { pass ->
                val creds = "$user:$pass"
                "Basic ${Base64.getEncoder().encodeToString(creds.toByteArray())}"
            }
        }

        // Build search URL
        val base = config.baseUrl.takeIf { it.startsWith("http") } ?: "https://${config.baseUrl}"
        val searchUri = URLBuilder("$base/service/rest/v1/search").apply {
            parameters.append("group", artifact.group)
            parameters.append("name", artifact.name)
            parameters.append("version", artifact.version)
        }.buildString()

        // Execute search
        val resp = client.get(searchUri) {
            authHeader?.let { header(HttpHeaders.Authorization, it) }
        } as HttpResponse
        if (resp.status != HttpStatusCode.OK) {
            return listOf(
                DownloadResult.Failure("Nexus search failed: HTTP ${resp.status.value}", source.keyInConfig)
            )
        }

        // Parse items
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

        // Select highest version asset via downloadUrl filename
        var bestVersion: Version? = null
        var bestAssetUrl: String? = null
        items.forEach { item ->
            val verStr = item.jsonObject["version"]?.jsonPrimitive?.content ?: return@forEach
            val v = runCatching { Version.valueOf(verStr) }.getOrNull() ?: return@forEach
            item.jsonObject["assets"]?.jsonArray.orEmpty().forEach { asset ->
                val urlField = asset.jsonObject["downloadUrl"]?.jsonPrimitive?.content ?: return@forEach
                val filename = urlField.substringAfterLast("/")
                if (!filename.endsWith(".${artifact.extension}")) return@forEach
                artifact.classifier?.let { cls -> if (!filename.contains("-$cls.")) return@forEach }
                if (bestVersion == null || v.greaterThan(bestVersion)) {
                    bestVersion = v
                    bestAssetUrl = urlField
                }
            }
        }

        // If no asset found
        val downloadUrl = bestAssetUrl ?: return listOf(
            DownloadResult.Failure(
                "No matching asset for ${artifact.group}:${artifact.name}:${artifact.version}" +
                        " (classifier=${artifact.classifier}, extension=${artifact.extension})",
                source.keyInConfig
            )
        )

        // Determine output file path
        val filename = downloadUrl.substringAfterLast("/")
        val outFile = targetDir.resolve(filename)

        // 1. Skip if target already exists
        if (outFile.exists()) {
            return listOf(DownloadResult.SkippedBecauseCached(outFile, source.keyInConfig))
        }

        // Ensure targetDir exists
        targetDir.createDirectories()

        // Stream download
        val channel = client.get(downloadUrl) {
            authHeader?.let { header(HttpHeaders.Authorization, it) }
        }.bodyAsChannel()

        withContext(Dispatchers.IO) {
            Files.newOutputStream(outFile)
        }.use { channel.copyTo(it) }

        return listOf(DownloadResult.Downloaded(outFile, source.keyInConfig))
    }
}
