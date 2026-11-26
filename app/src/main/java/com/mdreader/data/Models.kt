package com.mdreader.data

/** Whitespace runs, compiled once: [Chapter.title] is read for every row of a chapter list. */
private val whitespaceRun = Regex("\\s+")

/** A single chapter file belonging to a project. */
data class Chapter(
    val name: String,
    val uri: String,
    /** True for online chapters, where [uri] is a site path and [name] is the real title. */
    val remote: Boolean = false,
) {
    /** Filename without its extension, used as the chapter heading. */
    val title: String
        get() {
            if (remote) return name.trim().ifBlank { "Untitled" }
            val base = name.substringBeforeLast('.', name)
            val cleaned = base
                .replace('_', ' ')
                .replace(whitespaceRun, " ")
                .trim()
                .ifBlank { name }
            return if (cleaned == cleaned.lowercase()) {
                cleaned.split(' ').joinToString(" ") { word ->
                    word.replaceFirstChar { first ->
                        if (first.isLowerCase()) first.titlecase() else first.toString()
                    }
                }
            } else {
                cleaned
            }
        }

    val isMarkdown: Boolean
        get() = !remote && name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")
}

/** What a shelf entry holds: prose read as text, or pages read as a vertical strip. */
enum class ProjectKind { NOVEL, COMIC }

/** One title on the shelf: an imported folder of chapters, an online novel, or a comic. */
data class Project(
    val id: String,
    val name: String,
    val treeUri: String,
    val chapters: List<Chapter>,
    val importedAt: Long,
    /** Site path of an online novel, e.g. `/novel/some-title`; null for local folders. */
    val onlinePath: String? = null,
    /** Chapters the site reports for an online novel; [chapters] may hold only the first pages. */
    val totalChapters: Int? = null,
    /** Chapter list shown newest first; remembered per novel. */
    val newestFirst: Boolean = false,
    /** Cover image URL of an online novel, if the site has one. */
    val coverUrl: String? = null,
    /** When new chapters last appeared (online) or the shelf entry last changed; 0 if never. */
    val updatedAt: Long = 0,
    /** Chapters found by checks since the novel was last opened; shown as "+N" on the shelf. */
    val newChapters: Int = 0,
    /** The last summary read from the site, so the detail page opens instantly and offline. */
    val author: String = "",
    val authorPath: String = "",
    val status: String = "",
    val synopsis: String = "",
    val genres: List<String> = emptyList(),
    val rating: String = "",
    val votes: Int = 0,
    /** When [author] and the rest were last read from the site; 0 when never. */
    val summaryAt: Long = 0,
    /** Novel text or comic pages; defaults to NOVEL so older saved libraries keep working. */
    val kind: ProjectKind = ProjectKind.NOVEL,
    /** Comic source id ("mangakakalot", "batcave"); null for novels. */
    val sourceId: String? = null,
) {
    val isOnline: Boolean get() = onlinePath != null
    val isComic: Boolean get() = kind == ProjectKind.COMIC
}

/** Six hours: old enough to fetch again in the background, new enough to open without a request. */
const val SUMMARY_TTL_MS = 6 * 60 * 60 * 1000L

/** Whether the stored summary is missing or old enough to refresh on the next open. */
fun Project.summaryIsStale(now: Long, ttl: Long = SUMMARY_TTL_MS): Boolean =
    summaryAt == 0L || now - summaryAt > ttl || author.isEmpty()

/** Where the reader left off, both by chapter and by scroll position inside it. */
data class Progress(
    val chapterIndex: Int,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    /** Identity of the chapter ([Chapter.uri]); lets progress survive list refreshes. */
    val chapterKey: String? = null,
    /** When this position was saved; the most recent one is the novel to continue. */
    val updatedAt: Long = 0,
)

/**
 * Finds the saved chapter in [chapters] by identity, falling back to its old
 * index. If the chapter vanished, the scroll offset no longer means anything.
 */
fun Progress.remapTo(chapters: List<Chapter>): Progress {
    val key = chapterKey
    val last = chapters.lastIndex.coerceAtLeast(0)
    if (key == null) return copy(chapterIndex = chapterIndex.coerceIn(0, last))

    val found = chapters.indexOfFirst { it.uri == key }
    return when {
        found >= 0 -> copy(chapterIndex = found)
        else -> Progress(
            chapterIndex = chapterIndex.coerceIn(0, last),
            chapterKey = chapters.getOrNull(chapterIndex.coerceIn(0, last))?.uri,
        )
    }
}

/**
 * One remembered reading session — shelf or not. Reading anything from search
 * lands here, so reopening needs only the identity: source, path, title, cover.
 */
data class ReadingHistory(
    val id: String,
    val kind: ProjectKind,
    /** The comic source, or null for a novel. */
    val sourceId: String? = null,
    /** The series path on its source, for reopening from search. */
    val onlinePath: String = "",
    val title: String,
    val coverUrl: String? = null,
    val totalChapters: Int = 0,
    val chapterIndex: Int = 0,
    val chapterKey: String? = null,
    val scrollIndex: Int = 0,
    val updatedAt: Long = 0,
)
