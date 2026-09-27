package tachiyomi.core.common.storage

import com.hippo.unifile.UniFile
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

val UniFile.extension: String?
    get() = name?.substringAfterLast('.')

val UniFile.nameWithoutExtension: String?
    get() = name?.substringBeforeLast('.')

val UniFile.displayablePath: String
    get() = filePath ?: uri.toString()

/**
 * Renames this file or directory to [displayName], falling back to a copy + delete when the
 * underlying storage provider doesn't support renaming.
 *
 * Some storage providers (notably `DocumentsProvider` implementations in Android compatibility
 * layers) reject [UniFile.renameTo] outright. Renaming is preferred since it's atomic and cheap;
 * copying is only used as a last resort.
 *
 * @param displayName the new name, without any path separators.
 * @return the renamed/copied entry, or null if the operation failed entirely.
 */
fun UniFile.renameOrCopyTo(displayName: String): UniFile? {
    if (renameTo(displayName)) {
        return this
    }

    logcat(LogPriority.WARN) { "Failed to rename ${displayablePath} to $displayName, falling back to copy" }

    val parent = parentFile
    if (parent == null) {
        logcat(LogPriority.ERROR) { "Can't copy ${displayablePath}: no known parent directory" }
        return null
    }

    return if (isDirectory) {
        copyDirectoryInto(parent, displayName)
    } else {
        copyFileInto(parent, displayName)
    }
}

/**
 * Copies this file into [parent] under [displayName] and deletes the original.
 */
private fun UniFile.copyFileInto(parent: UniFile, displayName: String): UniFile? {
    // A previous failed attempt may have left a partial copy behind.
    parent.findFile(displayName)?.delete()

    val dest = parent.createFile(displayName)
    if (dest == null) {
        logcat(LogPriority.ERROR) { "Can't create $displayName in ${parent.displayablePath}" }
        return null
    }

    return try {
        openInputStream().use { input ->
            dest.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        if (!delete()) {
            logcat(LogPriority.WARN) { "Failed to delete ${displayablePath} after copying" }
        }
        dest
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to copy ${displayablePath} to $displayName" }
        dest.delete()
        null
    }
}

/**
 * Recursively copies this directory into [parent] under [displayName] and deletes the original.
 */
private fun UniFile.copyDirectoryInto(parent: UniFile, displayName: String): UniFile? {
    // A previous failed attempt may have left a partial copy behind.
    parent.findFile(displayName)?.delete()

    val dest = parent.createDirectory(displayName)
    if (dest == null) {
        logcat(LogPriority.ERROR) { "Can't create $displayName in ${parent.displayablePath}" }
        return null
    }

    for (child in listFiles().orEmpty()) {
        val childName = child.name ?: continue
        val copied = if (child.isDirectory) {
            child.copyDirectoryInto(dest, childName)
        } else {
            child.copyFileInto(dest, childName)
        }
        if (copied == null) {
            logcat(LogPriority.ERROR) { "Aborting directory copy of ${displayablePath}: failed on $childName" }
            dest.delete()
            return null
        }
    }

    if (!delete()) {
        logcat(LogPriority.WARN) { "Failed to delete ${displayablePath} after copying" }
    }
    return dest
}
