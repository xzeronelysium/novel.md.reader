package com.mdreader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.UUID

class ImportException(message: String) : Exception(message)

/**
 * Turns a folder picked through SAF into a project. Re-importing the same
 * folder refreshes its chapter list while keeping saved reading progress.
 */
fun importFolder(context: Context, treeUri: Uri, store: LibraryStore): Result<Project> = runCatching {
    try {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    } catch (_: SecurityException) {
        if (!ChapterSource.hasPermission(context, treeUri)) {
            throw ImportException("This app couldn't keep access to that folder. Try picking it again.")
        }
    }

    if (!ChapterSource.hasPermission(context, treeUri)) {
        throw ImportException("Access to that folder wasn't granted.")
    }

    val chapters = ChapterSource.listChapters(context, treeUri)
    if (chapters.isEmpty()) {
        throw ImportException("No .md or .txt files found in that folder.")
    }

    val name = ChapterSource.displayName(context, treeUri)
    val existing = store.load().firstOrNull { it.treeUri == treeUri.toString() }

    val project = Project(
        id = existing?.id ?: UUID.randomUUID().toString(),
        name = name,
        treeUri = treeUri.toString(),
        chapters = chapters,
        importedAt = System.currentTimeMillis(),
    )
    store.addOrReplace(project)
    project
}
