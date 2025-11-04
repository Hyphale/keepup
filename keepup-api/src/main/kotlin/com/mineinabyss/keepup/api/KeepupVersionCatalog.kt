package com.mineinabyss.keepup.api

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.decodeFromStream
import com.mineinabyss.keepup.config_sync.templating.Templater
import com.mineinabyss.keepup.downloads.parsing.DownloadSource
import com.mineinabyss.keepup.helpers.MSG
import com.mineinabyss.keepup.t
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
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
        templater: Templater,
        input: InputStream
    ): Unit {
        val templatedText = templater.template(
            input.bufferedReader().readText(),
            mapOf()
        ).getOrElse {
            t.println("${MSG.error} Failed to template catalog file!")
            throw it
        }
        val catalog = Yaml().decodeFromString<YamlVersionCatalog>(templatedText)
        catalog.catalog.forEach { key, source ->
            KeepupVersionCatalog[key] = DownloadSource(key, source)
        }
    }
}
