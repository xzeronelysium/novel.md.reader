package com.mdreader.data

import android.content.Context
import android.net.Uri
import com.mdreader.md.Block
import com.mdreader.md.MarkdownParser
import com.mdreader.md.MarkdownWriter
import com.mdreader.md.withoutOpeningTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Rewrites one chapter of a novel the way the reader's REWRITE button does, so one chapter and a
 * batch go through the same steps: bring the earlier chapters the memory needs, let the memory read
 * the ones it has not, run the rewrite, and save the result and what it taught the memory.
 */
object ChapterRewriter {

    /** The chapter's text as the reader shows it (its repeated heading dropped); null when unreadable. */
    suspend fun sourceBlocks(context: Context, vault: Vault, project: Project, chapter: Chapter): List<Block>? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (chapter.remote) {
                    vault.read(project, chapter)?.let { MarkdownParser.parse(it, markdown = true) }
                } else {
                    MarkdownParser.parse(ChapterSource.read(context, Uri.parse(chapter.uri)), chapter.isMarkdown)
                }?.withoutOpeningTitle(chapter.title)
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

    private suspend fun rawText(context: Context, vault: Vault, project: Project, chapter: Chapter): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (chapter.remote) vault.read(project, chapter) else ChapterSource.read(context, Uri.parse(chapter.uri))
            }.getOrNull()
        }

    /**
     * Rewrites `chapters[index]` from [source] and saves it. [onStatus] gets the stage ("DOWNLOAD 2/5",
     * "SHEETS 1/3", "WRITING SCENE 2/4") and null when a stage ends; [onProgress] the characters written.
     * Throws with a message fit to show.
     */
    suspend fun rewrite(
        context: Context,
        project: Project,
        chapters: List<Chapter>,
        index: Int,
        settings: RewriteSettings,
        rewrites: RewriteStore,
        vault: Vault,
        source: List<Block>,
        onStatus: (String?) -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): Rewrite {
        val chapter = chapters[index]
        val previousNote = withContext(Dispatchers.IO) {
            chapters.getOrNull(index - 1)?.let { rewrites.read(project, it)?.note }?.let(RewriteClient::carryNote)
        }
        val bible = rewrites.bible(project)
        // Cold start: read the earlier chapters into the sheets before rewriting this one.
        val builtSoFar = withContext(Dispatchers.IO) { bible.built() }
        // How far back the memory reads: the last N chapters, or every chapter from the first.
        val firstToRead = if (settings.memoryDepth <= 0) 0 else maxOf(0, index - settings.memoryDepth)
        if (chapter.remote) {
            val missing = (firstToRead until index).filter { chapters[it].uri !in builtSoFar && !vault.has(project, chapters[it]) }
            if (missing.isNotEmpty()) {
                val result = withContext(Dispatchers.IO) {
                    downloadChapters(project, missing, vault, null, java.time.LocalDate.now().toString()) {
                        onStatus("DOWNLOAD ${it.done}/${it.total}")
                    }
                }
                if (result.failed > 0 && result.message?.startsWith("Paused") == true) throw IOException(result.message)
            }
        }
        val todo = (firstToRead until index).mapNotNull { i ->
            val c = chapters[i]
            if (c.uri in builtSoFar) return@mapNotNull null
            rawText(context, vault, project, c)?.let { Triple(i, c, it) }
        }
        todo.forEachIndexed { n, (i, c, text) ->
            onStatus("SHEETS ${n + 1}/${todo.size}")
            val updates = RewriteClient.buildSheets(settings, c.number(i), c.title, text, onProgress)
            withContext(Dispatchers.IO) { bible.merge(updates); bible.markBuilt(c.uri) }
        }
        onStatus(null)
        onProgress(0)
        val md = MarkdownWriter.write(source)
        val previousTail = chapters.getOrNull(index - 1)?.let { rawText(context, vault, project, it) }?.let { RewriteClient.tailWords(it) }
        val result = RewritePipeline.rewrite(
            settings, chapter.number(index), chapter.title, md, previousNote,
            withContext(Dispatchers.IO) { bible.context(md) },
            previousTail,
            onStage = onStatus,
            onProgress = onProgress,
        )
        withContext(Dispatchers.IO) {
            rewrites.write(project, chapter, result)
            bible.merge(result.sheets)
            bible.markBuilt(chapter.uri)
        }
        return result
    }
}
