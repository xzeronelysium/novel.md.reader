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
            it.id == project.id || it.treeUri == project.treeUri
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
                            )
                        )
                    }
                }
                projects += Project(
                    id = item.getString("id"),
                    name = item.optString("name", "Untitled"),
                    treeUri = item.getString("treeUri"),
                    chapters = chapters,
                    importedAt = item.optLong("importedAt", 0L),
                )
            }

            val progressObject = root.optJSONObject("progress") ?: JSONObject()
            progressObject.keys().forEach { key ->
                val value = progressObject.getJSONObject(key)
                progressById[key] = Progress(
                    chapterIndex = value.optInt("chapterIndex", 0),
                    scrollIndex = value.optInt("scrollIndex", 0),
                    scrollOffset = value.optInt("scrollOffset", 0),
                )
            }
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
                )
            }
            projectArray.put(
                JSONObject()
                    .put("id", project.id)
                    .put("name", project.name)
                    .put("treeUri", project.treeUri)
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
            )
        }
        root.put("progress", progressObject)

        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(file)) {
            file.writeText(root.toString())
            temp.delete()
        }
    }
}
