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
        val firstToRead = if (settings.memoryDepth <= 0) 0 else maxOf(0, index - settings.memoryDepth)
        readInto(context, project, chapters, firstToRead, index, settings, bible, vault, onStatus, onProgress)
        onStatus(null)
        onProgress(0)
        val md = MarkdownWriter.write(source)
        val previousTail = chapters.getOrNull(index - 1)?.let { rawText(context, vault, project, it) }?.let { RewriteClient.tailWords(it) }
        val result = RewritePipeline.rewrite(
            settings, chapter.number(index), chapter.title, md, previousNote,
            withContext(Dispatchers.IO) { bible.context(md) },
            previousTail,
            upcoming(context, project, chapters, index, vault, onStatus),
            onStage = onStatus,
            onProgress = onProgress,
        )
        withContext(Dispatchers.IO) {
            rewrites.write(project, chapter, result.copy(sourceHash = RewriteStore.sourceHash(md)))
            bible.merge(result.sheets)
            bible.markBuilt(chapter.uri)
        }
        return result
    }

    /** How many chapters one read-ahead takes, and how near its end a reader must be to start the next. */
    const val WIKI_BATCH = 10
    private const val WIKI_REFILL = 2

    /**
     * The chapters (end exclusive) the wiki should be read up to for a reader on `chapters[index]`,
     * or null while the wiki is far enough ahead. From chapter 1 it reads to 10; on reaching chapter 9
     * it reads the next 10.
     */
    suspend fun wikiTarget(rewrites: RewriteStore, project: Project, chapters: List<Chapter>, index: Int): Int? {
        val built = withContext(Dispatchers.IO) { rewrites.bible(project).built() }
        var frontier = index
        while (frontier < chapters.size && chapters[frontier].uri in built) frontier++
        if (frontier >= chapters.size || frontier - index > WIKI_REFILL) return null
        return minOf(chapters.size, frontier + WIKI_BATCH)
    }

    /**
     * Reads the chapters before `chapters[upTo]` into the wiki without rewriting them, for books whose
     * own writing is good. The chapters the memory depth reaches back to are read first, so the sheets
     * stay in story order. [onStatus] gets "SHEETS 3/10" and null at the end.
     */
    suspend fun learn(
        context: Context,
        project: Project,
        chapters: List<Chapter>,
        from: Int,
        upTo: Int,
        settings: RewriteSettings,
        rewrites: RewriteStore,
        vault: Vault,
        onStatus: (String?) -> Unit = {},
    ) {
        val bible = rewrites.bible(project)
        val first = if (settings.memoryDepth <= 0) 0 else maxOf(0, from - settings.memoryDepth)
        try {
            readInto(context, project, chapters, first, upTo, settings, bible, vault, onStatus) {}
        } finally {
            onStatus(null)
        }
    }

    /** Reads chapters `from until to` that the memory has not read yet, downloading them first when remote. */
    private suspend fun readInto(
        context: Context,
        project: Project,
        chapters: List<Chapter>,
        from: Int,
        to: Int,
        settings: RewriteSettings,
        bible: BibleStore,
        vault: Vault,
        onStatus: (String?) -> Unit,
        onProgress: (Int) -> Unit,
    ) {
        val builtSoFar = withContext(Dispatchers.IO) { bible.built() }
        if (chapters.getOrNull(to - 1)?.remote == true) {
            val missing = (from until to).filter { chapters[it].uri !in builtSoFar && !vault.has(project, chapters[it]) }
            if (missing.isNotEmpty()) {
                val result = withContext(Dispatchers.IO) {
                    downloadChapters(project, missing, vault, null, java.time.LocalDate.now().toString()) {
                        onStatus("DOWNLOAD ${it.done}/${it.total}")
                    }
                }
                if (result.failed > 0 && result.message?.startsWith("Paused") == true) throw IOException(result.message)
            }
        }
        val todo = (from until to).mapNotNull { i ->
            val c = chapters[i]
            if (c.uri in builtSoFar) return@mapNotNull null
            rawText(context, vault, project, c)?.let { Triple(i, c, it) }
        }
        todo.forEachIndexed { n, (i, c, text) ->
            onStatus("SHEETS ${n + 1}/${todo.size}")
            val updates = RewriteClient.buildSheets(
                settings, c.number(i), c.title, text, withContext(Dispatchers.IO) { bible.roster() }, onProgress, bible::usable,
            )
            withContext(Dispatchers.IO) { bible.merge(updates); bible.markBuilt(c.uri) }
        }
    }

    /** Chapters ahead the rewrite reads for context, and how much of each it keeps. */
    private const val LOOKAHEAD = 2
    private const val LOOKAHEAD_WORDS = 1000

    /** The opening of the next [LOOKAHEAD] chapters, for those that exist and can be read; null when none. */
    private suspend fun upcoming(
        context: Context,
        project: Project,
        chapters: List<Chapter>,
        index: Int,
        vault: Vault,
        onStatus: (String?) -> Unit,
    ): String? {
        val ahead = (index + 1..index + LOOKAHEAD).filter { it < chapters.size }
        if (ahead.isEmpty()) return null
        if (chapters[ahead.first()].remote) {
            val missing = ahead.filter { !vault.has(project, chapters[it]) }
            if (missing.isNotEmpty()) {
                // Best effort: a chapter that will not download is just left out.
                runCatching {
                    withContext(Dispatchers.IO) {
                        downloadChapters(project, missing, vault, null, java.time.LocalDate.now().toString()) {
                            onStatus("DOWNLOAD ${it.done}/${it.total}")
                        }
                    }
                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                onStatus(null)
            }
        }
        return ahead.mapNotNull { i ->
            val c = chapters[i]
            val text = rawText(context, vault, project, c) ?: return@mapNotNull null
            "[Chapter ${c.number(i)}: ${c.title}]\n" + text.trim().split(Regex("\\s+")).take(LOOKAHEAD_WORDS).joinToString(" ")
        }.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }
}
