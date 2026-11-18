package com.mdreader.data

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Reads a novel folder from the local filesystem.
 *
 * Everything here works on the folder the user picked; nothing is copied into
 * the app. The folder must stay where it is for the project to keep working.
 */
object ChapterSource {

    private val SUPPORTED_EXTENSIONS = setOf("md", "markdown", "txt", "text")

    fun hasPermission(path: Path): Boolean = Files.isReadable(path) && Files.isDirectory(path)

    /** User-visible name of the picked folder. */
    fun displayName(path: Path): String =
        path.fileName?.toString()?.takeIf { it.isNotBlank() } ?: "Untitled"

    /** All readable chapter files directly inside the folder, in natural order. */
    fun listChapters(path: Path): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        val dir = path.toFile()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            if (name.startsWith(".")) return@forEach
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in SUPPORTED_EXTENSIONS) return@forEach
            chapters += Chapter(
                name = name,
                uri = file.toPath().toString(),
            )
        }
        return chapters.sortedWith { a, b -> NaturalSort.compare(a.name, b.name) }
    }

    @Throws(IOException::class)
    fun read(uri: String): String {
        val path = Paths.get(uri)
        return Files.readString(path)
    }
}
