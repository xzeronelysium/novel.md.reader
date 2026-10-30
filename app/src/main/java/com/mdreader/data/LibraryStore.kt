package com.mdreader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The whole library lives in one small JSON file. A database would be overkill
 * for a handful of projects, and this keeps the app free of annotation processors.
 */
class LibraryStore(context: Context) {

    private val file = File(context.filesDir, "library.json")
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
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        ensureLoaded()
        projects.removeAll { it.id == id }
        progressById.remove(id)
        persist()
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
        persist()
    }

    @Synchronized
    fun setProgress(projectId: String, progress: Progress) {
        ensureLoaded()
        val existing = progressById[projectId]
        if (existing != null && existing.copy(updatedAt = 0) == progress.copy(updatedAt = 0)) return
        progressById[projectId] = progress.copy(updatedAt = System.currentTimeMillis())
        persist()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return

        runCatching {
            val root = JSONObject(file.readText())
            val projectArray = root.optJSONArray("projects") ?: JSONArray()
            for (i in 0 until projectArray.length()) {
                // One bad entry must not wipe out the rest of the library.
                runCatching { projects += readProject(projectArray.getJSONObject(i)) }
            }

            val progressObject = root.optJSONObject("progress") ?: JSONObject()
            progressObject.keys().forEach { key ->
                val value = progressObject.getJSONObject(key)
                progressById[key] = Progress(
                    chapterIndex = value.optInt("chapterIndex", 0),
                    scrollIndex = value.optInt("scrollIndex", 0),
                    scrollOffset = value.optInt("scrollOffset", 0),
                    chapterKey = value.optString("chapterKey").ifEmpty { null },
                    updatedAt = value.optLong("updatedAt", 0L),
                )
            }

            textSizeLevel = root.optInt("textSize", 1).coerceIn(0, 2)
        }.onFailure {
            // A corrupt file should not brick the app; start with an empty library.
            projects.clear()
            progressById.clear()
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
        )
    }

    private fun persist() {
        val root = JSONObject()

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
                    .put("chapters", chaptersArray)
            )
        }
        root.put("projects", projectArray)

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
        root.put("progress", progressObject)
        root.put("textSize", textSizeLevel)

        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(file)) {
            file.writeText(root.toString())
            temp.delete()
        }
    }
}
