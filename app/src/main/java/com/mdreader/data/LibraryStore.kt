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
        val index = projects.indexOfFirst { existing ->
            existing.id == project.id ||
                (project.treeUri.isNotBlank() && existing.treeUri == project.treeUri) ||
                (project.remoteUrl.isNotBlank() &&
                    existing.source == project.source &&
                    existing.remoteUrl == project.remoteUrl)
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
        if (progressById[projectId] == progress) return
        progressById[projectId] = progress
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
                val item = projectArray.getJSONObject(i)
                val chaptersArray = item.optJSONArray("chapters") ?: JSONArray()
                val chapters = buildList {
                    for (c in 0 until chaptersArray.length()) {
                        val chapter = chaptersArray.getJSONObject(c)
                        add(
                            Chapter(
                                name = chapter.getString("name"),
                                uri = chapter.getString("uri"),
                                displayTitle = chapter.optString("title").takeIf { chapter.has("title") && it.isNotBlank() },
                            )
                        )
                    }
                }
                projects += Project(
                    id = item.getString("id"),
                    name = item.optString("name", "Untitled"),
                    treeUri = item.optString("treeUri"),
                    chapters = chapters,
                    importedAt = item.optLong("importedAt", 0L),
                    source = item.optString("source", SOURCE_LOCAL).ifBlank { SOURCE_LOCAL },
                    remoteUrl = item.optString("remoteUrl"),
                )
            }

            val progressObject = root.optJSONObject("progress") ?: JSONObject()
            progressObject.keys().forEach { key ->
                val value = progressObject.getJSONObject(key)
                progressById[key] = Progress(
                    chapterIndex = value.optInt("chapterIndex", 0),
                    scrollIndex = value.optInt("scrollIndex", 0),
                    scrollOffset = value.optInt("scrollOffset", 0),
                    chapterUri = value.optString("chapterUri"),
                )
            }

            textSizeLevel = root.optInt("textSize", 1).coerceIn(0, 2)
        }.onFailure {
            // A corrupt file should not brick the app; start with an empty library.
            projects.clear()
            progressById.clear()
        }
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
                        .apply {
                            chapter.displayTitle?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                        }
                )
            }
            projectArray.put(
                JSONObject()
                    .put("id", project.id)
                    .put("name", project.name)
                    .put("treeUri", project.treeUri)
                    .put("source", project.source)
                    .put("remoteUrl", project.remoteUrl)
                    .put("importedAt", project.importedAt)
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
                    .put("chapterUri", progress.chapterUri)
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
