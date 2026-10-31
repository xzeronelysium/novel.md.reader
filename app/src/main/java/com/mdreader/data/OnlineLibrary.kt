package com.mdreader.data

import com.mdreader.data.online.ChapterRef
import com.mdreader.data.online.FreeWebNovelClient

fun List<ChapterRef>.toChapters(): List<Chapter> =
    map { Chapter(name = it.title, uri = it.path, remote = true) }

/**
 * A cheap "anything new?": one request for the first list page, which carries the site's chapter
 * count. Only when that exceeds what is stored does the whole list get re-read.
 */
suspend fun checkOnlineProject(project: Project, store: LibraryStore): Result<Project> {
    val path = project.onlinePath ?: return Result.success(project)
    val total = try {
        FreeWebNovelClient.loadChapterPage(path, 1).totalChapters
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        return Result.failure(e)
    }
    if (total <= project.chapters.size) return Result.success(project)
    return refreshOnlineProject(project, store)
}

/** Re-reads the whole chapter list, keeping reading progress attached to the same chapter. */
suspend fun refreshOnlineProject(project: Project, store: LibraryStore): Result<Project> = runCatching {
    val path = requireNotNull(project.onlinePath) { "Not an online novel." }
    val novel = FreeWebNovelClient.loadDetails(path)
    val chapters = novel.chapters.toChapters()
    val updated = project.withChapters(novel.details.title, chapters, novel.details.cover)
    store.addOrReplace(updated)
    store.progress(project.id)?.let { store.setProgress(project.id, it.remapTo(chapters)) }
    updated
}
