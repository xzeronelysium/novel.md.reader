package com.mdreader.desktop.data

import com.mdreader.data.Chapter
import com.mdreader.data.NaturalSort
import com.mdreader.data.NovelFolderAccess
import com.mdreader.data.isSupportedChapterFile
import java.io.File
import java.io.IOException

object FsNovelFolderAccess : NovelFolderAccess {
    override fun canAccessFolder(folderRef: String): Boolean {
        val dir = File(folderRef)
        return dir.isDirectory && dir.canRead()
    }

    override fun displayName(folderRef: String): String =
        File(folderRef).name.ifBlank { "Untitled" }

    override fun listChapters(folderRef: String): List<Chapter> {
        val dir = File(folderRef)
        if (!dir.isDirectory) return emptyList()

        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && !it.name.startsWith('.') }
            ?.filter { isSupportedChapterFile(it.name) }
            ?.map { file ->
                Chapter(name = file.name, uri = file.absolutePath)
            }
            ?.sortedWith { a, b -> NaturalSort.compare(a.name, b.name) }
            ?.toList()
            ?: emptyList()
    }

    override fun readChapter(chapterRef: String): String {
        val file = File(chapterRef)
        if (!file.isFile) throw IOException("Could not open $chapterRef")
        return file.readText()
    }
}
