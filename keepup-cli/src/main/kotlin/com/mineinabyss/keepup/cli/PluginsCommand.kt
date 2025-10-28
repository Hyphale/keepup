package com.mineinabyss.keepup.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.inputStream
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.animation.progress.advance
import com.github.ajalt.mordant.animation.progress.animateOnThread
import com.github.ajalt.mordant.animation.progress.execute
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.widgets.progress.*
import com.mineinabyss.keepup.api.Keepup
import com.mineinabyss.keepup.api.KeepupDownloaderConfig
import com.mineinabyss.keepup.api.KeepupVersionCatalog
import com.mineinabyss.keepup.config_sync.ConfigDefinition
import com.mineinabyss.keepup.config_sync.Inventory
import com.mineinabyss.keepup.config_sync.templating.Templater
import com.mineinabyss.keepup.downloads.DownloadResult
import com.mineinabyss.keepup.downloads.github.GithubConfig
import com.mineinabyss.keepup.downloads.github.GithubReleaseOverride
import com.mineinabyss.keepup.downloads.gitlab.GitlabConfig
import com.mineinabyss.keepup.downloads.keeper.KeeperConfig
import com.mineinabyss.keepup.downloads.nexus.NexusConfig
import com.mineinabyss.keepup.helpers.MSG
import com.mineinabyss.keepup.helpers.clearSymlinks
import com.mineinabyss.keepup.helpers.linkToDest
import com.mineinabyss.keepup.helpers.printToConsole
import com.mineinabyss.keepup.t
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

class PluginsCommand : CliktCommand(name = "plugins") {
    override fun help(context: Context): String = "Syncs plugins from a hocon/json config"

    init {
        context {
            autoEnvvarPrefix = "KEEPUP"
        }
    }

    val include by argument("include", help = "The config defined in inventory to sync")

    val downloadPath by argument(help = "Path to download files to")
        .path(mustExist = true, canBeFile = false, mustBeWritable = true)

    val dest by argument()
        .path(mustExist = true, canBeFile = false, mustBeWritable = true)

    // === Arguments ===
    val catalog by option(
        "--catalog",
        help = "Path to the version catalog file",
    ).path().required()

    val inventoryFiles by option("--inventory", help = "Path to the inventory file(s) (can be chained)")
        .path(mustExist = false, canBeDir = false)
        .multiple(required = true)

    val sourceRoot by option(
        "-s",
        "--source",
        help = "Directory containing source configs to sync, defaults to directory of inventory"
    )
        .path(mustExist = true, canBeFile = false, mustBeReadable = true)
        .defaultLazy { inventoryFiles[0].parent }


    // === Options ===

    val ignoreSimilar by option(help = "Don't create symlinks for files with matching characters before the first number")
        .flag(default = true)

    val failAllDownloads by option(help = "Don't actually download anything, useful for testing")
        .flag(default = false)

    val hideProgressBar by option(help = "Does not show progress bar if set to true")
        .flag(default = false)

    val overrideGithubRelease by option(help = "Force downloading the latest version of files from GitHub")
        .enum<GithubReleaseOverride>()
        .default(GithubReleaseOverride.NONE)

    val cacheExpirationTime: Duration by option()
        .convert { Duration.parse(it) }
        .default(10.minutes)

    val githubAuthToken: String? by option(help = "Used to access private repos or get a higher rate limit on github repositories")

    val gitlabAccessToken: String? by option(help = "Used to access private repos or get a higher rate limit on gitlab repositories")

    val githubConfig by lazy {
        GithubConfig(
            githubAuthToken = githubAuthToken,
            overrideGithubRelease = overrideGithubRelease,
            cacheExpirationTime = cacheExpirationTime,
        )
    }

    val gitlabConfig by lazy {
        GitlabConfig(
            gitlabAccessToken = gitlabAccessToken,
            cacheExpirationTime = cacheExpirationTime,
        )
    }

    val keeperBaseUrl: String? by option(help = "Base URL for Keeper artifact repository")
    val keeperAuthToken: String? by option(help = "Bearer token for authentication with Keeper repository")

