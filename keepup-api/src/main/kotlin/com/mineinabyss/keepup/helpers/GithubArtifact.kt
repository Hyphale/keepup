package com.mineinabyss.keepup.helpers

import com.mineinabyss.keepup.downloads.parsing.DownloadSource

data class RepositoryArtifact(
    val source: DownloadSource,
    val repo: String,
    val releaseVersion: String,
    val regex: String,
) {
    val calculatedRegex = regex.toRegex()

    companion object {
        fun from(prefix: String, source: DownloadSource): RepositoryArtifact {
            val (repo, release, grep) = source.query.removePrefix(prefix).split(":")
            return RepositoryArtifact(source, repo, release, grep)
        }
    }
}
