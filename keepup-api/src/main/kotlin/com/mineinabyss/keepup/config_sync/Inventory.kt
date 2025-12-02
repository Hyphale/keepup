package com.mineinabyss.keepup.config_sync

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlContentPolymorphicSerializer
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.mineinabyss.keepup.config_sync.templating.Templater
import com.mineinabyss.keepup.downloads.parsing.DownloadSource
import com.mineinabyss.keepup.helpers.DockerSecrets
import com.mineinabyss.keepup.helpers.InnerSerializer
import com.mineinabyss.keepup.helpers.MSG
import com.mineinabyss.keepup.t
import kotlinx.serialization.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.*

@Serializable(with = Inventory.Serializer::class)
class Inventory(
    val configs: Map<String, ConfigDefinition>,
) {
    fun getOrCreateConfigs(host: String, configsRoot: Path? = null): List<ConfigDefinition> {
        val global = configs["global"]
        val includes = getDeepIncludes(names = listOf(host), configsRoot = configsRoot).distinct().reversed()
        return listOfNotNull(global) + includes.map {
            configs[it] ?: getConfigFromDirectory(it, configsRoot) ?: ConfigDefinition(copyPaths = listOf(CopyPath(source = it)))
        }
    }

    tailrec fun getDeepIncludes(acc: MutableList<String> = mutableListOf(), names: List<String>, configsRoot: Path? = null): List<String> {
        if (names.isEmpty()) return acc
        val namesSet = names.toSet()
        val nonCyclicNames = names - acc.filter { it in namesSet }.toSet()
        acc.addAll(nonCyclicNames)
        val newNames = nonCyclicNames.flatMap {
            val config = configs[it] ?: getConfigFromDirectory(it, configsRoot)
            config?.include?.reversed() ?: emptyList()
        }
        return getDeepIncludes(acc, newNames, configsRoot)
    }

    private fun getConfigFromDirectory(name: String, configsRoot: Path?): ConfigDefinition? {
        if (configsRoot == null) return null

        val directoryPath = (configsRoot / name)
            .takeIf { it.isDirectory() } ?: return null

        val includeFileNames = listOf("include.yml", "feature.yml", "server.yml", "event.yml")
        val includeFile = includeFileNames
            .map { directoryPath / it }
            .firstOrNull { it.exists() }
            ?: return null

        return try {
            t.println("${MSG.info} Loading config from directory: $directoryPath (using ${includeFile.fileName})")
            val templater = Templater()

            val dockerSecrets = DockerSecrets.readSecrets(enableLogging = false)
            val environment = System.getenv().toMutableMap<String, Any?>().apply {
                putAll(dockerSecrets)
            }

            val config = Yaml.default.decodeFromString<ConfigDefinition>(
                templater.template(
                    includeFile.readText(),
                    environment
                ).getOrThrow()
            )

            config.copy(
                copyPaths = config.copyPaths.map {
                    it.copy(
                        source = if (it.source.startsWith("/")) {
                            it.source
                        } else {
                            "$name/${it.source}"
                        }
                    )
                },
            )
        } catch (e: Exception) {
            t.println("${MSG.error} Failed to parse ${includeFile.fileName} in $directoryPath: ${e.message}")
            null
        }
    }

    object Serializer : InnerSerializer<Map<String, ConfigDefinition>, Inventory>(
        "Inventory",
        MapSerializer(String.serializer(), ConfigDefinition.serializer()),
        { Inventory(it) },
        { it.configs }
    )

    companion object {
        fun from(
            templater: Templater,
            inputStream: InputStream,
            environment: Map<String, String> = System.getenv().toMap(),
            enableDockerSecrets: Boolean = true,
            dockerSecretsPath: Path? = null,
        ): Inventory {
            t.println("${MSG.info} Parsing inventory file")

            val combinedEnvironment = if (enableDockerSecrets) {
                val dockerSecrets = DockerSecrets.readSecrets(
                    secretsPath = dockerSecretsPath ?: Path("/run/secrets"),
                    enableLogging = true
                )
                environment.toMutableMap<String, Any?>().apply {
                    putAll(dockerSecrets)
                }
            } else {
                environment
            }

            val templatedText = templater.template(
                inputStream.bufferedReader().readText(),
                combinedEnvironment
            ).getOrElse {
                t.println("${MSG.error} Failed to template inventory file!")
                throw it
            }
            return Yaml.default.decodeFromString<Inventory>(templatedText)
        }
    }
}

@Serializable
data class FileConfig(
    val deleteUntracked: Boolean = false,
    val keep: List<String> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = CopyPath.Serializer::class)
@KeepGeneratedSerializer
data class CopyPath(
    /** Offset from target path to copy to (i.e. target / dest / *files in source*). */
    @SerialName("to")
    val dest: String = "",
    @SerialName("from")
    val source: String,
) {
    object InlineSeriailzer : KSerializer<CopyPath> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CopyPath", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: CopyPath) = encoder.encodeString(value.source)
        override fun deserialize(decoder: Decoder): CopyPath = CopyPath(source = decoder.decodeString())
    }

    object Serializer : YamlContentPolymorphicSerializer<CopyPath>(CopyPath::class) {
        override fun selectDeserializer(node: YamlNode): DeserializationStrategy<CopyPath> {
            return if (node is YamlScalar) InlineSeriailzer
            else generatedSerializer()
        }
    }

    override fun toString() = "$source -> $dest"
}

@Serializable
data class ConfigDefinition(
    val copyPaths: List<CopyPath> = emptyList(),
    val files: Map<String, FileConfig> = mapOf(),
    val include: List<String> = listOf(),
    @Serializable(with = VariablesSerializer::class)
    val variables: Map<String, @Contextual Any?> = mapOf(),
    val plugins: List<DownloadSource> = listOf(),
    val excludePlugins: List<String> = listOf(), // TODO: this is not really implemented, and only works at the top level
) {
    companion object {
        fun reduce(configs: List<ConfigDefinition>) =
            configs.reduce { acc, config ->
                acc.copy(
                    copyPaths = acc.copyPaths + config.copyPaths,
                    files = acc.files + config.files,
                    variables = mergeVariables(acc.variables, config.variables),
                    plugins = acc.plugins + config.plugins,
                    excludePlugins = acc.excludePlugins + config.excludePlugins,
                )
            }

        fun mergeVariables(
            acc: Map<*, *>,
            vars: Map<*, *>,
        ): Map<String, Any?> {
            val merge = acc.toMutableMap()
            vars.forEach { (key, value) ->
                val existing = acc[key]
                merge[key] = when {
                    value is Map<*, *> && existing is Map<*, *> -> {
                        mergeVariables(existing, value)
                    }

                    value is List<*> && existing is List<*> -> {
                        (existing + value).distinct()
                    }

                    else -> value
                }
            }
            return merge as Map<String, Any?>
        }
    }
}
