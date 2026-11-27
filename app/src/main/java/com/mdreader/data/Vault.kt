package com.mdreader.data

import android.content.Context
import com.mdreader.data.online.FreeWebNovelParser
import com.mdreader.md.Block
import com.mdreader.md.MarkdownWriter
import org.json.JSONObject
import java.io.File
import java.util.Locale

private val trailingDigits = Regex("(\\d+)$")
private val lineBreakRun = Regex("\\s*\\n\\s*")
private val unsafeFileChars = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
private val spaces = Regex("\\s+")

/** The number a site chapter carries in its path (`.../chapter-2343`), or its position. */
fun Chapter.number(index: Int): Int =
    trailingDigits.find(uri)?.value?.toIntOrNull() ?: (index + 1)

/**
 * Downloaded chapters as plain Markdown files, one folder per novel:
 *
 * ```
 * vault/<project id>/_novel.md            title, author, source
 * vault/<project id>/_index.jsonl         which site path each file holds
 * vault/<project id>/2689 - Some Title.md
 * ```
 *
 * The index is append-only, one line per chapter, so recording a download costs one small
 * write and a crash can only tear the last line, which is ignored. A chapter counts as
 * downloaded only when it has an index line *and* its file still exists. Files are written to
 * a temporary name and renamed, so a half-written chapter is never mistaken for a finished one.
 */
class Vault(private val root: File) {

    private val indexes = HashMap<String, Map<String, String>>()

    fun folder(project: Project): File = File(root, safeFolder(project.id))

    /** Site paths of every chapter of [project] that is downloaded. */
    @Synchronized
    fun downloaded(project: Project): Set<String> = index(project).keys

    @Synchronized
    fun count(project: Project): Int = index(project).size

    @Synchronized
    fun has(project: Project, chapter: Chapter): Boolean = index(project).containsKey(chapter.uri)

    /** The saved Markdown of [chapter], or null when it hasn't been downloaded. */
    @Synchronized
    fun read(project: Project, chapter: Chapter): String? {
        val name = index(project)[chapter.uri] ?: return null
        val file = File(folder(project), name)
        return if (file.isFile) file.readText() else null
    }

    @Synchronized
    fun write(project: Project, chapter: Chapter, position: Int, markdown: String) {
        val dir = folder(project).apply { mkdirs() }
        val name = fileName(chapter, position)
        val temp = File(dir, "$name.tmp")
        temp.writeText(markdown)
        val target = File(dir, name)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }

        val previous = index(project)[chapter.uri]
        // The chapter's title changed since it was last saved: keep one file, not two.
        if (previous != null && previous != name) File(dir, previous).delete()

        File(dir, INDEX).appendText(JSONObject().put("p", chapter.uri).put("f", name).toString() + "\n")
        indexes[project.id] = index(project) + (chapter.uri to name)
    }

    /** Remembers what the folder is, so it still makes sense when opened outside the app. */
    @Synchronized
    fun writeInfo(project: Project, author: String?, now: String) {
        val dir = folder(project).apply { mkdirs() }
        val source = project.onlinePath?.let { FreeWebNovelParser.ORIGIN + it }.orEmpty()
        fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        // A download that doesn't know the author (one started by a rewrite) keeps the one on file.
        val keptAuthor = if (author.isNullOrBlank()) {
            File(dir, INFO).takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("author: ") }
        } else null
        File(dir, INFO).writeText(
            buildString {
                appendLine("---")
                appendLine("title: ${quoted(project.name)}")
                if (!author.isNullOrBlank()) appendLine("author: ${quoted(author)}") else keptAuthor?.let(::appendLine)
                if (source.isNotEmpty()) appendLine("source: ${quoted(source)}")
                appendLine("chapters: ${project.totalChapters ?: project.chapters.size}")
                appendLine("updated: $now")
                appendLine("---")
                appendLine()
                appendLine("# ${project.name}")
            },
        )
    }

    /** Disk used by the saved chapters at [paths]; paths that are not saved count as nothing. */
    @Synchronized
    fun bytesOf(project: Project, paths: Collection<String>): Long {
        val dir = folder(project)
        val index = index(project)
        return paths.sumOf { path -> index[path]?.let { File(dir, it).length() } ?: 0L }
    }

    /** Deletes the saved chapters at [paths]; the rest stay. The index is rewritten to match. */
    @Synchronized
    fun delete(project: Project, paths: Collection<String>) {
        if (paths.isEmpty()) return
        val dir = folder(project)
        val current = index(project)
        val gone = paths.toSet()
        gone.forEach { path -> current[path]?.let { File(dir, it).delete() } }

        val kept = current.filterKeys { it !in gone }
        // Same temp-then-rename as a chapter, so a crash leaves the old index or the new one.
        val temp = File(dir, "$INDEX.tmp")
        temp.writeText(
            kept.entries.joinToString(separator = "") { (path, name) ->
                JSONObject().put("p", path).put("f", name).toString() + "\n"
            },
        )
        val target = File(dir, INDEX)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        indexes[project.id] = kept
    }

    /** Deletes everything downloaded for [project]. */
    @Synchronized
    fun deleteAll(project: Project) {
        folder(project).deleteRecursively()
        indexes.remove(project.id)
    }

    /** A chapter's saved Markdown: its title as a heading, then the text. */
    fun render(chapter: Chapter, blocks: List<Block>): String =
        "# ${chapter.title.replace(lineBreakRun, " ")}\n\n" + MarkdownWriter.write(blocks)

    private fun index(project: Project): Map<String, String> = indexes.getOrPut(project.id) {
        val dir = folder(project)
        val file = File(dir, INDEX)
        if (!file.isFile) return@getOrPut emptyMap()
        val map = LinkedHashMap<String, String>()
        file.forEachLine { line ->
            // A line torn by a crash simply doesn't parse and is skipped.
            val entry = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val path = entry.optString("p")
            val name = entry.optString("f")
            if (path.isNotEmpty() && name.isNotEmpty()) map[path] = name
        }
        map.filterValues { File(dir, it).isFile }
    }

    companion object {
        private const val INDEX = "_index.jsonl"
        private const val INFO = "_novel.md"

        fun of(context: Context): Vault = Vault(File(context.filesDir, "vault"))

        /** The project id is a UUID; anything else is reduced so it can't leave the vault. */
        internal fun safeFolder(id: String): String =
            id.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "novel" }

        internal fun fileName(chapter: Chapter, position: Int): String =
            String.format(Locale.ROOT, "%04d - %s.md", chapter.number(position), safeTitle(chapter.title))

        internal fun safeTitle(title: String): String =
            title
                .replace(unsafeFileChars, "")
                .replace(spaces, " ")
                .trim()
                .take(80)
                .trimEnd('.', ' ')
                .ifEmpty { "Untitled" }
    }
}
