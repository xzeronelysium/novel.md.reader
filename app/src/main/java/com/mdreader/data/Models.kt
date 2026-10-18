package com.mdreader.data

/** A single chapter file belonging to a project. */
data class Chapter(
    val name: String,
    val uri: String,
) {
    /** Filename without its extension, used as the chapter heading. */
    val title: String
        get() {
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
        get() = name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")
}

/** One imported folder of chapters. */
data class Project(
    val id: String,
    val name: String,
    val treeUri: String,
    val chapters: List<Chapter>,
    val importedAt: Long,
)

/** Where the reader left off, both by chapter and by scroll position inside it. */
data class Progress(
    val chapterIndex: Int,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
)
