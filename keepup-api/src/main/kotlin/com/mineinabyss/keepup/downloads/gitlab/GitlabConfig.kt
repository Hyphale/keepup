package com.mineinabyss.keepup.downloads.gitlab

import kotlin.time.Duration

data class GitlabConfig(
    val gitlabAccessToken: String? = null,
    val cacheExpirationTime: Duration? = null,
)
