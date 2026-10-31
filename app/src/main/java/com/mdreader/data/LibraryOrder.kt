package com.mdreader.data

import java.util.Locale

/** How the shelf is ordered; newest update first unless the reader picks another. */
enum class LibrarySort { Updated, Read, Title }

private fun Project.lastUpdate() = maxOf(updatedAt, importedAt)

/** The shelf in [sort] order. [lastRead] gives when a novel was last read, 0 if never. */
fun List<Project>.ordered(sort: LibrarySort, lastRead: (Project) -> Long): List<Project> = when (sort) {
    LibrarySort.Updated -> sortedByDescending { it.lastUpdate() }
    LibrarySort.Read -> sortedWith(
        compareByDescending<Project> { lastRead(it) }.thenByDescending { it.lastUpdate() },
    )
    LibrarySort.Title -> sortedBy { it.name.lowercase(Locale.ROOT) }
}

/** Novels whose name contains [query], ignoring case; a blank query keeps everything. */
fun List<Project>.matching(query: String): List<Project> {
    val q = query.trim()
    return if (q.isEmpty()) this else filter { it.name.contains(q, ignoreCase = true) }
}

/** Short age for the shelf: NOW, 5H, 3D, 2W, 4M. */
fun ageLabel(now: Long, then: Long): String {
    val minutes = ((now - then) / 60_000L).coerceAtLeast(0)
    val hours = minutes / 60
    val days = hours / 24
    return when {
        hours < 1 -> "NOW"
        days < 1 -> "${hours}H"
        days < 7 -> "${days}D"
        days < 30 -> "${days / 7}W"
        else -> "${days / 30}M"
    }
}

/** The shelf entry with a freshly read chapter list; growth is recorded for ordering and the "+N". */
fun Project.withChapters(
    title: String,
    chapters: List<Chapter>,
    cover: String,
    now: Long = System.currentTimeMillis(),
): Project {
    val added = (chapters.size - this.chapters.size).coerceAtLeast(0)
    return copy(
        name = title,
        chapters = chapters,
        totalChapters = chapters.size,
        coverUrl = cover.ifEmpty { null } ?: coverUrl,
        updatedAt = if (added > 0) now else updatedAt,
        newChapters = if (added > 0) newChapters + added else newChapters,
    )
}
