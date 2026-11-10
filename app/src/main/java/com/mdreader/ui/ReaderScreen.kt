package com.mdreader.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mdreader.data.BatchRewrite
import com.mdreader.data.Chapter
import com.mdreader.data.ChapterRewriter
import com.mdreader.data.WikiEntry
import com.mdreader.data.WikiIndex
import com.mdreader.data.ChapterSource
import com.mdreader.data.Progress
import com.mdreader.data.RewriteStore
import com.mdreader.data.Project
import com.mdreader.data.number
import com.mdreader.data.Vault
import com.mdreader.data.online.FreeWebNovelClient
import com.mdreader.data.online.OnlineException
import com.mdreader.data.remapTo
import com.mdreader.md.Block
import com.mdreader.md.MarkdownParser
import com.mdreader.md.withoutOpeningTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
@Composable
fun ReaderScreen(
    project: Project,
    initialProgress: Progress?,
    textSizeLevel: Int,
    onTextSizeChange: (Int) -> Unit,
    onBack: () -> Unit,
    onProgress: (Progress) -> Unit,
) {
    val context = LocalContext.current
    val vault = remember { Vault.of(context.applicationContext) }
    val chapters = project.chapters
    val lastIndex = chapters.lastIndex
    // An online list may be only partly loaded; show the site's real count.
    val total = project.totalChapters?.takeIf { it > chapters.size } ?: chapters.size
    // Match the saved position to its chapter by identity: an online list may have shifted.
    val startProgress = remember(project.id) { initialProgress?.remapTo(chapters) }

    var index by rememberSaveable(project.id) {
        mutableIntStateOf((startProgress?.chapterIndex ?: 0).coerceIn(0, lastIndex.coerceAtLeast(0)))
    }
    var blocks by remember(project.id) { mutableStateOf<List<Block>>(emptyList()) }
    var loading by remember(project.id) { mutableStateOf(true) }
    var loadError by remember(project.id) { mutableStateOf<String?>(null) }
    var showChapters by remember { mutableStateOf(false) }
    var showWiki by remember { mutableStateOf(false) }
    var wikiEntry by remember { mutableStateOf<WikiEntry?>(null) }
    var wikiIndex by remember { mutableStateOf(WikiIndex(emptyList())) }
    val wikiLinks = remember(wikiIndex) { WikiLinks(wikiIndex) { wikiEntry = it; showWiki = true } }
    val rewrites = remember { RewriteStore.of(context.applicationContext) }
    val scope = rememberCoroutineScope()
    // The saved rewrite of the open chapter, and whether it is the one on screen.
    var rewriteBlocks by remember(project.id) { mutableStateOf<List<Block>?>(null) }
    var showRewrite by remember(project.id) { mutableStateOf(false) }
    // Which tab the reader chose last. It follows them from chapter to chapter: a chapter with a
    // rewrite opens in the rewrite while this is on, one without opens as the original and leaves it on.
    var preferRewrite by rememberSaveable(project.id) { mutableStateOf(startProgress?.rewritten == true) }
    var rewriting by remember(project.id) { mutableStateOf(false) }
    var rewriteError by remember(project.id) { mutableStateOf<String?>(null) }
    var rewriteChars by remember(project.id) { mutableIntStateOf(0) }
    var rewriteStatus by remember(project.id) { mutableStateOf<String?>(null) }
    var rewriteJob by remember(project.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var learning by remember(project.id) { mutableStateOf(false) }
    var learnJob by remember(project.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var learnStatus by remember(project.id) { mutableStateOf<String?>(null) }
    var showRewriteSettings by remember { mutableStateOf(false) }
    var pendingRestore by remember(project.id) { mutableStateOf(startProgress) }
    // Each tab keeps its own place in the open chapter: (first visible item, its offset).
    var originalPos by remember(project.id) { mutableStateOf(0 to 0) }
    var rewritePos by remember(project.id) { mutableStateOf(0 to 0) }
    // Set once the open chapter's saved rewrite has been looked up, so restoring can pick the tab.
    var rewriteChecked by remember(project.id) { mutableStateOf(false) }
    var attempt by remember(project.id) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val chapterPercent by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val first = layout.visibleItemsInfo.firstOrNull()
            val last = layout.visibleItemsInfo.lastOrNull()
            if (first == null || last == null || layout.totalItemsCount == 0) {
                null
            } else {
                chapterScrollPercent(
                    firstIndex = first.index,
                    firstOffset = first.offset,
                    firstSize = first.size,
                    totalItems = layout.totalItemsCount,
                    atEnd = last.index == layout.totalItemsCount - 1 &&
                        last.offset + last.size <= layout.viewportEndOffset,
                )
            }
        }
    }

    LaunchedEffect(project.id, index, attempt) {
        loading = true
        loadError = null
        val chapter = chapters.getOrNull(index)
        if (chapter == null) {
            blocks = emptyList()
            loading = false
            return@LaunchedEffect
        }

        val outcome = if (chapter.remote) {
            // A downloaded chapter opens from the vault, offline; anything else comes from the site.
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    vault.read(project, chapter)?.let { text ->
                        MarkdownParser.parse(text, markdown = true).withoutOpeningTitle(chapter.title)
                    }
                }.getOrNull()
            }?.takeIf { it.isNotEmpty() }
            if (saved != null) Result.success(saved) else runCatching { FreeWebNovelClient.loadChapter(chapter.uri) }
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val text = ChapterSource.read(context, Uri.parse(chapter.uri))
                    MarkdownParser
                        .parse(text, chapter.isMarkdown)
                        .withoutOpeningTitle(chapter.title)
                }
            }
        }
        outcome.fold(
            onSuccess = { blocks = it },
            onFailure = {
                blocks = emptyList()
                loadError = if (chapter.remote) {
                    (it as? OnlineException)?.message ?: run {
                        android.util.Log.e("MDReader", "chapter load failed: ${chapter.uri}", it)
                        "Couldn't load this chapter. (${it.javaClass.simpleName}: ${it.message})"
                    }
                } else {
                    "Couldn't open \"${chapter.name}\". The folder may have moved, " +
                        "been renamed, or lost its permission."
                }
            },
        )
        loading = false
    }

    LaunchedEffect(project.id, index) {
        showRewrite = false
        rewriteChecked = false
        originalPos = 0 to 0
        rewritePos = 0 to 0
        rewriteError = null
        val chapter = chapters.getOrNull(index)
        rewriteBlocks = chapter?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    rewrites.read(project, it)?.let { saved ->
                        MarkdownParser.parse(saved.chapter, markdown = true).withoutOpeningTitle(it.title)
                    }
                }.getOrNull()
            }
        }
        // A place restored from the last visit decides its own tab; every other chapter follows the preference.
        if (pendingRestore?.chapterIndex != index) showRewrite = preferRewrite && rewriteBlocks != null
        rewriteChecked = true
    }

    fun startRewrite() {
        val chapter = chapters.getOrNull(index) ?: return
        val settings = rewrites.settings()
        if (!settings.ready) {
            showRewriteSettings = true
            return
        }
        val source = blocks
        if (source.isEmpty() || rewriting) return
        if (BatchRewrite.state.value.running) {
            rewriteError = "Chapters are being rewritten ahead. Stop that in the rewrite settings first."
            return
        }
        learnJob?.cancel()
        rewriting = true
        rewriteError = null
        rewriteChars = 0
        rewriteJob = scope.launch {
            try {
                val result = ChapterRewriter.rewrite(
                    context, project, chapters, index, settings, rewrites, vault, source,
                    onStatus = { rewriteStatus = it }, onProgress = { rewriteChars = it },
                )
                val parsed = MarkdownParser.parse(result.chapter, markdown = true).withoutOpeningTitle(chapter.title)
                // The reader may have moved on while the answer was coming back.
                if (chapters.getOrNull(index) == chapter) {
                    if (!showRewrite) originalPos = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                    rewriteBlocks = parsed
                    showRewrite = true
                    preferRewrite = true
                    // A fresh rewrite is read from its start.
                    rewritePos = 0 to 0
                    listState.scrollToItem(0)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                rewriteError = e.message ?: e.javaClass.simpleName
            } finally {
                rewriting = false
                rewriteStatus = null
                rewriteJob = null
            }
        }
    }

    // Wiki as you read: an opened chapter is read into the wiki in the background, rewritten or not.
    // Waits for the chapter to load and stays out of the way of a rewrite or a batch; a failure is
    // quiet, the chapter is simply tried again the next time it is opened.
    // The read-ahead runs on its own job, so turning the page does not cut it off.
    LaunchedEffect(project.id, index, loading, rewriting, blocks.isNotEmpty()) {
        val settings = rewrites.settings()
        if (!settings.autoWiki || !settings.ready || loading || rewriting || blocks.isEmpty()) return@LaunchedEffect
        if (learnJob?.isActive == true || BatchRewrite.state.value.running) return@LaunchedEffect
        val upTo = ChapterRewriter.wikiTarget(rewrites, project, chapters, index) ?: return@LaunchedEffect
        learning = true
        learnJob = scope.launch {
            try {
                ChapterRewriter.learn(context, project, chapters, index, upTo, settings, rewrites, vault) { learnStatus = it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MDReader", "wiki read failed near ${chapters.getOrNull(index)?.uri}", e)
            } finally {
                learning = false
                learnStatus = null
            }
        }
    }

    // Restore the saved scroll position once per visit, otherwise start at the top.
    // Waits for the rewrite lookup too, so a chapter last read in the rewrite reopens there.
    LaunchedEffect(blocks, rewriteChecked) {
        if (blocks.isEmpty() || !rewriteChecked) return@LaunchedEffect
        val restore = pendingRestore
        if (restore != null && restore.chapterIndex == index) {
            pendingRestore = null
            originalPos = restore.scrollIndex to restore.scrollOffset
            val rewritten = rewriteBlocks
            if (rewritten != null) rewritePos = restore.rewriteIndex to restore.rewriteOffset
            showRewrite = restore.rewritten && rewritten != null
            val (item, offset) = if (showRewrite) rewritePos else originalPos
            val last = (if (showRewrite) rewritten!! else blocks).lastIndex
            listState.scrollToItem(item.coerceIn(0, last.coerceAtLeast(0)), offset.coerceAtLeast(0))
        } else {
            listState.scrollToItem(0)
        }
    }

    /** The open chapter's place in both tabs, the one on screen read live from the list. */
    fun currentProgress(): Progress {
        val here = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        val original = if (showRewrite) originalPos else here
        val rewrite = if (showRewrite) here else rewritePos
        return Progress(
            index, original.first, original.second, chapters.getOrNull(index)?.uri,
            rewritten = showRewrite, rewriteIndex = rewrite.first, rewriteOffset = rewrite.second,
        )
    }

    /** Switches tab, remembering where this one was left and returning to where the other was. */
    fun switchTab(toRewrite: Boolean) {
        preferRewrite = toRewrite
        if (showRewrite == toRewrite) return
        val here = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        if (showRewrite) rewritePos = here else originalPos = here
        showRewrite = toRewrite
        val (item, offset) = if (toRewrite) rewritePos else originalPos
        scope.launch { listState.scrollToItem(item, offset) }
    }

    LaunchedEffect(project.id, index) {
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, showRewrite) }
            .debounce(500)
            .collect {
                // Ignore positions from the previous chapter while the new one loads, and before
                // the saved place has been restored (it would overwrite it with the top).
                if (!loading && rewriteChecked && pendingRestore?.chapterIndex != index) onProgress(currentProgress())
            }
    }

    fun saveAndLeave() {
        onProgress(currentProgress())
        onBack()
    }

    BackHandler { saveAndLeave() }

    fun goTo(target: Int) {
        val clamped = target.coerceIn(0, lastIndex.coerceAtLeast(0))
        if (clamped == index) return
        index = clamped
        blocks = emptyList()
        loadError = null
        loading = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        ReaderTopBar(
            bookTitle = project.name,
            onBack = { saveAndLeave() },
            onIndex = { showChapters = true },
            onWiki = { wikiEntry = null; showWiki = true },
            wikiEnabled = !wikiIndex.isEmpty(),
            indexEnabled = chapters.isNotEmpty(),
        )
        RewriteTabs(
            chapterNumber = index + 1,
            chapterPercent = if (!loading && loadError == null && blocks.isNotEmpty()) chapterPercent else null,
            showRewrite = showRewrite,
            hasRewrite = rewriteBlocks != null,
            rewriting = rewriting,
            words = rewriteChars / 6,
            status = rewriteStatus,
            canStart = !loading && loadError == null && blocks.isNotEmpty(),
            onOriginal = {
                // Back to the original also puts away a failed rewrite's message.
                rewriteError = null
                switchTab(toRewrite = false)
            },
            onRewritten = {
                when {
                    rewriting -> Unit
                    rewriteBlocks != null -> switchTab(toRewrite = true)
                    else -> startRewrite()
                }
            },
            onStop = { rewriteJob?.cancel() },
            onRewriteAgain = { startRewrite() },
            onSettings = { showRewriteSettings = true },
        )
        if (learning) {
            // "SHEETS 3/10" while chapters are read, "DOWNLOAD 2/5" before that.
            Text(
                text = "WIKI  " + (learnStatus?.substringAfter(' ') ?: "…") +
                    (learnStatus?.takeIf { it.startsWith("DOWNLOAD") }?.let { "  downloading" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
            )
        }
        ChapterRail(percent = if (!loading && loadError == null && blocks.isNotEmpty()) chapterPercent ?: 0 else 0)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val message = loadError
            val chapter = chapters.getOrNull(index)
            when {
                rewriteError != null -> ReaderMessage(
                    label = "Rewrite",
                    title = "The rewrite failed",
                    body = rewriteError.orEmpty(),
                    actionLabel = "Try again",
                    onAction = { startRewrite() },
                )

                message != null -> ReaderMessage(
                    label = "Error",
                    title = "Couldn't open this chapter",
                    body = message,
                    actionLabel = if (chapter?.remote == true) "Try again" else null,
                    onAction = { attempt++ },
                )

                loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 1.5.dp,
                    )
                }

                chapters.isEmpty() || chapter == null -> ReaderMessage(
                    label = "Empty",
                    title = "No chapters",
                    body = "Re-import the folder from the library.",
                )

                blocks.isEmpty() -> ReaderMessage(
                    label = "Empty",
                    title = "This chapter is empty.",
                    body = "Move on to the next one.",
                )

                else -> {
                    // S/M/L scales every sp value in the reading column, on top of
                    // whatever the system font scale already is.
                    val density = LocalDensity.current
                    val scaled = when (textSizeLevel.coerceIn(0, 2)) {
                        0 -> 0.88f
                        1 -> 1f
                        else -> 1.15f
                    }
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, density.fontScale * scaled),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = ScreenPad,
                                end = ScreenPad,
                                top = 34.dp,
                                bottom = 32.dp,
                            ),
                        ) {
                            item(key = "opening") {
                                ChapterOpening(
                                    chapter = chapter,
                                    index = index,
                                    total = total,
                                )
                            }
                            items(if (showRewrite) rewriteBlocks ?: blocks else blocks) { block ->
                                CompositionLocalProvider(LocalWiki provides wikiLinks) { BlockView(block) }
                            }
                        }
                    }
                }
            }
        }

        if (chapters.isNotEmpty()) {
            ReaderFooter(
                index = index,
                total = total,
                chapterPercent = if (!loading && loadError == null && blocks.isNotEmpty()) chapterPercent else null,
                onPrevious = { goTo(index - 1) },
                onNext = { goTo(index + 1) },
            )
        }
    }

    val wikiChapter = chapters.getOrNull(index)?.number(index) ?: (index + 1)
    LaunchedEffect(project.id, wikiChapter, rewriting, learning) {
        wikiIndex = withContext(Dispatchers.IO) { rewrites.bible(project).index(wikiChapter) }
    }
    if (showWiki) {
        WikiSheet(
            bible = remember(project.id) { rewrites.bible(project) },
            index = wikiIndex,
            upTo = wikiChapter,
            start = wikiEntry,
            onDismiss = { showWiki = false },
        )
    }

    if (showRewriteSettings) {
        RewriteSettingsScreen(
            initial = remember { rewrites.settings() },
            memoryChapters = remember(showRewriteSettings) { rewrites.bible(project).built().size },
            earlierRead = remember(showRewriteSettings, index) {
                val built = rewrites.bible(project).built()
                chapters.take(index).map { it.uri in built }
            },
            // Sealing the key is Keystore work: keep it off the main thread.
            onChange = { next -> scope.launch(Dispatchers.IO) { rewrites.saveSettings(next) } },
            onRebuildMemory = { rewrites.bible(project).clear() },
            onBack = { showRewriteSettings = false },
            ahead = AheadSetup(
                projectId = project.id,
                chapterLabels = remember(project.id, chapters.size) { chapters.mapIndexed { i, c -> "Chapter ${c.number(i)} · ${c.title}" } },
                currentIndex = index,
                rewritten = remember(showRewriteSettings, project.id) { rewrites.rewrittenPositions(project, chapters) },
                busy = rewriting,
            ),
            onStartAhead = { queue -> com.mdreader.data.RewriteBatchService.start(context, project.id, queue) },
            onStopAhead = { com.mdreader.data.RewriteBatchService.stop(context) },
        )
    }

    if (showChapters) {
        ChapterListSheet(
            project = project,
            currentIndex = index,
            textSizeLevel = textSizeLevel,
            onTextSizeChange = onTextSizeChange,
            onSelect = { selected ->
                showChapters = false
                goTo(selected)
            },
            onDismiss = { showChapters = false },
        )
    }
}

