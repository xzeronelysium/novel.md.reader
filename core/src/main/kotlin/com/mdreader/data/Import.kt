package com.mdreader.data

import java.util.UUID

class ImportException(message: String) : Exception(message)

/**
 * Turns an on-disk or SAF folder into a project. Re-importing the same folder
 * refreshes its chapter list while keeping saved reading progress.
 */
fun importFolder(
    folderRef: String,
    access: NovelFolderAccess,
    store: LibraryStore,
): Result<Project> = runCatching {
    if (!access.canAccessFolder(folderRef)) {
        throw ImportException("Access to that folder wasn't granted or the path no longer exists.")
    }

    val chapters = access.listChapters(folderRef)
    if (chapters.isEmpty()) {
        throw ImportException("No .md or .txt files found in that folder.")
    }

    val name = access.displayName(folderRef)
    val existing = store.load().firstOrNull { it.treeUri == folderRef }

    val project = Project(
        id = existing?.id ?: UUID.randomUUID().toString(),
        name = name,
        treeUri = folderRef,
        chapters = chapters,
        importedAt = System.currentTimeMillis(),
    )
    store.addOrReplace(project)
    project
}

fun refreshProject(
    project: Project,
    access: NovelFolderAccess,
    store: LibraryStore,
): Result<Project> = runCatching {
    if (!access.canAccessFolder(project.treeUri)) {
        throw ImportException("The folder is no longer accessible. Import it again.")
    }

    val chapters = access.listChapters(project.treeUri)
    if (chapters.isEmpty()) {
        throw ImportException("No .md or .txt files are left in that folder.")
    }

    val updated = project.copy(chapters = chapters)
    store.addOrReplace(updated)
    updated
}
