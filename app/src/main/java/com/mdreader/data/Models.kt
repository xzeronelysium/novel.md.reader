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
            return base.ifBlank { name }
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
