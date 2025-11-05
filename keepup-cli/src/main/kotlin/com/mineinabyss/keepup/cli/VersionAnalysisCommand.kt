package com.mineinabyss.keepup.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextColors
import com.mineinabyss.keepup.api.Keepup
import com.mineinabyss.keepup.api.KeepupVersionCatalog
import com.mineinabyss.keepup.config_sync.ConfigDefinition
import com.mineinabyss.keepup.config_sync.Inventory
import com.mineinabyss.keepup.config_sync.templating.Templater
import com.mineinabyss.keepup.downloads.keeper.KeeperConfig
import com.mineinabyss.keepup.downloads.parsing.DownloadSource
import com.mineinabyss.keepup.helpers.MSG
import com.mineinabyss.keepup.t
import com.mineinabyss.keepup.version_analysis.KeeperVersionResolver
import com.mineinabyss.keepup.version_analysis.VersionAnalyzer
import kotlinx.coroutines.runBlocking
import java.io.SequenceInputStream
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import java.nio.file.Files

class VersionAnalysisCommand : CliktCommand(name = "versions") {
    override fun help(context: Context): String = "Analyzes plugin catalog and checks for version updates"

    init {
        context {
            autoEnvvarPrefix = "KEEPUP"
        }
    }

    val catalog by option(
        "--catalog",
        help = "Path to the version catalog file",
    ).path().required()

    val inventoryFiles by option("--inventory", help = "Path to the inventory file(s) to check for updates (can be chained)")
        .path(mustExist = false, canBeDir = false)
        .multiple(required = true)

    val sourceRoot by option(
        "-s",
        "--source",
        help = "Directory containing source configs, defaults to directory of inventory"
    )
        .path(mustExist = true, canBeFile = false, mustBeReadable = true)
        .defaultLazy { inventoryFiles[0].toFile().parentFile!!.toPath() }

    val jsonPath by option(
        "--json-path",
        help = "Path to the root object to check for updates from, uses keys separated by ."
    ).default("$")

    val keeperBaseUrl: String? by option(help = "Base URL for Keeper artifact repository")
    val keeperAuthToken: String? by option(help = "Bearer token for authentication with Keeper repository")

    val keeperConfig by lazy {
        KeeperConfig(
            baseUrl = keeperBaseUrl,
            authToken = keeperAuthToken,
            cacheExpirationTime = null,
        )
    }

    override fun run() {
        val keepup = Keepup()
        val templater = Templater()

        // Parse the version catalog
        keepup.catalogParser().parse(templater, catalog.inputStream()).also {
            t.println("${MSG.info} Loaded ${KeepupVersionCatalog.size()} download sources from catalog")
        }

        // Determine source root
        val actualSourceRoot = sourceRoot

        // Load current inventory
        val currentInventory = Inventory.from(
            templater = templater,
            inputStream = SequenceInputStream(
                Collections.enumeration(
                    inventoryFiles.filter { it.exists() }.map { it.inputStream() }
                )
            ),
            enableDockerSecrets = false,
        )

        // Get the configurations
        val currentConfigs = currentInventory.getOrCreateConfigs(jsonPath, actualSourceRoot)
        val currentReduced = ConfigDefinition.reduce(currentConfigs)

        // Extract current sources
        val currentSources = currentReduced.plugins
            .associateBy { it.keyInConfig }
            .mapValues { it.value }

        // Get catalog sources
        val catalogSources = mutableMapOf<String, DownloadSource>()
        currentSources.keys.forEach { projectKey ->
            val source = KeepupVersionCatalog[projectKey]
            if (source != null) {
                catalogSources[projectKey] = source
            }
        }

        // Analyze versions
        val analyzer = VersionAnalyzer(
            versionResolvers = listOf(
                KeeperVersionResolver(keepup.http, keeperConfig)
            )
        )
        val result = runBlocking {
            analyzer.analyzeVersions(currentSources, catalogSources)
        }

        // Print results
        if (result.updates.isEmpty()) {
            t.println("${MSG.info} ${TextColors.green("All plugins are up to date!")}")
        } else {
            t.println("${MSG.info} ${TextColors.yellow(result.updates.size.toString())} plugin(s) have newer versions available:")
            t.println()

            result.updates.forEach { (project, update) ->
                t.println(
                    "  ${TextColors.cyan(update.project)}: " +
                    "${TextColors.red(update.oldVersion)} → ${TextColors.green(update.newVersion)}"
                )
            }
        }

        // Print formatted map output
        t.println()
        t.println("${MSG.info} Version updates map:")
        t.println(buildVersionUpdateMap(result.updates))
    }

    private fun buildVersionUpdateMap(updates: Map<String, com.mineinabyss.keepup.version_analysis.VersionUpdate>): String {
        if (updates.isEmpty()) return "{}"

        return updates.entries.joinToString(separator = "\n") { (project, update) ->
            "  $project = {oldVersion: \"${update.oldVersion}\", newVersion: \"${update.newVersion}\"}"
        }.let { "{\n$it\n}" }
    }
}
