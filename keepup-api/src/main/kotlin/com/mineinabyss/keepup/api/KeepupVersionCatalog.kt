package com.mineinabyss.keepup.api

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.decodeFromStream
import com.mineinabyss.keepup.downloads.parsing.DownloadSource
import kotlinx.serialization.Serializable
import java.io.InputStream

data object KeepupVersionCatalog {
    private val catalog: MutableMap<String, DownloadSource> = mutableMapOf()

    fun size() = catalog.size
    operator fun get(key: String) = catalog[key];
    operator fun set(key: String, source: DownloadSource) {
        catalog[key] = source;
    }
}

@Serializable
private data class YamlVersionCatalog(
    val catalog: Map<String, String>
)

class KeepupVersionsCatalogParser {
    fun parse(
        input: InputStream
    ): Unit {
        val catalog = Yaml().decodeFromStream<YamlVersionCatalog>(input)
        catalog.catalog.forEach { key, source ->
            KeepupVersionCatalog[key] = DownloadSource(key, source)
        }
    }
}
