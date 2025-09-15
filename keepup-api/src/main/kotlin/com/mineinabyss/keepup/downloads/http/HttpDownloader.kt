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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*
import kotlin.time.Duration.Companion.seconds

class HttpDownloader(
    val client: HttpClient,
    val source: DownloadSource,
    val targetDir: Path,
    val fileName: String = source.query.substringAfterLast("/"),
    val overrideWhenHeadersChange: Boolean = false,
    val transformHeader: HttpRequestBuilder.() -> Unit = { },
) : Downloader {
    suspend fun getCacheString(query: String): String {
        val headers = client.head(source.query)
        val length = headers.contentLength()
        val lastModified = headers.lastModified()
        return "Last-Modified: $lastModified, Content-Length: $length"
    }

    override suspend fun download(): List<DownloadResult> {
        val cacheFile = targetDir.resolve("$fileName.cache")
        val targetFile = targetDir.resolve(fileName)
        val cacheString = if (overrideWhenHeadersChange) getCacheString(source.query) else null

        // Check if target already exists and skip if it does, check last modified headers if overrideWhenHeadersChange is true
        if (targetFile.exists() && (cacheString == null || (cacheFile.exists() && cacheFile.readText() == cacheString)))
            return listOf(DownloadResult.SkippedBecauseCached(targetFile, source.keyInConfig))


        return withContext(Dispatchers.IO) {
            targetFile.deleteIfExists()

            val response = client.get {
                url(source.query)
                timeout {
                    requestTimeoutMillis = 30.seconds.inWholeMilliseconds
                }
                transformHeader()
            }

            if (response.status != HttpStatusCode.OK) {
                return@withContext listOf(
                    DownloadResult.Failure(
                        message = "Failed to download ${source.query}, status code: ${response.status}",
                        keyInConfig = source.keyInConfig,
                    )
                )
            }

            response.bodyAsChannel()
                .copyAndClose(targetFile.toFile().writeChannel())

            // Only mark as cached after download is complete
            cacheFile.deleteIfExists()
            cacheFile.createFile().writeText(cacheString ?: getCacheString(source.query))
            return@withContext listOf(DownloadResult.Downloaded(targetFile, source.keyInConfig))
        }
    }
}