/**
 * Back, the book's title and INDEX. Touch areas are 48dp; the visible title sits on the text's
 * 28dp margin, so the bar lines up with everything under it.
 */
@Composable
private fun ReaderTopBar(
    bookTitle: String,
    onBack: () -> Unit,
    onIndex: () -> Unit,
    indexEnabled: Boolean,
    onWiki: () -> Unit,
    wikiEnabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = onBack)
                .semantics {
                    contentDescription = "Back to library"
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            QuietArrow(direction = ArrowDirection.Left)
        }
        Text(
            text = bookTitle.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .height(48.dp)
                .clickable(enabled = wikiEnabled, onClick = onWiki)
                .padding(horizontal = 12.dp)
                .semantics {
                    contentDescription = "Open the novel's wiki"
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "WIKI",
                style = MaterialTheme.typography.labelSmall,
                color = if (wikiEnabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
            )
        }
        Box(
            modifier = Modifier
                .height(48.dp)
                .clickable(enabled = indexEnabled, onClick = onIndex)
                .padding(horizontal = 12.dp)
                .semantics {
                    contentDescription = "Open chapter index"
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "INDEX",
                style = MaterialTheme.typography.labelSmall,
                color = if (indexEnabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
            )
        }
    }
}

/** How far through this chapter the reader is: a 2dp line where the text begins. */
@Composable
private fun ChapterRail(percent: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (percent > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun ChapterOpening(
    chapter: Chapter,
    index: Int,
    total: Int,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height(1.dp)
                    .background(scheme.primary),
            )
            Spacer(Modifier.width(9.dp))
            Micro(
                text = "Chapter ${(index + 1).toString().padStart(2, '0')}",
                accent = true,
            )
        }

        Spacer(Modifier.height(17.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = chapter.title,
                color = scheme.onSurface,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Normal,
                fontSize = 33.sp,
                lineHeight = 40.sp,
                letterSpacing = (-0.7).sp,
            )
            Spacer(Modifier.height(12.dp))
            Micro(
                text = "${(index + 1).toString().padStart(2, '0')} / " +
                    total.toString().padStart(2, '0'),
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        Spacer(Modifier.height(26.dp))
        Ornament()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Ornament() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Hairline(modifier = Modifier.weight(1f))
        Text(
            text = "間",
            color = MaterialTheme.colorScheme.outline,
            fontFamily = FontFamily.SansSerif,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Hairline(modifier = Modifier.weight(1f))
    }
}

/** One 56dp bar: Previous, the chapter number, Next; each pager is a full 48dp target. */
@Composable
private fun ReaderFooter(
    index: Int,
    total: Int,
    chapterPercent: Int?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Hairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Pager(ArrowDirection.Left, "Prev", arrowFirst = true, enabled = index > 0, onClick = onPrevious)
            }
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.Medium)) {
                        append("%,d".format(index + 1))
                    }
                    withStyle(SpanStyle(color = scheme.onSurfaceVariant)) {
                        append(" / ")
                        append("%,d".format(total))
                    }
                },
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                modifier = Modifier.semantics {
                    contentDescription = "Chapter ${index + 1} of $total" +
                        (chapterPercent?.let { ", $it percent read" } ?: "")
                },
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Pager(ArrowDirection.Right, "Next", arrowFirst = false, enabled = index < total - 1, onClick = onNext)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Pager(
    direction: ArrowDirection,
    label: String,
    arrowFirst: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }

    Row(
        modifier = Modifier
            .height(48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (arrowFirst) {
            QuietArrow(direction = direction, color = color)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
        )
        if (!arrowFirst) {
            Spacer(Modifier.width(8.dp))
            QuietArrow(direction = direction, color = color)
        }
    }
}

