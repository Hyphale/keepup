package com.mineinabyss.keepup.downloads.keeper

import kotlin.time.Duration

data class KeeperConfig(
    val baseUrl: String? = null,
    val authToken: String? = null,
    val cacheExpirationTime: Duration? = null,
)
