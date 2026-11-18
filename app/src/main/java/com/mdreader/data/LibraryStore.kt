package com.mdreader.data

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * The whole library lives in one small JSON file. A database would be overkill
 * for a handful of projects, and this keeps the app free of annotation processors.
 *
 * On desktop the store lives under the XDG data directory
 * (~/.local/share/novel-md-reader/library.json) instead of Android's filesDir.
 */
class LibraryStore(storageDir: Path) {

    private val dir = storageDir
    private val file = dir.resolve("library.json")
    private val projects = mutableListOf<Project>()
    private val progressById = mutableMapOf<String, Progress>()
    private var loaded = false
    private var textSizeLevel = 1

    init {
        Files.createDirectories(dir)
    }

    /** Default storage location: ~/.local/share/novel-md-reader */
    companion object {
        fun defaultDir(): Path {
            val home = System.getProperty("user.home")
            return Paths.get(home, ".local/share", "novel-md-reader")
        }
    }

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
        if (!Files.exists(file)) return

        runCatching {
            val text = Files.readString(file)
            val root = JSONObject(text)
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

            textSizeLevel = root.optInt("textSize", 1).coerceIn(0, 2)
        }.onFailure {
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
        root.put("textSize", textSizeLevel)

        val temp = Files.createTempFile(dir, "library", ".json")
        Files.writeString(temp, root.toString())
        try {
            Files.move(temp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            // ATOMIC_MOVE not supported on all filesystems; fall back to a plain move.
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
