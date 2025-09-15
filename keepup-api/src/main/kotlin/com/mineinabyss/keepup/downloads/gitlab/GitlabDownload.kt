package com.mineinabyss.keepup.downloads.gitlab

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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.time.Duration.Companion.seconds

/**
 * Ex url "gitlab:MineInAbyss/Idofront:v0.20.6:*.jar"
 */
class GitlabDownload(
    val client: HttpClient,
    val config: GitlabConfig,
    val artifact: RepositoryArtifact,
    val targetDir: Path,
) : Downloader {
    val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class GitlabRelease(
        @Suppress("PropertyName")
        val released_at: String,
        val assets: Assets,
    )

    @Serializable
    data class Assets(
        val links: List<Asset>
    )

    @Serializable
    data class Asset(
        val name: String,
        val url: String,
    )

    override suspend fun download(): List<DownloadResult> {
        val version = artifact.releaseVersion
        val repositoryURL = artifact.repo.replace("/", "%2F")

        val request = CachedRequest(
            targetDir / "response-${artifact.repo.replace("/", "-")}-$version",
            expiration = config.cacheExpirationTime.takeIf { version == "latest" },
            evaluate = {
                val response = client.get {
                    timeout {
                        requestTimeoutMillis = 30.seconds.inWholeMilliseconds
                    }
                    if (version == "latest")
                        url("https://gitlab.com/api/v4/projects/$repositoryURL/releases")
                    else {
                        val releaseURL = artifact.releaseVersion.replace("/", "%2F")
                        url("https://gitlab.com/api/v4/projects/$repositoryURL/releases/$releaseURL")
                    }
                    headers {
                        if (config.gitlabAccessToken != null)
                            append("PRIVATE-TOKEN", config.gitlabAccessToken)
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

        val release: GitlabRelease = runCatching {
            if (version == "latest") {
                json.decodeFromString(ListSerializer(GitlabRelease.serializer()), body).maxBy { it.released_at }
            } else {
                json.decodeFromString(GitlabRelease.serializer(), body)
            }
        }.getOrElse {
            return listOf(
                DownloadResult.Failure(
                    "Failed to parse GitHub response:\n${it.message}",
                    artifact.source.keyInConfig
                )
            )
        }

        val fullName = TextColors.yellow(artifact.source.keyInConfig)

        if (!response.wasCached) {
            t.println("${MSG.gitlab} $fullName ${TextColors.gray("GET artifact URLs")}")
        }

        return coroutineScope {
            release.assets
                .links
                .filter { it.name.contains(artifact.calculatedRegex) }
                .map {
                    async {
                        HttpDownloader(
                            client = client,
                            source = DownloadSource(artifact.source.keyInConfig, it.url),
                            targetDir = targetDir,
                            fileName = it.name,
                            transformHeader = {
                                header(HttpHeaders.Accept, "application/octet-stream")
                                if (config.gitlabAccessToken != null)
                                    header("PRIVATE-TOKEN", config.gitlabAccessToken)
                            }
                        ).download()
                    }
                }.awaitAll().flatten()
        }
    }
}