@Composable
private fun ReaderMessage(
    label: String,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPad, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Micro(text = label, accent = true)
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = actionLabel.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onAction)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/**
 * ORIGINAL | REWRITTEN as one square segmented control with the settings gear beside it, then a
 * line with the chapter's place on the left and the rewrite's action on the right. Everything
 * visible sits on the text's 28dp margin.
 */
@Composable
private fun RewriteTabs(
    chapterNumber: Int,
    chapterPercent: Int?,
    showRewrite: Boolean,
    hasRewrite: Boolean,
    rewriting: Boolean,
    words: Int,
    status: String?,
    canStart: Boolean,
    onOriginal: () -> Unit,
    onRewritten: () -> Unit,
    onStop: () -> Unit,
    onRewriteAgain: () -> Unit,
    onSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // Replacing a saved rewrite takes a second tap; the ask resets when the view changes.
    var confirmAgain by remember(showRewrite, hasRewrite, rewriting) { mutableStateOf(false) }
    @Composable
    fun Segment(label: String, selected: Boolean, accent: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Box(
            modifier = modifier
                .fillMaxHeight()
                .background(if (selected) scheme.onSurface else androidx.compose.ui.graphics.Color.Transparent)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { role = Role.Tab },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    selected -> scheme.background
                    !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
                    accent -> scheme.primary
                    else -> scheme.onSurfaceVariant
                },
            )
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ScreenPad, end = ScreenPad, top = 4.dp)
            .height(36.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f).fillMaxHeight().border(1.dp, scheme.outline)) {
            Segment("ORIGINAL", !showRewrite, false, true, Modifier.weight(1f), onOriginal)
            Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outline))
            Segment(
                label = when {
                    rewriting -> "● ${words}w"
                    hasRewrite -> "REWRITTEN"
                    else -> "✦ REWRITE"
                },
                selected = showRewrite && hasRewrite,
                accent = rewriting || !hasRewrite,
                enabled = rewriting || hasRewrite || canStart,
                modifier = Modifier.weight(1f),
                onClick = onRewritten,
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .border(1.dp, scheme.outline)
                .clickable(onClick = onSettings)
                .semantics {
                    contentDescription = "Rewrite settings"
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("⚙", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        }
    }
    val place = "CH. %,d".format(chapterNumber) + (chapterPercent?.let { " · $it%" } ?: "")
    val (left, action, onAction) = when {
        rewriting -> Triple(
            listOfNotNull(status, words.takeIf { it > 0 }?.let { "$it words" }).joinToString(" · ")
                .ifEmpty { "Starting the rewrite. You can keep reading." },
            "STOP",
            onStop,
        )
        showRewrite && hasRewrite -> Triple(
            if (confirmAgain) "Replace this rewrite?" else place,
            if (confirmAgain) "TAP AGAIN TO REPLACE" else "REWRITE AGAIN",
            {
                if (confirmAgain) {
                    confirmAgain = false
                    onRewriteAgain()
                } else {
                    confirmAgain = true
                }
            },
        )
        else -> Triple(place, null, {})
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPad)
            .height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = left,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            val enabled = rewriting || canStart
            Box(
                Modifier
                    .height(32.dp)
                    .clickable(enabled = enabled, onClick = onAction)
                    .padding(start = 12.dp)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = action,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}
