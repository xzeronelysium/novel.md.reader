package com.mdreader.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.LruCache
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
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
import com.mdreader.data.Chapter
import com.mdreader.data.ChapterSource
import com.mdreader.data.Progress
import com.mdreader.data.Project
import com.mdreader.data.Vault
import com.mdreader.data.online.FreeWebNovelClient
import com.mdreader.data.online.OnlineException
import com.mdreader.data.remapTo
import com.mdreader.md.Block
import com.mdreader.md.MarkdownParser
import com.mdreader.md.charCount
import com.mdreader.md.withoutOpeningTitle
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
    val scope = rememberCoroutineScope()
    var pendingRestore by remember(project.id) { mutableStateOf(startProgress) }
    var attempt by remember(project.id) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    // A State, not a delegated value: the rail reads it while drawing, so scrolling a chapter
    // never recomposes the footer and the top bar.
    val chapterPercent = remember(listState) {
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

    // New text fades in rather than snapping under the eye; declared here because the reading
    // column only exists once a chapter has loaded.
    val contentAlpha by animateFloatAsState(
        targetValue = if (loading) 0f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "chapter",
    )

    // Parsed chapters kept warm: returning to one, or turning to a prefetched one, is instant.
    // Bounded by the characters held rather than by a count, so six very long chapters cannot
    // quietly hold tens of megabytes.
    val chapterCache = remember(project.id) {
        object : LruCache<String, List<Block>>(CHAPTER_CACHE_CHARS) {
            override fun sizeOf(key: String, value: List<Block>): Int =
                value.sumOf { it.charCount() }.coerceAtLeast(1)
        }
    }
    // One read per chapter even when a prefetch and the reader ask for it at the same moment.
    val inFlight = remember(project.id) { ConcurrentHashMap<String, Deferred<Result<List<Block>>>>() }

    /** Reads and parses one chapter: the vault or the site for online, the SAF folder for local. */
    suspend fun readChapter(chapter: Chapter): Result<List<Block>> = try {
        if (chapter.remote) {
            val saved = withContext(Dispatchers.IO) {
                try {
                    vault.read(project, chapter)?.let { text ->
                        MarkdownParser.parse(text, markdown = true).withoutOpeningTitle(chapter.title)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }?.takeIf { it.isNotEmpty() }
            Result.success(saved ?: FreeWebNovelClient.loadChapter(chapter.uri))
        } else {
            Result.success(
                withContext(Dispatchers.IO) {
                    val text = ChapterSource.read(context, chapter.uri.toUri())
                    MarkdownParser.parse(text, chapter.isMarkdown).withoutOpeningTitle(chapter.title)
                },
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** [readChapter], but a second caller joins the first read instead of fetching it again. */
    suspend fun readOnce(chapter: Chapter): Result<List<Block>> {
        val work = inFlight.computeIfAbsent(chapter.uri) {
            scope.async(Dispatchers.IO) { readChapter(chapter) }
        }
        // Registered outside the map's mapping function; repeat callers just install a no-op.
        work.invokeOnCompletion { inFlight.remove(chapter.uri, work) }
        return work.await()
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

        val cached = chapterCache.get(chapter.uri)
        val outcome = if (cached != null) Result.success(cached) else readOnce(chapter)
        outcome.fold(
            onSuccess = { loaded ->
                blocks = loaded
                chapterCache.put(chapter.uri, loaded)
            },
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

        // Read the neighbours ahead once this chapter has settled. Next may come from the site;
        // the previous one only when it is already on disk, so flipping back cannot hit the site.
        if (outcome.isSuccess) {
            delay(700)
            val next = chapters.getOrNull(index + 1)
            // A chapter the reader has not asked for yet is not worth mobile data.
            val nextIsWorthFetching = next != null && (!next.remote || !metered(context))
            if (next != null && nextIsWorthFetching && chapterCache.get(next.uri) == null) {
                readOnce(next).getOrNull()?.let { chapterCache.put(next.uri, it) }
            }
            val previous = chapters.getOrNull(index - 1)
            val onDisk = previous != null &&
                (!previous.remote || withContext(Dispatchers.IO) { vault.has(project, previous) })
            if (previous != null && onDisk && chapterCache.get(previous.uri) == null) {
                readOnce(previous).getOrNull()?.let { chapterCache.put(previous.uri, it) }
            }
        }
    }

    // Restore the saved scroll position once per visit, otherwise start at the top.
    LaunchedEffect(blocks) {
        if (blocks.isEmpty()) return@LaunchedEffect
        val restore = pendingRestore
        if (restore != null && restore.chapterIndex == index) {
            pendingRestore = null
            val last = blocks.lastIndex
            listState.scrollToItem(restore.scrollIndex.coerceIn(0, last.coerceAtLeast(0)), restore.scrollOffset.coerceAtLeast(0))
        } else {
            listState.scrollToItem(0)
        }
    }

    /** The open chapter's place, read live from the list. */
    fun currentProgress(): Progress {
        return Progress(
            index, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset,
            chapters.getOrNull(index)?.uri,
        )
    }

    LaunchedEffect(project.id, index) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .debounce(500)
            .collect {
                // Ignore positions from the previous chapter while the new one loads, and before
                // the saved place has been restored (it would overwrite it with the top).
                if (!loading && pendingRestore?.chapterIndex != index) onProgress(currentProgress())
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
            percent = {
                if (loading || loadError != null || blocks.isEmpty()) 0 else chapterPercent.value ?: 0
            },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.TopCenter,
        ) {
            val message = loadError
            val chapter = chapters.getOrNull(index)
            when {
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
                            // A phone fills the width; a tablet keeps a readable measure (~66
                            // characters) instead of stretching every line across the screen.
                            modifier = Modifier
                                .fillMaxHeight()
                                .widthIn(max = 640.dp)
                                .fillMaxWidth()
                                .graphicsLayer { alpha = contentAlpha },
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
                            items(blocks) { block ->
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
                onPrevious = { goTo(index - 1) },
                onNext = { goTo(index + 1) },
            )
        }
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

/** Roughly how many parsed characters stay cached (about 3 MB of text plus block objects). */
private const val CHAPTER_CACHE_CHARS = 1_500_000

/** True when the active connection is metered, so a prefetch the reader didn't ask for is skipped. */
private fun metered(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
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
private fun ChapterRail(percent: () -> Int) {
    LinearProgressIndicator(
        progress = { percent().coerceIn(0, 100) / 100f },
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.outlineVariant,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
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
                    contentDescription = "Chapter ${index + 1} of $total"
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

