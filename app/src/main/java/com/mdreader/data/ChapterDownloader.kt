package com.mdreader.data

import com.mdreader.data.online.FreeWebNovelClient
import com.mdreader.md.Block
import kotlinx.coroutines.CancellationException
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Which chapters a download should fetch. */
sealed interface DownloadRequest {
    /** The next [count] chapters not yet saved, from where the reader would open next. */
    data class Next(val count: Int) : DownloadRequest

    /** Every unsaved chapter from the current one to the end. */
    data object Unread : DownloadRequest

    /** Every unsaved chapter in the novel. */
    data object All : DownloadRequest

    /** Every chapter already saved, fetched again so an updated site text replaces the old copy. */
    data object Refresh : DownloadRequest
}

/** What the page shows while chapters are being saved. */
data class DownloadProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    /** Short outcome of the last run ("Paused: …", "12 saved"), shown until the next one starts. */
    val message: String? = null,
)

/**
 * The chapter positions [request] asks for, in reading order. Chapters already saved are never
 * planned again, so running the same download twice only fetches what is still missing.
 *
 * Everything but [DownloadRequest.All] starts at the chapter the reader would open next.
 */
fun planDownload(
    chapters: List<Chapter>,
    downloaded: Set<String>,
    resumeIndex: Int?,
    request: DownloadRequest,
): List<Int> {
    if (request == DownloadRequest.Refresh) {
        return chapters.indices.filter { chapters[it].uri in downloaded }
    }
    val start = if (request == DownloadRequest.All) 0 else (resumeIndex ?: 0).coerceIn(0, chapters.size)
    val missing = (start until chapters.size).filter { chapters[it].uri !in downloaded }
    return when (request) {
        is DownloadRequest.Next -> missing.take(request.count.coerceAtLeast(0))
        DownloadRequest.Unread, DownloadRequest.All -> missing
        DownloadRequest.Refresh -> emptyList() // handled above
    }
}

/**
 * A chapter count typed into the stepper: digits only, at least 1 and at most what is left to
 * save. Nothing typed (or no digits) means "leave it as it was", so this returns null.
 */
fun parseChapterCount(text: String, remaining: Int): Int? {
    val typed = text.filter { it.isDigit() }.take(9).toIntOrNull() ?: return null
    return typed.coerceIn(1, maxOf(remaining, 1))
}

/** What deleting could remove: chapters already read, or everything saved. */
data class DeletePlan(val read: List<String>, val all: List<String>)

/** Saved chapters before the current one count as read; those from it onward are still to read. */
fun planDelete(chapters: List<Chapter>, downloaded: Set<String>, resumeIndex: Int?): DeletePlan {
    val saved = chapters.withIndex().filter { it.value.uri in downloaded }
    val readUpTo = resumeIndex ?: 0
    return DeletePlan(
        read = saved.filter { it.index < readUpTo }.map { it.value.uri },
        all = saved.map { it.value.uri },
    )
}

/** About what one chapter takes and costs, from the real chapters measured. */
private const val ESTIMATED_CHAPTER_BYTES = 10_000L
private const val ESTIMATED_SECONDS_PER_CHAPTER = 1.7

fun estimatedBytes(chapters: Int): Long = chapters.coerceAtLeast(0) * ESTIMATED_CHAPTER_BYTES

/** A size for people: 250 KB, 3.3 MB, 27 MB, 1.2 GB. */
fun formatBytes(bytes: Long): String {
    val kb = bytes / 1_000.0
    val mb = kb / 1_000.0
    return when {
        bytes < 1_000 -> "$bytes B"
        mb < 1.0 -> "${kb.roundToInt()} KB"
        mb < 10.0 -> String.format(Locale.ROOT, "%.1f MB", mb)
        mb < 1_000.0 -> "${mb.roundToInt()} MB"
        else -> String.format(Locale.ROOT, "%.1f GB", mb / 1_000.0)
    }
}

/** A time for people: 40 s, 1.5 min, 12 min, 1.3 h. */
fun estimatedDuration(chapters: Int): String {
    val seconds = chapters.coerceAtLeast(0) * ESTIMATED_SECONDS_PER_CHAPTER
    return when {
        seconds < 60 -> "${seconds.roundToInt()} s"
        seconds < 600 -> String.format(Locale.ROOT, "%.1f min", seconds / 60)
        seconds < 3_600 -> "${(seconds / 60).roundToInt()} min"
        else -> String.format(Locale.ROOT, "%.1f h", seconds / 3_600)
    }
}

/**
 * Saves [positions] of [project] into the [vault], one chapter at a time with a pause between
 * requests. One failed chapter is counted and skipped; [maxConsecutiveFailures] in a row means
 * the site has stopped answering, so the run stops cleanly instead of hammering it. Whatever
 * was saved stays saved: the vault itself is the checkpoint, and starting again continues.
 */
suspend fun downloadChapters(
    project: Project,
    positions: List<Int>,
    vault: Vault,
    author: String?,
    today: String,
    cooldownMs: Long = 1_000,
    maxConsecutiveFailures: Int = 3,
    fetch: suspend (String) -> List<Block> = FreeWebNovelClient::loadChapter,
    /** Fetch saved chapters again instead of skipping them, replacing their files. */
    refresh: Boolean = false,
    onProgress: (DownloadProgress) -> Unit,
): DownloadProgress {
    var progress = DownloadProgress(running = true, total = positions.size)
    onProgress(progress)
    if (positions.isEmpty()) return progress.copy(running = false)

    vault.writeInfo(project, author, today)

    var consecutive = 0
    var lastError: String? = null
    for (position in positions) {
        coroutineContext.ensureActive()
        val chapter = project.chapters.getOrNull(position)
        if (chapter == null || (!refresh && vault.has(project, chapter))) {
            progress = progress.copy(done = progress.done + 1)
            onProgress(progress)
            continue
        }
        try {
            val blocks = fetch(chapter.uri)
            vault.write(project, chapter, position, vault.render(chapter, blocks))
            consecutive = 0
            progress = progress.copy(done = progress.done + 1)
            onProgress(progress)
            if (cooldownMs > 0) delay(cooldownMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            consecutive++
            lastError = e.message
            progress = progress.copy(done = progress.done + 1, failed = progress.failed + 1)
            onProgress(progress)
            if (consecutive >= maxConsecutiveFailures) {
                val saved = progress.done - progress.failed
                return progress.copy(
                    running = false,
                    message = "Paused after $saved saved: the site stopped answering. Tap Download to resume." +
                        (lastError?.let { " ($it)" } ?: ""),
                )
            }
            if (cooldownMs > 0) delay(cooldownMs)
        }
    }

    val saved = progress.done - progress.failed
    return progress.copy(
        running = false,
        message = when {
            progress.failed == 0 -> if (saved == 1) "1 chapter saved" else "$saved chapters saved"
            else -> "$saved saved, ${progress.failed} failed. Tap Download to retry those."
        },
    )
}
