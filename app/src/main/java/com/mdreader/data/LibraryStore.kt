package com.mdreader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The shelf lives in two small JSON files. Structure — novels, chapter lists, covers — changes
 * rarely and goes to `library.json`. Reading position and text size change constantly and go to
 * `progress.json`, a few hundred bytes, so a scroll never rewrites the whole library.
 *
 * Older versions kept both in `library.json`; the first load moves a legacy progress block into
 * `progress.json` and rewrites the library without it.
 */
class LibraryStore(private val filesDir: File) {

    constructor(context: Context) : this(context.filesDir)

    private val libraryFile = File(filesDir, "library.json")
    private val progressFile = File(filesDir, "progress.json")
    private val projects = mutableListOf<Project>()
    private val progressById = mutableMapOf<String, Progress>()
    private var loaded = false
    private var textSizeLevel = 1

    @Synchronized
    fun load(): List<Project> {
        ensureLoaded()
        return projects.sortedByDescending { it.importedAt }
    }

    @Synchronized
    fun find(id: String): Project? {
        ensureLoaded()
        return projects.firstOrNull { it.id == id }
    }

    /** Adds a project, or updates the existing entry for the same folder. */
    @Synchronized
    fun addOrReplace(project: Project) {
        ensureLoaded()
        val index = projects.indexOfFirst {
            it.id == project.id || when (project.onlinePath) {
                null -> it.onlinePath == null && it.treeUri == project.treeUri
                else -> it.onlinePath == project.onlinePath
            }
        }
        if (index >= 0) {
            // Keep the original id so saved progress still points at this project.
            val previous = projects[index]
            projects[index] = project.copy(id = previous.id, importedAt = previous.importedAt)
        } else {
            projects += project
        }
        persistLibrary()
    }

    @Synchronized
    fun remove(id: String) {
        ensureLoaded()
        projects.removeAll { it.id == id }
        progressById.remove(id)
        persistLibrary()
        persistProgress()
    }

    @Synchronized
    fun progress(projectId: String): Progress? {
        ensureLoaded()
        return progressById[projectId]
    }

    /** Reader text size: 0 = small, 1 = medium (default), 2 = large. */
    @Synchronized
    fun textSizeLevel(): Int {
        ensureLoaded()
        return textSizeLevel
    }

    @Synchronized
    fun setTextSizeLevel(level: Int) {
        ensureLoaded()
        val clamped = level.coerceIn(0, 2)
        if (clamped == textSizeLevel) return
        textSizeLevel = clamped
        persistProgress()
    }

    @Synchronized
    fun setProgress(projectId: String, progress: Progress) {
        ensureLoaded()
        val existing = progressById[projectId]
        if (existing != null && existing.copy(updatedAt = 0) == progress.copy(updatedAt = 0)) return
        progressById[projectId] = progress.copy(updatedAt = System.currentTimeMillis())
        persistProgress()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true

        var legacyProgress: JSONObject? = null
        var legacyTextSize: Int? = null
        if (libraryFile.exists()) {
            runCatching {
                val root = JSONObject(libraryFile.readText())
                val projectArray = root.optJSONArray("projects") ?: JSONArray()
                for (i in 0 until projectArray.length()) {
                    // One bad entry must not wipe out the rest of the library.
                    runCatching { projects += readProject(projectArray.getJSONObject(i)) }
                }
                legacyProgress = root.optJSONObject("progress")
                if (root.has("textSize")) legacyTextSize = root.optInt("textSize", 1).coerceIn(0, 2)
            }.onFailure {
                // A corrupt file should not brick the app; start with an empty library.
                projects.clear()
                progressById.clear()
            }
        }

        val progressRoot = progressFile.takeIf { it.isFile }
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

        if (progressRoot != null) {
            readProgress(progressRoot.optJSONObject("progress") ?: JSONObject())
            textSizeLevel = progressRoot.optInt("textSize", 1).coerceIn(0, 2)
        } else {
            legacyProgress?.let(::readProgress)
            legacyTextSize?.let { textSizeLevel = it }
            if (legacyProgress != null || legacyTextSize != null) {
                // Write the new file first: a crash here leaves the legacy copy intact.
                val moved = runCatching { persistProgress() }.isSuccess && progressFile.isFile
                // Only then drop the old block, and only if the new one really is on disk.
                if (moved) runCatching { persistLibrary() }
            }
        }
    }

    /** Reads a map of project id to position, as both `progress.json` and the legacy block hold it. */
    private fun readProgress(progressObject: JSONObject) {
        progressObject.keys().forEach { key ->
            runCatching {
                val value = progressObject.getJSONObject(key)
                progressById[key] = Progress(
                    chapterIndex = value.optInt("chapterIndex", 0),
                    scrollIndex = value.optInt("scrollIndex", 0),
                    scrollOffset = value.optInt("scrollOffset", 0),
                    chapterKey = value.optString("chapterKey").ifEmpty { null },
                    updatedAt = value.optLong("updatedAt", 0L),
                )
            }
        }
    }

