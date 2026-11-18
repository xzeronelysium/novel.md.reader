package com.mdreader.data

import java.io.IOException

/** Platform-specific read access to a novel folder and its chapter files. */
interface NovelFolderAccess {
    fun canAccessFolder(folderRef: String): Boolean
    fun displayName(folderRef: String): String
    fun listChapters(folderRef: String): List<Chapter>

    @Throws(IOException::class)
    fun readChapter(chapterRef: String): String
}

internal val SUPPORTED_EXTENSIONS = setOf("md", "markdown", "txt", "text")

fun isSupportedChapterFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
