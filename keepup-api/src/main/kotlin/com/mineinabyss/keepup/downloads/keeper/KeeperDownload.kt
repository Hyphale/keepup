package com.mineinabyss.keepup.downloads.keeper

import com.github.ajalt.mordant.rendering.TextColors
import com.mineinabyss.keepup.downloads.DownloadResult
import com.mineinabyss.keepup.downloads.Downloader
import com.mineinabyss.keepup.downloads.http.HttpDownloader
import com.mineinabyss.keepup.downloads.parsing.DownloadSource
import com.mineinabyss.keepup.helpers.MSG
import com.mineinabyss.keepup.helpers.RepositoryArtifact
import com.mineinabyss.keepup.helpers.http.CachedRequest
import com.mineinabyss.keepup.t
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.time.Duration.Companion.seconds

/**
 * Ex url "keeper:myapp:1.0.0:*.jar"
 */
class KeeperDownload(
    val client: HttpClient,
    val config: KeeperConfig,
    val artifact: RepositoryArtifact,
    val targetDir: Path,
) : Downloader {
    val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class KeeperArtifact(
        val name: String,
        val downloadUrl: String,
    )

    override suspend fun download(): List<DownloadResult> {
        if (config.baseUrl == null) {
            return listOf(
                DownloadResult.Failure(
                    "Keeper base URL not configured",
                    artifact.source.keyInConfig
                )
            )
        }

        val baseUrl = config.baseUrl.trimEnd('/')
        val project = artifact.repo
        val version = artifact.releaseVersion

        val request = CachedRequest(
            targetDir / "response-${project}-$version",
            expiration = config.cacheExpirationTime,
            evaluate = {
                val response = client.get {
                    timeout {
                        requestTimeoutMillis = 30.seconds.inWholeMilliseconds
                    }
                    url("$baseUrl/api/v1/search?project=$project&version=$version")
                    headers {
                        if (config.authToken != null)
                            append(HttpHeaders.Authorization, "Bearer ${config.authToken}")
                    }
                }

                if (response.status != HttpStatusCode.OK) {
                    Result.failure(RuntimeException("GET responded with error: ${response.status}, ${response.bodyAsText()}"))
                } else {
                    Result.success(response.bodyAsText())
                }
            }
        )

        val response = request.getFromCacheOrEval().getOrElse {
            return listOf(DownloadResult.Failure(it.message ?: "", artifact.source.keyInConfig))
        }

        val body = response.result

        val results = runCatching {
            json.parseToJsonElement(body).jsonArray
        }.getOrElse {
            return listOf(
                DownloadResult.Failure(
                    "Failed to parse Keeper response:\n${it.message}",
                    artifact.source.keyInConfig
                )
            )
        }

        if (results.isEmpty()) {
            return listOf(
                DownloadResult.Failure(
                    "No artifacts found for $project:$version",
                    artifact.source.keyInConfig
                )
            )
        }

        val fullName = TextColors.yellow(artifact.source.keyInConfig)

        if (!response.wasCached) {
            t.println("${MSG.keeper} $fullName ${TextColors.gray("GET artifact URLs")}")
        }

        return coroutineScope {
            results
                .mapNotNull { item ->
                    val artifacts = item.jsonObject["artifacts"]?.jsonArray.orEmpty()
                    artifacts.mapNotNull { artifactObj ->
                        val name = artifactObj.jsonObject["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val downloadUrl = artifactObj.jsonObject["downloadUrl"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        name to downloadUrl
                    }
                }
                .flatten()
                .filter { (name, _) -> name.contains(this@KeeperDownload.artifact.calculatedRegex) }
                .map { (name, downloadUrl) ->
                    async {
                        HttpDownloader(
                            client = client,
                            source = DownloadSource(artifact.source.keyInConfig, "$baseUrl/$downloadUrl"),
                            targetDir = targetDir,
                            fileName = name,
                            transformHeader = {
                                if (config.authToken != null)
                                    header(HttpHeaders.Authorization, "Bearer ${config.authToken}")
                            }
                        ).download()
                    }
                }
                .awaitAll()
                .flatten()
        }
    }
}
