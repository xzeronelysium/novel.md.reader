package com.mdreader.data.comics

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plain files in a folder the user picked: nothing app-private, readable by any file manager.
 * A comic lands as `<folder>/<comic>/<#076 - Chapter title>/001.webp`, one page per file.
 * A finished chapter carries a marker so re-saving skips it, and a half-written chapter
 * resumes file by file — an existing page of non-zero size is never downloaded twice.
 */
object ComicStorage {

    /** Keeps read-write access to the picked folder across restarts, the way the import does. */
    fun keepAccess(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** The folder's own name, as the picker's document id tells it ("primary:Comics" → "Comics"). */
    fun folderName(treeUri: Uri): String =
        DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast(':').ifEmpty { "folder" }

    private fun rootOf(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun childrenUri(parent: Uri): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))

    private fun documentUri(parent: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(parent, documentId)

    private fun findChild(context: Context, parent: Uri, name: String): Uri? {
        context.contentResolver
            .query(
                childrenUri(parent),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) == name) {
                        return documentUri(parent, cursor.getString(idColumn))
                    }
                }
            }
        return null
    }

    /** The named child folder, found or created. */
    private fun childDir(context: Context, parent: Uri, name: String): Uri? {
        findChild(context, parent, name)?.let { return it }
        return runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )
        }.getOrNull()
    }

    private fun childSize(context: Context, document: Uri): Long {
        context.contentResolver
            .query(document, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0)
            }
        return 0L
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().take(120).ifEmpty { "Chapter" }

    private fun extension(url: String): String =
        url.substringBefore('?').substringAfterLast('.', "").lowercase().take(5)
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") } ?: "jpg"

    private fun mime(url: String): String = when (extension(url)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    /**
     * One framed panel — or a whole page — to the public Downloads folder, where any file
     * manager and gallery can see it. Returns the file's name, or null when this Android
     * version or the store refused it.
     */
    fun savePanel(context: Context, panel: Bitmap): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val name = "mdreader-panel-${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MD Reader")
        }
        val uri = runCatching {
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                panel.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                stream.flush()
            }
            name
        }.getOrNull()
    }

    /**
     * Writes one chapter as a folder of page files. Returns the pages written, or null when the
     * chapter was already complete; [onPage] reports each page the moment it lands.
     */
    suspend fun saveChapter(
        context: Context,
        source: ComicSource,
        treeUri: Uri,
        comicName: String,
        chapterName: String,
        pages: List<String>,
        onPage: (Int) -> Unit = {},
    ): Int? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val comicDir = childDir(context, rootOf(treeUri), sanitize(comicName))
            ?: throw ComicException("Couldn't create the comic's folder in storage.")
        val chapterDir = childDir(context, comicDir, sanitize(chapterName))
            ?: throw ComicException("Couldn't create the chapter's folder.")

        if (findChild(context, chapterDir, "complete.txt") != null) return@withContext null

        var written = 0
        pages.forEachIndexed { index, url ->
            val name = "%03d.%s".format(index + 1, extension(url))
            val target = findChild(context, chapterDir, name)
                ?: runCatching {
                    DocumentsContract.createDocument(resolver, chapterDir, mime(url), name)
                }.getOrNull()
            if (target != null && childSize(context, target) > 0L) {
                // A resumed chapter: this page is already a real file.
                written++
                onPage(written)
                return@forEachIndexed
            }
            val bytes = runCatching { source.imageBytes(url) }.getOrNull()
            if (bytes != null && target != null) {
                val stored = runCatching {
                    resolver.openOutputStream(target)?.use { stream ->
                        stream.write(bytes)
                        stream.flush()
                    } != null
                }.getOrDefault(false)
                if (stored) {
                    written++
                    onPage(written)
                }
            }
        }

        if (written == pages.size && pages.isNotEmpty()) {
            runCatching {
                DocumentsContract.createDocument(resolver, chapterDir, "text/plain", "complete.txt")
                    ?.let { marker -> resolver.openOutputStream(marker)?.use { it.write(0) } }
            }
        }
        written
    }
}
