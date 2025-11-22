package com.mineinabyss.keepup.similarfiles

import com.mineinabyss.keepup.downloads.DownloadResult
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

class SimilarFileChecker(
    val dest: Path
) {
    val names = dest
        .listDirectoryEntries()
        .filter { it.isRegularFile() }
        .map { it.name }

    fun findSimilarFileTo(key: String): String? {
        return names.firstOrNull { similar(it, key) }
    }

    fun filterSimilarFiles(results: List<DownloadResult>): List<DownloadResult> {
        return results.map {
            if (it is DownloadResult.HasFiles) {
                val similarFile = this.findSimilarFileTo(it.file.name)
                if (similarFile != null)
                    return@map DownloadResult.SkippedBecauseSimilar(it.keyInConfig, similarFile)
            }
            it
        }
    }
    val String.extension: String get() = takeLastWhile { it != '.' }

    /** Removes everything between the first digit and ext of the file */
    fun String.removeVersion(): String = takeWhile { !it.isDigit() }

    /** Checks if two strings are similar with their versions removed or if the other is contained in the longer */
    fun similar(a: String, b: String): Boolean {
        if (a.extension != b.extension) {
            return false
        }
        val a = a.removeVersion()
        val b = b.removeVersion()
        if (a == b) {
            return true
        } else {
            val (shorter, longer) = if (a.length < b.length) a to b else b to a
            return longer.contains(shorter)
        }
    }
}
