package com.mineinabyss.keepup.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.core.Abort
import com.mineinabyss.keepup.api.Keepup
import com.mineinabyss.keepup.api.KeepupVersionCatalog
import com.mineinabyss.keepup.config_sync.Inventory
import com.mineinabyss.keepup.config_sync.templating.Templater
import com.mineinabyss.keepup.helpers.MSG
import com.mineinabyss.keepup.t
import java.io.SequenceInputStream
import java.nio.file.LinkOption
import java.util.Collections
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class ConfigCommand : CliktCommand(name = "config") {
    override fun help(context: Context) = "Syncs local config files to appropriate destinations"

    val include by argument(
        "include",
        help = "The config defined in inventory to sync"
    )

    val inventoryFiles by option("--inventory", help = "Path to the inventory file(s) (can be chained)")
        .path(mustExist = false, canBeDir = false)
        .multiple(required = true)

    val catalogFile by option("--catalog", help = "Path to the version catalog file")
        .path(mustExist = true, canBeDir = false, mustBeReadable = true)
        .required()

    val sourceRoot by option(
        "-s",
        "--source",
        help = "Directory containing source configs to sync, defaults to directory of inventory"
    )
        .path(mustExist = true, canBeFile = false, mustBeReadable = true)
        .defaultLazy { inventoryFiles[0].parent }

    val destRoot by option("-d", "--dest", help = "Directory to sync configs to")
        .path(mustExist = true, canBeFile = false, mustBeWritable = true)
        .required()

    val templateCacheDir by option(
        "-t", "--template-cache",
        help = "Directory to cache template results, if unspecified will not templates .peb files"
    )
        .path(mustExist = false, canBeFile = false)

    val disableDockerSecrets by option(
        "--disable-docker-secrets",
        help = "Disable reading Docker Swarm secrets from the filesystem"
    )
        .flag(default = false)

    val dockerSecretsPath by option(
        "--docker-secrets-path",
        help = "Custom path to Docker secrets directory (default: /run/secrets)"
    )
        .path(mustExist = false, canBeFile = false, mustBeReadable = true)

    override fun run() {
        t.println("${MSG.info} Running config sync for $include...")
        val keepup = Keepup()
        val templater = Templater()

        keepup.catalogParser().parse(catalogFile.inputStream()).also {
            t.println("${MSG.info} Added ${KeepupVersionCatalog.size()} download sources")
        }

        val success = keepup.configSync(
            inventory = Inventory.from(
                templater = templater,
                inputStream = SequenceInputStream(Collections.enumeration(inventoryFiles.filter { it.exists() }.map { it.inputStream() })),
                enableDockerSecrets = !disableDockerSecrets,
                dockerSecretsPath = dockerSecretsPath
            )
        ).sync(
            host = include,
            configsRoot = sourceRoot,
            templateCacheDir = templateCacheDir,
            destRoot = destRoot
        )

        if (!success) {
            throw Abort()
        }
    }
}
