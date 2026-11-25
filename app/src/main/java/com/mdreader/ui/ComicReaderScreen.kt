package com.mdreader.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mdreader.data.Chapter
import com.mdreader.data.Progress
import com.mdreader.data.Project
import com.mdreader.data.comics.ComicCache
import com.mdreader.data.comics.ComicException
import com.mdreader.data.comics.ComicSource
import com.mdreader.data.remapTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The comic reader in the Kuro skin: one continuous vertical strip of page images on pure
 * black, edge to edge, with floating translucent bars for back, index and chapter steps.
 * No paged mode, no tap zones; position is the chapter plus the page at the top of the screen.
 */
@OptIn(FlowPreview::class, ExperimentalMaterial3Api::class)
@Composable
fun ComicReaderScreen(
    project: Project,
    source: ComicSource,
    initialProgress: Progress?,
    initialZoom: Boolean = false,
    onModeChange: (Boolean) -> Unit = {},
    onBack: () -> Unit,
    onProgress: (Progress) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val chapters = project.chapters
    val start = remember(project.id) { initialProgress?.remapTo(chapters) }
    var index by rememberSaveable(project.id) {
        mutableIntStateOf((start?.chapterIndex ?: 0).coerceIn(0, chapters.lastIndex.coerceAtLeast(0)))
    }
    var pages by remember(project.id) { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember(project.id) { mutableStateOf(true) }
    var error by remember(project.id) { mutableStateOf<String?>(null) }
    var attempt by remember(project.id) { mutableIntStateOf(0) }
    var showChapters by remember { mutableStateOf(false) }
    var zoomPage by remember { mutableStateOf<Int?>(null) }
    // The page the zoom view should open on when a new chapter's pages arrive.
    var zoomPendingPage by remember { mutableStateOf<Int?>(null) }
    // The view this comic was last read in: the strip, or the zoom pager.
    var zoomMode by rememberSaveable(project.id) { mutableStateOf(initialZoom) }
    var restoredPage by remember(project.id) { mutableStateOf<Int?>(null) }
    var pendingPage by remember(project.id) { mutableStateOf(start?.scrollIndex) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(project.id, index, attempt) {
        loading = true
        error = null
        pages = emptyList()
        val chapter = chapters.getOrNull(index)
        if (chapter == null) {
            loading = false
            return@LaunchedEffect
        }
        try {
            pages = withContext(Dispatchers.IO) { ComicCache.pages(context, source, chapter.uri) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = (e as? ComicException)?.message ?: "Couldn't load this chapter."
        } finally {
            loading = false
        }
    }

    // Put the saved page back once its chapter has arrived; otherwise start at the first page.
    LaunchedEffect(pages, index) {
        if (pages.isEmpty()) return@LaunchedEffect
        val restore = pendingPage
        pendingPage = null
        val target = (restore ?: 0).coerceIn(0, pages.lastIndex)
        restoredPage = target
        listState.scrollToItem(target + 1)
    }

    // A comic last read in the zoom view reopens in it, at the page reading stopped on; a
    // chapter stepped inside the zoom view lands on its first page the same way.
    LaunchedEffect(pages) {
        if (pages.isNotEmpty() && zoomMode && zoomPage == null) {
            val target = (zoomPendingPage ?: restoredPage ?: 0).coerceIn(0, pages.lastIndex)
            zoomPendingPage = null
            zoomPage = target
        }
    }

    // A page is "read" when it reaches the top; the header item shifts pages by one.
    val firstPage by remember(listState) {
        derivedStateOf {
            (listState.firstVisibleItemIndex - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(project.id, index) {
        snapshotFlow { firstPage to listState.firstVisibleItemScrollOffset }
            .debounce(500)
            .collect {
                if (!loading && error == null) {
                    onProgress(
                        Progress(
                            chapterIndex = index,
                            scrollIndex = it.first,
                            chapterKey = chapters.getOrNull(index)?.uri,
                        ),
                    )
                }
            }
    }

    // Warm a window ahead of the screen, in parallel, and near the end the next chapter's page
    // list and its first pages too, so NEXT lands already loaded.
    LaunchedEffect(pages, index, firstPage) {
        if (pages.isEmpty()) return@LaunchedEffect
        val page = firstPage
        coroutineScope {
            (page + 1..minOf(page + 8, pages.lastIndex)).forEach { i ->
                pages.getOrNull(i)?.let { url ->
                    launch { ComicCache.bitmap(context, source, url, 1440) }
                }
            }
        }
        if (page >= pages.lastIndex - 2) {
            chapters.getOrNull(index + 1)?.let { next ->
                val nextPages = runCatching { ComicCache.pages(context, source, next.uri) }
                    .getOrNull()
                    .orEmpty()
                coroutineScope {
                    nextPages.take(5).forEach { url ->
                        launch { ComicCache.bitmap(context, source, url, 1440) }
                    }
                }
            }
        }
    }

    fun currentProgress(): Progress = Progress(
        chapterIndex = index,
        scrollIndex = firstPage,
        chapterKey = chapters.getOrNull(index)?.uri,
    )

    fun leave() {
        onProgress(currentProgress())
        onBack()
    }

    fun goTo(target: Int) {
        val clamped = target.coerceIn(0, chapters.lastIndex.coerceAtLeast(0))
        if (clamped == index) return
        pendingPage = 0
        index = clamped
    }

    // The reader can force landscape for wide panels; leaving hands the choice back to the system.
    val activityContext = LocalContext.current
    var landscape by rememberSaveable(project.id) { mutableStateOf(false) }
    LaunchedEffect(landscape) {
        activityContext.findActivity()?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(project.id) {
        onDispose {
            activityContext.findActivity()?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    ComicSystemBars()
    BackHandler(enabled = zoomPage == null) { leave() }

    // The strip and the floating bars share one stage; the bars dissolve into the pages.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComicSkin.PageStage)
            .statusBarsPadding(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                error != null -> ComicMessage(
                    title = "Couldn't load this chapter",
                    body = error.orEmpty(),
                    action = "Try again",
                    onAction = { attempt++ },
                )

                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = ComicSkin.Accent,
                        strokeWidth = 1.5.dp,
                    )
                }

                pages.isEmpty() -> ComicMessage(
                    title = "No pages",
                    body = "This chapter came back empty.",
                    action = "Try again",
                    onAction = { attempt++ },
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "opening") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ScreenPad)
                                .padding(top = 72.dp, bottom = 22.dp),
                        ) {
                            Micro(
                                text = "Chapter ${(index + 1).toString().padStart(2, '0')}",
                                color = ComicSkin.Accent,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = chapters.getOrNull(index)?.title.orEmpty(),
                                style = MaterialTheme.typography.titleLarge,
                                color = ComicSkin.Ink,
                            )
                            Spacer(Modifier.height(6.dp))
                            Micro(text = "${pages.size} pages · vertical strip", color = ComicSkin.Sub)
                        }
                    }
                    itemsIndexed(pages) { pageIndex, url ->
                        ComicPage(
                            source = source,
                            url = url,
                            onDoubleTap = {
                                zoomMode = true
                                onModeChange(true)
                                zoomPage = pageIndex
                            },
                        )
                    }
                    item(key = "end") {
                        // The chapter's sign-off: a vermilion tick, the issue number, and the
                        // step buttons as real controls — ghost for back, filled paper for next.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ScreenPad)
                                .padding(top = 30.dp, bottom = 118.dp)
                                .padding(horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.width(28.dp).height(2.dp).background(ComicSkin.Accent))
                            Spacer(Modifier.height(16.dp))
                            Micro(text = "Chapter ${(index + 1).toString().padStart(3, '0')}", color = ComicSkin.Sub)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "End of chapter",
                                style = MaterialTheme.typography.titleLarge,
                                color = ComicSkin.Ink,
                            )
                            Spacer(Modifier.height(22.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (index > 0) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .border(1.dp, ComicSkin.LineSoft)
                                            .clickable { goTo(index - 1) }
                                            .padding(vertical = 15.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "PREVIOUS",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = ComicSkin.Sub,
                                        )
                                    }
                                }
                                if (index < chapters.lastIndex) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(ComicSkin.Ink)
                                            .clickable { goTo(index + 1) }
                                            .padding(vertical = 15.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "NEXT CHAPTER",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = ComicSkin.Stage,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // The floating top bar: back, the comic's name, the chapter index.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.92f),
                        0.55f to Color.Black.copy(alpha = 0.5f),
                        1f to Color.Transparent,
                    ),
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(start = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = { leave() })
                        .semantics {
                            contentDescription = "Back to library"
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    QuietArrow(direction = ArrowDirection.Left, color = ComicSkin.Ink)
                }
                Text(
                    text = project.name.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = ComicSkin.Sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clickable(enabled = pages.isNotEmpty()) {
                            zoomMode = true
                            onModeChange(true)
                            zoomPage = firstPage
                        }
                        .border(1.dp, ComicSkin.LineSoft)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics {
                            contentDescription = "Zoom into pages"
                            role = Role.Button
                        },
                ) {
                    Text(
                        text = "ZOOM",
                        style = MaterialTheme.typography.labelSmall,
                        color = ComicSkin.Ink,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clickable { landscape = !landscape }
                        .border(1.dp, ComicSkin.LineSoft)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics {
                            contentDescription = if (landscape) "Back to portrait" else "Rotate to landscape"
                            role = Role.Button
                        },
                ) {
                    Text(
                        text = "ROTATE",
                        style = MaterialTheme.typography.labelSmall,
                        color = ComicSkin.Ink,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clickable(enabled = chapters.isNotEmpty()) { showChapters = true }
                        .border(1.dp, ComicSkin.LineSoft)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics {
                            contentDescription = "Open chapter index"
                            role = Role.Button
                        },
                ) {
                    Text(
                        text = "INDEX",
                        style = MaterialTheme.typography.labelSmall,
                        color = ComicSkin.Ink,
                    )
                }
            }
        }

        // The floating footer: chapter steps and the page counter, over the artwork.
        if (chapters.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Black.copy(alpha = 0.7f),
                            1f to Color.Black.copy(alpha = 0.92f),
                        ),
                    )
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (index > 0) {
                            Row(
                                modifier = Modifier
                                    .height(48.dp)
                                    .clickable { goTo(index - 1) }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                QuietArrow(direction = ArrowDirection.Left, color = ComicSkin.Sub)
                                Spacer(Modifier.width(8.dp))
                                Micro(text = "Prev", color = ComicSkin.Sub)
                            }
                        }
                    }
                    Text(
                        text = if (pages.isEmpty()) {
                            "${index + 1} / ${chapters.size}"
                        } else {
                            "${index + 1} / ${chapters.size} · page ${firstPage + 1}/${pages.size}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = ComicSkin.Sub,
                        modifier = Modifier.semantics {
                            contentDescription = "Chapter ${index + 1} of ${chapters.size}"
                        },
                    )
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (index < chapters.lastIndex) {
                            Row(
                                modifier = Modifier
                                    .height(48.dp)
                                    .clickable { goTo(index + 1) }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Micro(text = "Next", color = ComicSkin.Accent)
                                Spacer(Modifier.width(8.dp))
                                QuietArrow(direction = ArrowDirection.Right, color = ComicSkin.Accent)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    // The zoom view: the comic's other reading mode. Entered by the ZOOM chip or a double-tap,
    // it is remembered as the view this comic reads in; the STRIP chip switches back for good,
    // while ✕ and back return to the strip once — or leave the reader outright when the zoom
    // view is the comic's remembered mode. Pages swiped here are saved as reading position.
    val zooming = zoomPage
    if (zooming != null && pages.isNotEmpty()) {
        ComicZoomOverlay(
            source = source,
            pages = pages,
            initialPage = zooming,
            chapters = chapters,
            index = index,
            modeZoom = zoomMode,
            onStrip = { page ->
                zoomMode = false
                onModeChange(false)
                zoomPage = null
                scope.launch { listState.scrollToItem(page + 1) }
            },
            onClose = { page ->
                zoomPage = null
                scope.launch {
                    listState.scrollToItem(page + 1)
                    if (zoomMode) leave()
                }
            },
            onChapter = { target ->
                zoomPage = null
                zoomPendingPage = 0
                goTo(target)
            },
            onZoomProgress = { page ->
                zoomPage = page
                onProgress(
                    Progress(
                        chapterIndex = index,
                        scrollIndex = page,
                        chapterKey = chapters.getOrNull(index)?.uri,
                    ),
                )
            },
        )
    }

    if (showChapters) {
        ModalBottomSheet(
            onDismissRequest = { showChapters = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RectangleShape,
            containerColor = ComicSkin.Stage,
            scrimColor = Color.Black.copy(alpha = 0.55f),
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenPad, vertical = 20.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "Chapters",
                        style = MaterialTheme.typography.titleLarge,
                        color = ComicSkin.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    Micro(text = "${chapters.size}", color = ComicSkin.Sub)
                }
                Hairline(color = ComicSkin.Line)
                LazyColumn(modifier = Modifier.fillMaxWidth().height(480.dp)) {
                    items(chapters.size) { i ->
                        val chapter = chapters[i]
                        val on = i == index
                        Column(modifier = Modifier.padding(horizontal = ScreenPad)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showChapters = false
                                        goTo(i)
                                    }
                                    .padding(vertical = 13.dp),
                            ) {
                                Text(
                                    text = "#${(i + 1).toString().padStart(3, '0')}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (on) ComicSkin.Accent else ComicSkin.Sub,
                                    modifier = Modifier.width(52.dp).padding(top = 8.dp),
                                )
                                Text(
                                    text = chapter.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (on) ComicSkin.Accent else ComicSkin.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Hairline(color = ComicSkin.Line)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComicMessage(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPad, vertical = 48.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = ComicSkin.Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = ComicSkin.Sub,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = action.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = ComicSkin.Accent,
            modifier = Modifier.clickable(onClick = onAction).padding(vertical = 8.dp),
        )
    }
}

/**
 * The zoom view: the chapter's pages in a horizontal pager, one page filling the screen,
 * pinch to zoom into a panel, pan around, double-tap for 1x / 2.5x, swipe between pages.
 * It is a full reading mode: chapter steps in the footer, pages reported as they're swiped.
 */
@Composable
private fun ComicZoomOverlay(
    source: ComicSource,
    pages: List<String>,
    initialPage: Int,
    chapters: List<Chapter>,
    index: Int,
    modeZoom: Boolean,
    onStrip: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onChapter: (Int) -> Unit,
    onZoomProgress: (Int) -> Unit,
) {
    val chapterMark = chapters.getOrNull(index)?.uri.orEmpty()
    key(chapterMark) {
        val pagerState = rememberPagerState(
            initialPage = initialPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
        ) { pages.size }

        // Every swiped page is reading position, saved as it happens, not just on close.
        LaunchedEffect(pagerState.currentPage) {
            onZoomProgress(pagerState.currentPage)
        }

        BackHandler { onClose(pagerState.currentPage) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ComicSkin.PageStage)
                .statusBarsPadding(),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ZoomableComicPage(source = source, url = pages[page])
            }

            // The one bar: close on the left, the strip switch, the page counter on the right.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.92f),
                            0.55f to Color.Black.copy(alpha = 0.5f),
                            1f to Color.Transparent,
                        ),
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(start = 8.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { onClose(pagerState.currentPage) }
                            .semantics {
                                contentDescription = if (modeZoom) "Close reader" else "Back to the strip"
                                role = Role.Button
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "✕",
                            style = MaterialTheme.typography.labelLarge,
                            color = ComicSkin.Ink,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clickable { onStrip(pagerState.currentPage) }
                            .border(1.dp, ComicSkin.LineSoft)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics {
                                contentDescription = "Read as a vertical strip"
                                role = Role.Button
                            },
                    ) {
                        Text(
                            text = "STRIP",
                            style = MaterialTheme.typography.labelSmall,
                            color = ComicSkin.Ink,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Micro(text = "Page ${pagerState.currentPage + 1} / ${pages.size}", color = ComicSkin.Sub)
                }
            }

            // Chapter steps, the same floating bar the strip has.
            if (chapters.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.45f to Color.Black.copy(alpha = 0.7f),
                                1f to Color.Black.copy(alpha = 0.92f),
                            ),
                        )
                        .navigationBarsPadding(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (index > 0) {
                                Row(
                                    modifier = Modifier
                                        .height(48.dp)
                                        .clickable { onChapter(index - 1) }
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    QuietArrow(direction = ArrowDirection.Left, color = ComicSkin.Sub)
                                    Spacer(Modifier.width(8.dp))
                                    Micro(text = "Prev", color = ComicSkin.Sub)
                                }
                            }
                        }
                        Micro(text = "${index + 1} / ${chapters.size}", color = ComicSkin.Sub)
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            if (index < chapters.lastIndex) {
                                Row(
                                    modifier = Modifier
                                        .height(48.dp)
                                        .clickable { onChapter(index + 1) }
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Micro(text = "Next", color = ComicSkin.Accent)
                                    Spacer(Modifier.width(8.dp))
                                    QuietArrow(direction = ArrowDirection.Right, color = ComicSkin.Accent)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/**
 * One zoomable page. The gesture handler consumes only while zoomed, so a one-finger swipe at
 * 1x still turns the pager; two fingers pinch anywhere, and the pan is clamped to the page.
 */
@Composable
private fun ZoomableComicPage(source: ComicSource, url: String) {
    val context = LocalContext.current.applicationContext
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        // Panels need real pixels: decode wider than the strip's 1440 for readable zoom.
        image = ComicCache.bitmap(context, source, url, maxWidth = 2048)
    }
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var bounds by remember(url) { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The page sits above the taskbar, never behind it — pan bounds follow the box,
            // so a zoomed panel stops exactly where the system area begins.
            .navigationBarsPadding()
            .onSizeChanged { bounds = it }
            .pointerInput(url) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val active = scale > 1f || zoomChange != 1f
                        if (active) {
                            scale = (scale * zoomChange).coerceIn(1f, 6f)
                            if (scale > 1f) {
                                val maxX = bounds.width * (scale - 1f) / 2f
                                val maxY = bounds.height * (scale - 1f) / 2f
                                val pan = event.calculatePan()
                                offset = Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            } else {
                                offset = Offset.Zero
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(url) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.4f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = ComicSkin.Accent,
                strokeWidth = 1.5.dp,
            )
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}
