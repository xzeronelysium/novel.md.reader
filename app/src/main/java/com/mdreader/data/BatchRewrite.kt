package com.mdreader.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Rewriting a run of chapters ahead of reading, one after another (each chapter's rewrite builds on
 * the one before: its note, its ending, the memory it taught). The work itself runs in
 * [RewriteBatchService], so it goes on with the screen off or the app in the background; this
 * object holds what the screen and the notification show.
 */
object BatchRewrite {

    data class State(
        val running: Boolean = false,
        val projectId: String = "",
        val projectName: String = "",
        /** Positions (0-based) of the chapters this run will rewrite, in order. */
        val queue: List<Int> = emptyList(),
        /** How many of the queue are finished. */
        val done: Int = 0,
        /** The chapter being written now: its label, and the stage ("WRITING SCENE 2/4"). */
        val currentLabel: String = "",
        val stage: String = "",
        /** How the last run ended: finished, stopped by the person, or why it stopped on its own. */
        val result: String? = null,
        val failed: Boolean = false,
    ) {
        val total: Int get() = queue.size
    }

    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()

    /** Requests a chapter takes, from measured runs: one analysis, a few scenes, a repair now and then, one record. */
    const val REQUESTS_PER_CHAPTER = 6

    /** The positions in [from]..[to] a run will rewrite: every one, or only those with no rewrite yet. */
    fun queueFor(from: Int, to: Int, alreadyRewritten: Set<Int>, redo: Boolean): List<Int> =
        (minOf(from, to)..maxOf(from, to)).filter { redo || it !in alreadyRewritten }

    internal fun begin(project: Project, queue: List<Int>) {
        mutable.value = State(running = true, projectId = project.id, projectName = project.name, queue = queue)
    }

    internal fun update(transform: (State) -> State) {
        mutable.value = transform(mutable.value)
    }

    internal fun end(result: String, failed: Boolean = false) {
        mutable.value = mutable.value.copy(running = false, stage = "", currentLabel = "", result = result, failed = failed)
    }

    /** Puts the last result away, once the person has seen it. */
    fun clearResult() {
        if (!mutable.value.running) mutable.value = State()
    }

    private fun label(chapter: Chapter, index: Int) = "Chapter ${chapter.number(index)} · ${chapter.title}"

    /**
     * Runs the queue to its end or its first failure: a failure (a refused sign-in, a spent quota, a
     * dropped connection) would only repeat on the next chapter, so the run stops there and keeps
     * what it finished. Cancel the coroutine to stop. Returns how it ended.
     */
    suspend fun run(
        context: Context,
        project: Project,
        queue: List<Int>,
        settings: RewriteSettings,
        rewrites: RewriteStore,
        vault: Vault,
    ) {
        if (!settings.ready) {
            end("Set up the rewrite service first.", failed = true)
            return
        }
        try {
            val chapters = project.chapters
            // Chapters of an online novel are fetched first, in one polite pass, so the rewriting never waits on the site.
            val missing = queue.filter { chapters[it].remote && !vault.has(project, chapters[it]) }
            if (missing.isNotEmpty()) {
                update { it.copy(stage = "DOWNLOAD 0/${missing.size}") }
                val result = withContext(Dispatchers.IO) {
                    downloadChapters(project, missing, vault, null, java.time.LocalDate.now().toString()) { progress ->
                        update { it.copy(stage = "DOWNLOAD ${progress.done}/${progress.total}") }
                    }
                }
                if (result.failed > 0 && result.message?.startsWith("Paused") == true) {
                    end(result.message, failed = true)
                    return
                }
            }
            for ((n, index) in queue.withIndex()) {
                val chapter = chapters[index]
                update { it.copy(done = n, currentLabel = label(chapter, index), stage = "") }
                val source = ChapterRewriter.sourceBlocks(context, vault, project, chapter)
                if (source == null) {
                    end("Stopped at ${label(chapter, index)}: it could not be read.", failed = true)
                    return
                }
                try {
                    ChapterRewriter.rewrite(
                        context, project, chapters, index, settings, rewrites, vault, source,
                        onStatus = { stage -> update { it.copy(stage = stage.orEmpty()) } },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    end("Stopped at ${label(chapter, index)}: ${e.message ?: e.javaClass.simpleName}. $n of ${queue.size} done.", failed = true)
                    return
                }
            }
            update { it.copy(done = queue.size) }
            end("Rewrote ${queue.size} ${if (queue.size == 1) "chapter" else "chapters"}.")
        } catch (e: CancellationException) {
            val done = mutable.value.done
            end("Stopped. $done of ${queue.size} chapters done.")
            throw e
        }
    }
}