    private fun readProject(item: JSONObject): Project {
        val chaptersArray = item.optJSONArray("chapters") ?: JSONArray()
        val chapters = buildList {
            for (c in 0 until chaptersArray.length()) {
                val chapter = chaptersArray.getJSONObject(c)
                add(
                    Chapter(
                        name = chapter.getString("name"),
                        uri = chapter.getString("uri"),
                        remote = chapter.optBoolean("remote", false),
                    )
                )
            }
        }
        return Project(
            id = item.getString("id"),
            name = item.optString("name", "Untitled"),
            treeUri = item.optString("treeUri"),
            chapters = chapters,
            importedAt = item.optLong("importedAt", 0L),
            onlinePath = item.optString("onlinePath").ifEmpty { null },
            totalChapters = if (item.has("totalChapters")) item.optInt("totalChapters") else null,
            newestFirst = item.optBoolean("newestFirst", false),
            coverUrl = item.optString("coverUrl").ifEmpty { null },
            updatedAt = item.optLong("updatedAt", 0L),
            newChapters = item.optInt("newChapters", 0),
            author = item.optString("author"),
            authorPath = item.optString("authorPath"),
            status = item.optString("status"),
            synopsis = item.optString("synopsis"),
            genres = item.optJSONArray("genres")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
            }.orEmpty(),
            rating = item.optString("rating"),
            votes = item.optInt("votes", 0),
            summaryAt = item.optLong("summaryAt", 0L),
            kind = if (item.optString("kind") == "comic") ProjectKind.COMIC else ProjectKind.NOVEL,
            sourceId = item.optString("sourceId").ifEmpty { null },
        )
    }

    /** Novels only: no reading position, so this runs only when the shelf changes. */
    private fun persistLibrary() {
        val projectArray = JSONArray()
        projects.forEach { project ->
            val chaptersArray = JSONArray()
            project.chapters.forEach { chapter ->
                chaptersArray.put(
                    JSONObject()
                        .put("name", chapter.name)
                        .put("uri", chapter.uri)
                        .apply { if (chapter.remote) put("remote", true) }
                )
            }
            projectArray.put(
                JSONObject()
                    .put("id", project.id)
                    .put("name", project.name)
                    .put("treeUri", project.treeUri)
                    .put("importedAt", project.importedAt)
                    .apply { project.onlinePath?.let { put("onlinePath", it) } }
                    .apply { project.totalChapters?.let { put("totalChapters", it) } }
                    .apply { if (project.newestFirst) put("newestFirst", true) }
                    .apply { project.coverUrl?.let { put("coverUrl", it) } }
                    .apply { if (project.updatedAt > 0) put("updatedAt", project.updatedAt) }
                    .apply { if (project.newChapters > 0) put("newChapters", project.newChapters) }
                    .apply { if (project.author.isNotEmpty()) put("author", project.author) }
                    .apply { if (project.authorPath.isNotEmpty()) put("authorPath", project.authorPath) }
                    .apply { if (project.status.isNotEmpty()) put("status", project.status) }
                    .apply { if (project.synopsis.isNotEmpty()) put("synopsis", project.synopsis) }
                    .apply { if (project.genres.isNotEmpty()) put("genres", JSONArray(project.genres)) }
                    .apply { if (project.rating.isNotEmpty()) put("rating", project.rating) }
                    .apply { if (project.votes > 0) put("votes", project.votes) }
                    .apply { if (project.summaryAt > 0) put("summaryAt", project.summaryAt) }
                    .apply { if (project.kind == ProjectKind.COMIC) put("kind", "comic") }
                    .apply { project.sourceId?.let { put("sourceId", it) } }
                    .put("chapters", chaptersArray)
            )
        }
        writeAtomically(libraryFile, JSONObject().put("projects", projectArray).toString())
    }

    /** Position and text size: small and written on every scroll pause. */
    private fun persistProgress() {
        val progressObject = JSONObject()
        progressById.forEach { (id, progress) ->
            progressObject.put(
                id,
                JSONObject()
                    .put("chapterIndex", progress.chapterIndex)
                    .put("scrollIndex", progress.scrollIndex)
                    .put("scrollOffset", progress.scrollOffset)
                    .apply { progress.chapterKey?.let { put("chapterKey", it) } }
                    .put("updatedAt", progress.updatedAt)
            )
        }
        writeAtomically(
            progressFile,
            JSONObject().put("progress", progressObject).put("textSize", textSizeLevel).toString(),
        )
    }

    private fun writeAtomically(target: File, text: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.writeText(text)
            temp.delete()
        }
    }
}
