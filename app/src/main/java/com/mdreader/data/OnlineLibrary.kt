package com.mdreader.data

import com.mdreader.data.online.ChapterRef
import com.mdreader.data.online.FreeWebNovelClient

fun List<ChapterRef>.toChapters(): List<Chapter> =
    map { Chapter(name = it.title, uri = it.path, remote = true) }

/** Re-reads the whole chapter list, keeping reading progress attached to the same chapter. */
suspend fun refreshOnlineProject(project: Project, store: LibraryStore): Result<Project> = runCatching {
    val path = requireNotNull(project.onlinePath) { "Not an online novel." }
    val novel = FreeWebNovelClient.loadDetails(path)
    val chapters = novel.chapters.toChapters()
    val updated = project.copy(name = novel.details.title, chapters = chapters, totalChapters = chapters.size,
        coverUrl = novel.details.cover.ifEmpty { null } ?: project.coverUrl)
    store.addOrReplace(updated)
    store.progress(project.id)?.let { store.setProgress(project.id, it.remapTo(chapters)) }
    updated
}
