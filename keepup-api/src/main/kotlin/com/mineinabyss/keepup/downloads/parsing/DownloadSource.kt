package com.mineinabyss.keepup.downloads.parsing

import com.mineinabyss.keepup.api.KeepupVersionCatalog
import com.mineinabyss.keepup.type_checker.FileType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = DownloadSourceSerializer::class)
data class DownloadSource(
    val keyInConfig: String,
    val query: String,
    val expectedType: FileType? = guessFileType(query),
) {
    companion object {
        fun guessFileType(query: String): FileType? {
            return when {
                query.endsWith(".zip") || query.endsWith(".jar") || query.endsWith(".tar.gz") -> FileType.Archive
                query.endsWith(".html") -> FileType.HTML
                else -> null
            }
        }
    }
}

@Serializable
private data class DownloadSourceSurrogate(
    val keyInConfig: String,
    val query: String,
    val expectedType: FileType? = DownloadSource.guessFileType(query)
)

object DownloadSourceSerializer : KSerializer<DownloadSource> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DownloadSource", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): DownloadSource {
        return try {
            val target = decoder.decodeString()
            KeepupVersionCatalog[target] ?: error("'$target' is not defined in the version catalog")
        } catch (e: SerializationException) {
            val surrogate = decoder.decodeSerializableValue(DownloadSourceSurrogate.serializer())
            DownloadSource(
                keyInConfig = surrogate.keyInConfig,
                query = surrogate.query,
                expectedType = surrogate.expectedType
            )
        }
    }

    override fun serialize(encoder: Encoder, value: DownloadSource) {
        encoder.encodeString(value.keyInConfig)
    }
}