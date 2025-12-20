package com.mineinabyss.keepup.helpers

import com.mineinabyss.keepup.downloads.DownloadResult
import java.nio.file.Path
import kotlin.io.path.*

/** Creates a symlink for a downloaded [item] to a [dest] folder */
fun linkToDest(dest: Path, source: DownloadResult.HasFiles) {
    val file = source.file
    (dest / file.name).createSymbolicLinkPointingTo((file).relativeTo(dest.absolute()))
}

/** Removes any symlinks contained in [path] */
fun clearSymlinks(path: Path) {
    // For each file in the directory
    path.listDirectoryEntries().forEach {
        if (!it.isDirectory() && it.isSymbolicLink()) it.deleteIfExists()
    }
}

/** Creates parent directories for [this] path, deleting any existing files that conflict with the directory structure. */
fun Path.createParentDirectoriesSafely(): Path {
    val parent = this.parent ?: return this
    var current = parent.root ?: Path("")
    parent.forEach { part ->
        current = current.resolve(part)
        if (current.exists() && current.isRegularFile()) {
            current.deleteIfExists()
        }
    }
    parent.createDirectories()
    return this
}
