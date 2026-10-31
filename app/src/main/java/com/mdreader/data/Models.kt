package com.mdreader.data

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
                .replace(Regex("\\s+"), " ")
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

/** One novel on the shelf: an imported folder of chapters, or an online novel. */
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
) {
    val isOnline: Boolean get() = onlinePath != null
}

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
