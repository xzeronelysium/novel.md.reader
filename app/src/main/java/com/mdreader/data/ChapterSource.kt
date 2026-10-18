package com.mdreader.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

/**
 * Reads a novel folder through the Storage Access Framework.
 *
 * Everything here works on the folder the user picked; nothing is copied into
 * the app. The folder must stay where it is for the project to keep working.
 */
object ChapterSource {

    private val SUPPORTED_EXTENSIONS = setOf("md", "markdown", "txt", "text")

    fun hasPermission(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == treeUri.toString() && it.isReadPermission
        }

    /** User-visible name of the picked folder. */
    fun displayName(context: Context, treeUri: Uri): String {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val name = context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return name?.takeIf { it.isNotBlank() } ?: "Untitled"
    }

    /** All readable chapter files directly inside the folder, in natural order. */
    fun listChapters(context: Context, treeUri: Uri): List<Chapter> {
        val resolver: ContentResolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        val chapters = mutableListOf<Chapter>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val mimeType = cursor.getString(mimeColumn)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue

                val name = cursor.getString(nameColumn) ?: continue
                if (name.startsWith(".")) continue
                if (name.substringAfterLast('.', "").lowercase() !in SUPPORTED_EXTENSIONS) continue

                val documentId = cursor.getString(idColumn) ?: continue
                chapters += Chapter(
                    name = name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString(),
                )
            }
        }

        return chapters.sortedWith { a, b -> NaturalSort.compare(a.name, b.name) }
    }

    @Throws(IOException::class)
    fun read(context: Context, uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open ${uri.lastPathSegment}")
        return stream.use { it.bufferedReader().use { reader -> reader.readText() } }
    }
}
