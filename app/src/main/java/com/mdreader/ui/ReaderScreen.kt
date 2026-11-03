package com.mdreader.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mdreader.data.Chapter
import com.mdreader.data.ChapterSource
import com.mdreader.data.Progress
import com.mdreader.data.RewriteClient
import com.mdreader.data.RewritePipeline
import com.mdreader.data.RewriteStore
import com.mdreader.data.Project
import com.mdreader.data.downloadChapters
import com.mdreader.data.number
import com.mdreader.data.Vault
import com.mdreader.data.online.FreeWebNovelClient
import com.mdreader.data.online.OnlineException
import com.mdreader.data.remapTo
import com.mdreader.md.Block
import com.mdreader.md.MarkdownParser
import com.mdreader.md.MarkdownWriter
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
    val rewrites = remember { RewriteStore.of(context.applicationContext) }
    val scope = rememberCoroutineScope()
    // The saved rewrite of the open chapter, and whether it is the one on screen.
    var rewriteBlocks by remember(project.id) { mutableStateOf<List<Block>?>(null) }
    var showRewrite by remember(project.id) { mutableStateOf(false) }
    var rewriting by remember(project.id) { mutableStateOf(false) }
    var rewriteError by remember(project.id) { mutableStateOf<String?>(null) }
    var rewriteChars by remember(project.id) { mutableIntStateOf(0) }
    var rewriteStatus by remember(project.id) { mutableStateOf<String?>(null) }
    var rewriteJob by remember(project.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
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
        rewriting = true
        rewriteError = null
        rewriteChars = 0
        val previousNote = chapters.getOrNull(index - 1)?.let { rewrites.read(project, it)?.note }?.let(RewriteClient::carryNote)
        rewriteJob = scope.launch {
            try {
                val bible = rewrites.bible(project)
                // Cold start: read the earlier chapters into the sheets before rewriting this one.
                val builtSoFar = bible.built()
                // Fetch the earlier chapters the memory needs but the vault doesn't have yet.
                // How far back the memory reads: the last N chapters, or every chapter from the first.
                val firstToRead = if (settings.memoryDepth <= 0) 0 else maxOf(0, index - settings.memoryDepth)
                if (chapter.remote) {
                    val missing = (firstToRead until index).filter {
                        chapters[it].uri !in builtSoFar && !vault.has(project, chapters[it])
                    }
                    if (missing.isNotEmpty()) {
                        val result = withContext(Dispatchers.IO) {
                            downloadChapters(project, missing, vault, null, java.time.LocalDate.now().toString()) {
                                rewriteStatus = "DOWNLOAD ${it.done}/${it.total}"
                            }
                        }
                        if (result.failed > 0 && result.message?.startsWith("Paused") == true) {
                            throw java.io.IOException(result.message)
                        }
                    }
                }
                val todo = (firstToRead until index).mapNotNull { i ->
                    val c = chapters[i]
                    if (c.uri in builtSoFar) return@mapNotNull null
                    val text = withContext(Dispatchers.IO) {
                        runCatching {
                            if (c.remote) vault.read(project, c) else ChapterSource.read(context, Uri.parse(c.uri))
                        }.getOrNull()
                    }
                    text?.let { Triple(i, c, it) }
                }
                todo.forEachIndexed { n, (i, c, text) ->
                    rewriteStatus = "SHEETS ${n + 1}/${todo.size}"
                    val updates = RewriteClient.buildSheets(settings, c.number(i), c.title, text) { rewriteChars = it }
                    withContext(Dispatchers.IO) { bible.merge(updates); bible.markBuilt(c.uri) }
                }
                rewriteStatus = null
                rewriteChars = 0
                val md = MarkdownWriter.write(source)
                val previousTail = chapters.getOrNull(index - 1)?.let { c ->
                    withContext(Dispatchers.IO) {
                        runCatching {
                            if (c.remote) vault.read(project, c) else ChapterSource.read(context, Uri.parse(c.uri))
                        }.getOrNull()
                    }?.let { RewriteClient.tailWords(it) }
                }
                val result = RewritePipeline.rewrite(
                    settings, chapter.number(index), chapter.title, md, previousNote,
                    withContext(Dispatchers.IO) { bible.context(md) },
                    previousTail,
                    onStage = { rewriteStatus = it },
                ) { rewriteChars = it }
                withContext(Dispatchers.IO) {
                    rewrites.write(project, chapter, result)
                    bible.merge(result.sheets)
                    bible.markBuilt(chapter.uri)
                }
                val parsed = MarkdownParser.parse(result.chapter, markdown = true).withoutOpeningTitle(chapter.title)
                // The reader may have moved on while the answer was coming back.
                if (chapters.getOrNull(index) == chapter) {
                    if (!showRewrite) originalPos = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                    rewriteBlocks = parsed
                    showRewrite = true
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
            indexEnabled = chapters.isNotEmpty(),
        )
        ChapterRail(
            currentIndex = index,
            chapterCount = total,
        )
        RewriteTabs(
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
                                BlockView(block)
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

@Composable
private fun ReaderTopBar(
    bookTitle: String,
    onBack: () -> Unit,
    onIndex: () -> Unit,
    indexEnabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
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
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )

        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(enabled = indexEnabled, onClick = onIndex)
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

@Composable
private fun ChapterRail(currentIndex: Int, chapterCount: Int) {
    val fraction = if (chapterCount > 0) {
        ((currentIndex + 1).toFloat() / chapterCount).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(1.dp)
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
                .padding(start = ScreenPad, end = ScreenPad, top = 15.dp, bottom = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Pager(
                    direction = ArrowDirection.Left,
                    label = "Previous",
                    arrowFirst = true,
                    enabled = index > 0,
                    onClick = onPrevious,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.Medium)) {
                            append((index + 1).toString().padStart(2, '0'))
                        }
                        withStyle(SpanStyle(color = scheme.onSurfaceVariant)) {
                            append(" / ")
                            append(total.toString().padStart(2, '0'))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                )
                if (chapterPercent != null) {
                    Text(
                        text = "$chapterPercent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.semantics {
                            contentDescription = "$chapterPercent percent of chapter"
                        },
                    )
                }
            }

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Pager(
                    direction = ArrowDirection.Right,
                    label = "Next",
                    arrowFirst = false,
                    enabled = index < total - 1,
                    onClick = onNext,
                )
            }
        }
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
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 2.dp),
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

/** Drops a leading Markdown heading when it only repeats the chapter's filename title. */
private fun List<Block>.withoutOpeningTitle(title: String): List<Block> {
    val first = firstOrNull() as? Block.Heading ?: return this
    if (first.level > 2) return this

    val heading = normalizeHeading(first.text)
    val expected = normalizeHeading(title)
    val genericChapter = Regex("^chapter\\s+\\d+$").matches(heading)
    return if ((expected.isNotEmpty() && heading == expected) || genericChapter) {
        drop(1)
    } else {
        this
    }
}

private fun normalizeHeading(value: String): String =
    value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()


/** ORIGINAL | REWRITTEN tabs under the chapter rail; the gear opens the rewrite settings. */
@Composable
private fun RewriteTabs(
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
    fun Tab(label: String, selected: Boolean, accent: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Box(
            modifier = modifier
                .height(40.dp)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { role = Role.Tab },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
                    accent -> scheme.primary
                    selected -> scheme.onSurface
                    else -> scheme.onSurfaceVariant
                },
            )
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(scheme.primary),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Tab("ORIGINAL", !showRewrite, false, true, Modifier.weight(1f), onOriginal)
        Tab(
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
        Tab("⚙", false, false, true, Modifier.width(56.dp), onSettings)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
    if (!rewriting && showRewrite && hasRewrite) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (confirmAgain) "Replace this rewrite with a new one?" else "Rewritten version",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .height(36.dp)
                    .clickable(enabled = canStart) {
                        if (confirmAgain) {
                            confirmAgain = false
                            onRewriteAgain()
                        } else {
                            confirmAgain = true
                        }
                    }
                    .padding(start = 12.dp)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (confirmAgain) "TAP AGAIN TO REPLACE" else "REWRITE AGAIN",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (canStart) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
    if (rewriting) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = listOfNotNull(status, words.takeIf { it > 0 }?.let { "$it words" }).joinToString(" · ")
                    .ifEmpty { "Starting the rewrite. You can keep reading the original." },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.height(36.dp).clickable(onClick = onStop).padding(start = 12.dp), contentAlignment = Alignment.Center) {
                Text("STOP", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
            }
        }
    }
}
