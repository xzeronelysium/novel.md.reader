package com.mdreader.data

import android.content.Context
import android.net.Uri

class AndroidNovelFolderAccess(private val context: Context) : NovelFolderAccess {
    override fun canAccessFolder(folderRef: String): Boolean =
        ChapterSource.hasPermission(context, Uri.parse(folderRef))

    override fun displayName(folderRef: String): String =
        ChapterSource.displayName(context, Uri.parse(folderRef))

    override fun listChapters(folderRef: String): List<Chapter> =
        ChapterSource.listChapters(context, Uri.parse(folderRef))

    override fun readChapter(chapterRef: String): String =
        ChapterSource.read(context, Uri.parse(chapterRef))
}
