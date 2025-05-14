package com.mineinabyss.keepup.downloads.http

import com.mineinabyss.keepup.downloads.DownloadResult
import com.mineinabyss.keepup.downloads.Downloader
import com.mineinabyss.keepup.downloads.parsing.DownloadSource
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import java.nio.file.Path
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.io.path.*
import kotlin.time.Duration.Companion.seconds

sealed interface HttpAuth {

    data class Basic(
        val username: String,
        val password: String,
    ) : HttpAuth

    data class Bearer(
        val token: String,
    ) : HttpAuth

}

class HttpDownloader(
    val client: HttpClient,
    val source: DownloadSource,
    val targetDir: Path,
    val fileName: String = source.query.substringAfterLast("/"),
    val overrideWhenHeadersChange: Boolean = false,
    val auth: HttpAuth? = null,
) : Downloader {
    suspend fun getCacheString(query: String): String {
        val headers = client.head(source.query)
        val length = headers.contentLength()
        val lastModified = headers.lastModified()
        return "Last-Modified: $lastModified, Content-Length: $length"
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun download(): List<DownloadResult> {
        val cacheFile = targetDir.resolve("$fileName.cache")
        val targetFile = targetDir.resolve(fileName)
        val partial = targetDir.resolve("$fileName.partial")
        val cacheString = if (overrideWhenHeadersChange) getCacheString(source.query) else null

        // Check if target already exists and skip if it does, check last modified headers if overrideWhenHeadersChange is true
        if (targetFile.exists() && (cacheString == null || (cacheFile.exists() && cacheFile.readText() == cacheString)))
            return listOf(DownloadResult.SkippedBecauseCached(targetFile, source.keyInConfig))


        // Write to partial file, then move it to target once download is complete
        partial.deleteIfExists()
        val response = client.prepareGet {
            url(source.query)
            timeout {
                requestTimeoutMillis = 30.seconds.inWholeMilliseconds
            }

            if (auth is HttpAuth.Basic) {
                header(HttpHeaders.Authorization, "Basic ${Base64.encode("${auth.username}:${auth.password}".toByteArray())}")
            } else if (auth is HttpAuth.Bearer) {
                header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            }
        }.execute();

        if (response.status != HttpStatusCode.OK) {
            return listOf(
                DownloadResult.Failure(
                    message = "Failed to download ${source.query}, status code: ${response.status}",
                    keyInConfig = source.keyInConfig,
                )
            )
        }

        response.bodyAsChannel()
            .copyAndClose(partial.toFile().writeChannel())

        targetFile.deleteIfExists()
        partial.moveTo(targetFile)

        // Only mark as cached after download is complete
        cacheFile.deleteIfExists()
        cacheFile.createFile().writeText(cacheString ?: getCacheString(source.query))

        return listOf(DownloadResult.Downloaded(targetFile, source.keyInConfig))
    }
}
