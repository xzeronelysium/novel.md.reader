package com.mdreader.data

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Android SAF import: persist permission, then delegate to shared [importFolder].
 */
fun importFolder(context: Context, treeUri: Uri, store: LibraryStore): Result<Project> {
    try {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    } catch (_: SecurityException) {
        if (!ChapterSource.hasPermission(context, treeUri)) {
            return Result.failure(
                ImportException("This app couldn't keep access to that folder. Try picking it again."),
            )
        }
    }

    val folderRef = treeUri.toString()
    val access = AndroidNovelFolderAccess(context)
    return importFolder(folderRef, access, store)
}