    val keeperConfig by lazy {
        KeeperConfig(
            baseUrl = keeperBaseUrl,
            authToken = keeperAuthToken,
            cacheExpirationTime = cacheExpirationTime,
        )
    }

    val nexusBaseUrl by option(help = "Base URL for Nexus repository")
        .default("https://repo.maven.apache.org/maven2")
    val nexusUsername by option(help = "Username for Nexus repository")
    val nexusPassword by option(help = "Password for Nexus repository")
    val nexusDefaultExtension by option(help = "Default extension for Nexus repository")
        .default("jar")
    val nexusDefaultClassifier by option(help = "Default classifier for Nexus repository")
        .default("all")

    val nexusConfig by lazy {
        NexusConfig(
            baseUrl = nexusBaseUrl,
            username = nexusUsername,
            password = nexusPassword,
            defaultExtension = nexusDefaultExtension,
            defaultClassifier = nexusDefaultClassifier,
        )
    }

    override fun run() {
        val keepup = Keepup()
        val downloader = keepup.downloader(
            config = KeepupDownloaderConfig(
                downloadCache = downloadPath,
                ignoreSimilar = ignoreSimilar,
                failAllDownloads = failAllDownloads,
            ),
            githubConfig = githubConfig,
            gitlabConfig = gitlabConfig,
            keeperConfig = keeperConfig,
            nexusConfig = nexusConfig,
        )

        keepup.catalogParser().parse(catalog.inputStream()).also {
            t.println("${MSG.info} Added ${KeepupVersionCatalog.size()} download sources")
        }

        val inventory = Inventory.from(
            templater = Templater(),
            inputStream = SequenceInputStream(Collections.enumeration(inventoryFiles.filter { it.exists() }.map { it.inputStream() })),
            enableDockerSecrets = false,
        )

        val included = inventory.getOrCreateConfigs(include, sourceRoot)
        val reduced = ConfigDefinition.reduce(included)
        val sources = reduced.plugins.toMutableList()
        sources.removeIf { reduced.excludePlugins.contains(it.keyInConfig)}

        t.println("${MSG.info} Clearing symlinks")
        clearSymlinks(dest)

        t.println(
            "${MSG.info} Running Keepup on ${TextColors.yellow(sources.size.toString())} items" + if (include != "$") " from path ${
                TextColors.yellow(include)
            }" else ""
        )

        progressBarLayout {
            progressBar()
        }
        val progress = if (hideProgressBar) null else progressBarLayout {
            text("Keepup!")
            percentage()
            progressBar()
            completed()
            timeRemaining()
        }.animateOnThread(t)
        progress?.update { total = (sources.size.toLong()) }
        progress?.execute()

        if (githubConfig.overrideGithubRelease != GithubReleaseOverride.NONE)
            t.println("${TextColors.yellow("[!]")} Overriding GitHub release versions to ${githubConfig.overrideGithubRelease}")

        val startTime = TimeSource.Monotonic.markNow()

        runBlocking {
            val downloadResults = downloader.download(sources = sources.toTypedArray(), dest = dest, this)
            var hasFailures = false
            var downloadsAttempted = 0

            for (result in downloadResults) {
                if (result is DownloadResult.HasFiles) {
                    linkToDest(dest, result)
                    downloadsAttempted++
                } else if (result is DownloadResult.Failure) {
                    hasFailures = true
                    downloadsAttempted++
                }

                progress?.advance(1)
                result.printToConsole()
            }

            progress?.clear()
            progress?.stop()

            // Check if there were any failures
            if (hasFailures) {
                throw Abort()
            }

            // Check if downloads were attempted but no artifacts were found
            if (downloadsAttempted == 0 && sources.isNotEmpty()) {
                t.println("${MSG.error} No artifacts were downloaded for any of the requested plugins")
                throw Abort()
            }

            val elapsed = startTime.elapsedNow().toString(unit = DurationUnit.SECONDS, decimals = 2)
            t.println("${MSG.info} ${TextColors.brightGreen("done in $elapsed!")}")
        }
    }
}
