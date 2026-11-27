package com.mdreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import com.mdreader.data.parseChapterCount
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mdreader.data.Chapter
import com.mdreader.data.DeletePlan
import com.mdreader.data.DownloadRequest
import com.mdreader.data.estimatedBytes
import com.mdreader.data.estimatedDuration
import com.mdreader.data.formatBytes
import com.mdreader.data.planDelete
import com.mdreader.data.planDownload
import com.mdreader.data.DownloadProgress
import com.mdreader.data.Project
import com.mdreader.data.number
import com.mdreader.data.online.NovelDetails
import kotlinx.coroutines.launch

/** Everything the details screen shows. [project] is a preview unless [inLibrary]. */
data class NovelDetailState(
    val details: NovelDetails? = null,
    val project: Project? = null,
    val inLibrary: Boolean = false,
    /** Chapter to continue from, for a library novel that has been started. */
    val resumeIndex: Int? = null,
    val error: String? = null,
    /** A pull-to-check is running. */
    val refreshing: Boolean = false,
    /** Short outcome of the last check ("Up to date", "3 new chapters"), shown briefly. */
    val refreshResult: String? = null,
    /** The title, known from the search result or library before anything is fetched. */
    val title: String? = null,
    /** Live loading progress of the chapter list. */
    val pagesDone: Int = 0,
    val totalPages: Int = 0,
    val loadedChapters: Int = 0,
    val totalChapters: Int = 0,
)

/** The site's number at the end of a chapter path (`.../chapter-2343`). */
private val trailingDigits = Regex("(\\d+)$")

/** Index of chapter [number]: matched by the site's own numbering, else by position. */
private fun List<Chapter>.indexOfNumber(number: Int): Int? {
    val byNumber = indexOfFirst { chapter -> trailingDigits.find(chapter.uri)?.value?.toIntOrNull() == number }
    if (byNumber >= 0) return byNumber
    return (number - 1).takeIf { it in indices }
}

// Rows before the chapters in the lazy list: the header and the "Chapters" label.
private const val LEADING_ITEMS = 2

@OptIn(ExperimentalMaterial3Api::class)
/**
 * A novel's summary and chapter list, shown before anything is added to the
 * library. Tapping a chapter reads it straight from the site.
 */
@Composable
fun NovelDetailScreen(
    state: NovelDetailState,
    onBack: () -> Unit,
    onAuthor: () -> Unit,
    onRetry: () -> Unit,
    onRead: (Int) -> Unit,
    onContinue: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleOrder: () -> Unit,
    onRefresh: () -> Unit,
    downloaded: Set<String>,
    download: DownloadProgress,
    onDownload: (DownloadRequest) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownloads: (List<String>) -> Unit,
    sizeOf: (Collection<String>) -> Long,
) {
    val scheme = MaterialTheme.colorScheme
    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        val details = state.details
        val project = state.project
        when {
            state.error != null && (details == null || project == null) -> Column(
                modifier = Modifier.padding(horizontal = ScreenPad, vertical = 24.dp),
            ) {
                BackArrow(onBack)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "TRY AGAIN",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    modifier = Modifier.clickable(onClick = onRetry).padding(vertical = 8.dp),
                )
            }

            details == null || project == null -> LoadingPage(state = state, onBack = onBack)

            else -> {
                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val newestFirst = project.newestFirst
                val shownChapters = remember(project.chapters, newestFirst) {
                    if (newestFirst) project.chapters.asReversed() else project.chapters
                }
                var pendingScroll by remember { mutableStateOf(false) }
                LaunchedEffect(newestFirst) {
                    if (pendingScroll) {
                        listState.scrollToItem(1)
                        pendingScroll = false
                    }
                }
                val pullState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    state = pullState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = {
                        CheckStrip(
                            fraction = pullState.distanceFraction,
                            refreshing = state.refreshing,
                            result = state.refreshResult,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    },
                ) {
                // The page slides down to make room for the strip rather than hiding under it.
                // The drag distance is read inside the layer block, so pulling redraws the list
                // instead of recomposing it on every pixel of movement.
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val shown = if (state.refreshing || state.refreshResult != null) {
                                1f
                            } else {
                                pullState.distanceFraction.coerceIn(0f, 1f)
                            }
                            translationY = shown * 44.dp.toPx()
                        }
                        .navigationBarsPadding(),
                ) {
                    item {
                        Header(
                            details = details,
                            project = project,
                            inLibrary = state.inLibrary,
                            resumeIndex = state.resumeIndex,
                            onBack = onBack,
                            onAuthor = onAuthor,
                            onRead = onRead,
                            onContinue = onContinue,
                            onToggleLibrary = onToggleLibrary,
                            downloaded = downloaded,
                            download = download,
                            onDownload = onDownload,
                            onCancelDownload = onCancelDownload,
                            onDeleteDownloads = onDeleteDownloads,
                            sizeOf = sizeOf,
                            onGoTo = { number ->
                                val index = project.chapters.indexOfNumber(number)
                                if (index != null) {
                                    // The list may be shown reversed; scroll to where the chapter sits on screen.
                                    val shown = if (newestFirst) project.chapters.lastIndex - index else index
                                    scope.launch { listState.animateScrollToItem(LEADING_ITEMS + shown) }
                                }
                                index
                            },
                            onOpenNumber = { number ->
                                project.chapters.indexOfNumber(number)?.let(onRead)
                            },
                        )
                    }
                    item {
                        Column(modifier = Modifier.padding(horizontal = ScreenPad)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 26.dp, bottom = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Micro(text = "Chapters · ${project.totalChapters ?: project.chapters.size}")
                                OrderChip(
                                    newestFirst = newestFirst,
                                    onClick = {
                                        // Remember to land on the first row of the reversed list.
                                        pendingScroll = listState.firstVisibleItemIndex > 1
                                        onToggleOrder()
                                    },
                                )
                            }
                            Hairline()
                        }
                    }
                    itemsIndexed(shownChapters, key = { _, c -> c.uri }) { shownIndex, chapter ->
                        // Position in the novel, whichever way the list is displayed.
                        val index = if (newestFirst) project.chapters.lastIndex - shownIndex else shownIndex
                        val resume = state.resumeIndex
                        val current = resume == index
                        val read = resume != null && index < resume
                        Column(modifier = Modifier.padding(horizontal = ScreenPad)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onRead(index) }
                                    .padding(vertical = 14.dp),
                            ) {
                                Text(
                                    text = chapter.number(index).toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (current) scheme.primary else scheme.onSurfaceVariant,
                                    // 48 dp puts titles on the title block's x; 9 dp centres the number
                                    // on the first 32 sp title line.
                                    modifier = Modifier.width(48.dp).padding(top = 9.dp),
                                )
                                Text(
                                    text = chapter.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (read) scheme.onSurfaceVariant else scheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (chapter.uri in downloaded) {
                                    // A vermilion square: saved on this device, readable offline.
                                    Spacer(Modifier.width(12.dp))
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 14.dp)
                                            .size(5.dp)
                                            .background(scheme.primary)
                                            .semantics { contentDescription = "Downloaded" },
                                    )
                                }
                            }
                            Hairline()
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
internal fun BackArrow(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clickable(onClick = onBack)
            .semantics {
                contentDescription = "Back"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        QuietArrow(direction = ArrowDirection.Left)
    }
}

/** The strip that slides down while pulling, then reports the outcome of the check. */
@Composable
internal fun CheckStrip(
    fraction: Float,
    refreshing: Boolean,
    result: String?,
    modifier: Modifier = Modifier,
    busyText: String = "Checking…",
    pullText: String = "Pull to check for new chapters",
    releaseText: String = "Release to check",
) {
    val scheme = MaterialTheme.colorScheme
    val height = 44.dp
    val visible = refreshing || result != null
    val shown = if (visible) 1f else fraction.coerceIn(0f, 1f)
    if (shown <= 0f) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { translationY = (shown - 1f) * height.toPx() }
            .background(scheme.background),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = scheme.primary,
                strokeWidth = 1.5.dp,
            )
            Spacer(Modifier.width(10.dp))
        }
        Micro(
            text = when {
                refreshing -> busyText
                result != null -> result
                fraction >= 1f -> releaseText
                else -> pullText
            },
            accent = result != null && !refreshing,
        )
    }
}

@Composable
private fun Header(
    details: NovelDetails,
    project: Project,
    inLibrary: Boolean,
    resumeIndex: Int?,
    onBack: () -> Unit,
    onAuthor: () -> Unit,
    onRead: (Int) -> Unit,
    onContinue: () -> Unit,
    onToggleLibrary: () -> Unit,
    downloaded: Set<String>,
    download: DownloadProgress,
    onDownload: (DownloadRequest) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownloads: (List<String>) -> Unit,
    sizeOf: (Collection<String>) -> Long,
    onGoTo: (Int) -> Int?,
    onOpenNumber: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var confirmRemove by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var expanded by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf("") }
    var jumpMissing by remember { mutableStateOf(false) }
    val total = project.totalChapters ?: project.chapters.size

    fun goTo() {
        val number = jump.trim().toIntOrNull() ?: return
        jumpMissing = onGoTo(number) == null
        if (!jumpMissing) {
            keyboard?.hide()
            focus.clearFocus()
        }
    }

    val cover = rememberCover(details.cover.ifEmpty { project.coverUrl.orEmpty() })
    // The cover only lends its colours: a frosted field behind the header, fading out above the
    // button, and a fainter wash of it carrying on behind the summary.
    Column(modifier = Modifier.coverField(cover, FieldFade.Page, alpha = 0.4f)) {
        Column(modifier = Modifier.coverField(cover, FieldFade.Detail)) {
            TitleBlock(onBack = onBack, title = details.title) {
                if (details.author.isNotEmpty()) {
                    if (details.authorPath.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Micro(text = details.author)
                    } else {
                        // Padded to a taller target, with the gap above reduced to match.
                        Spacer(Modifier.height(2.dp))
                        Micro(
                            text = details.author,
                            accent = true,
                            modifier = Modifier
                                .clickable(onClick = onAuthor)
                                .padding(vertical = 6.dp)
                                .semantics {
                                    contentDescription = "Novels by ${details.author}"
                                    role = Role.Button
                                },
                        )
                    }
                }
                if (details.rating.isNotEmpty()) {
                    // The linked author already ends in 6 dp of padding; the plain one does not.
                    Spacer(
                        Modifier.height(
                            when {
                                details.author.isEmpty() -> 8.dp
                                details.authorPath.isEmpty() -> 6.dp
                                else -> 0.dp
                            },
                        ),
                    )
                    RatingLine(details.rating, details.votes)
                }

            }

            if (details.genres.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.padding(horizontal = ScreenPad),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    details.genres.take(4).forEach { genre ->
                        Text(
                            text = genre.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier
                                .border(1.dp, scheme.outline)
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Hairline(modifier = Modifier.padding(horizontal = ScreenPad))
            Row(modifier = Modifier.padding(horizontal = ScreenPad).height(IntrinsicSize.Min)) {
                Stat("Chapters", "%,d".format(total), modifier = Modifier.weight(1f))
                Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
                Stat(
                    "Status",
                    details.status.ifEmpty { "—" },
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
                val read = resumeIndex?.let { ((it + 1) * 100 / total.coerceAtLeast(1)).coerceIn(1, 100) }
                Stat(
                    "Read",
                    read?.let { "$it%" } ?: "—",
                    accent = read != null,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
            }
            Hairline(modifier = Modifier.padding(horizontal = ScreenPad))
            Spacer(Modifier.height(if (cover != null) 50.dp else 0.dp))
        }

        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .padding(horizontal = ScreenPad)
                .fillMaxWidth()
                .background(scheme.onSurface)
                .clickable { if (resumeIndex != null) onContinue() else onRead(0) }
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (resumeIndex != null) "CONTINUE · CH. ${resumeIndex + 1}" else "READ FIRST",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.background,
                fontWeight = FontWeight.SemiBold,
            )
        }
        LibraryActions(
            inLibrary = inLibrary,
            chapters = project.chapters,
            downloaded = downloaded,
            resumeIndex = resumeIndex,
            download = download,
            sizeOf = sizeOf,
            // Removing a novel with downloads asks first: they leave the device with it.
            onToggleLibrary = { if (inLibrary && downloaded.isNotEmpty()) confirmRemove = true else onToggleLibrary() },
            onDownload = onDownload,
            onCancelDownload = onCancelDownload,
            onDeleteDownloads = onDeleteDownloads,
        )
        if (confirmRemove) {
            RemoveDialog(
                project = project,
                downloaded = downloaded.size,
                onDismiss = { confirmRemove = false },
                onConfirm = {
                    confirmRemove = false
                    onToggleLibrary()
                },
            )
        }

        if (details.synopsis.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = ScreenPad).padding(top = 6.dp)) {
                Micro(text = "Summary", accent = true)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = details.synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
                Spacer(Modifier.height(8.dp))
                Micro(
                    text = if (expanded) "Show less" else "Read more",
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }

        // Jump anywhere in a long list.
        Column(modifier = Modifier.padding(horizontal = ScreenPad).padding(top = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = jump,
                    onValueChange = { value ->
                        jump = value.filter { it.isDigit() }.take(6)
                        jumpMissing = false
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { goTo() }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { field ->
                        Box(modifier = Modifier.padding(vertical = 10.dp)) {
                            if (jump.isEmpty()) {
                                Text(
                                    text = "Go to chapter number",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                            field()
                        }
                    },
                )
                Text(
                    text = "GO",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    modifier = Modifier.clickable { goTo() }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                )
            }
            Hairline(color = if (jumpMissing) scheme.error else scheme.onSurface)
            if (jumpMissing) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "No chapter ${jump.trim()} in this novel.",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.error,
                )
            } else if (jump.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Micro(
                    text = "Open chapter ${jump.trim()}",
                    accent = true,
                    modifier = Modifier.clickable {
                        jump.trim().toIntOrNull()?.let { number ->
                            keyboard?.hide()
                            onOpenNumber(number)
                        }
                    },
                )
            }
        }
    }
}

private enum class DeleteKind { Read, All }

private fun chapterCount(n: Int) = if (n == 1) "1 chapter" else "%,d chapters".format(n)

/**
 * The row under Continue. Idle it is two outlined buttons, Download and the library toggle.
 * Tapping Download morphs that row in place into a stepper beside a split Download button, with a
 * row of presets under it that mirrors the two columns above. The trash arm on the split button
 * opens the Delete menu; deleting asks in the same footprint. While a download runs, Download is
 * its own progress bar and stop control. Outside the library "Add to library" takes the row.
 *
 * One grid throughout: 28 dp side margins, two 12 dp-separated columns, 48 dp tall controls.
 */
@Composable
private fun LibraryActions(
    inLibrary: Boolean,
    chapters: List<Chapter>,
    downloaded: Set<String>,
    resumeIndex: Int?,
    download: DownloadProgress,
    sizeOf: (Collection<String>) -> Long,
    onToggleLibrary: () -> Unit,
    onDownload: (DownloadRequest) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownloads: (List<String>) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<DownloadRequest>(DownloadRequest.Next(10)) }
    var confirm by remember { mutableStateOf<DeleteKind?>(null) }

    fun close() {
        open = false
        confirm = null
    }
    LaunchedEffect(download.running) { if (download.running) close() }
    // Back leaves the morphed row (and an open question) before it leaves the page.
    BackHandler(enabled = open || confirm != null) { close() }

    val plan = remember(chapters, downloaded, resumeIndex, request) {
        planDownload(chapters, downloaded, resumeIndex, request)
    }
    val remaining = remember(chapters, downloaded, resumeIndex) {
        planDownload(chapters, downloaded, resumeIndex, DownloadRequest.Next(Int.MAX_VALUE)).size
    }
    val deletable = remember(chapters, downloaded, resumeIndex) { planDelete(chapters, downloaded, resumeIndex) }

    Column(
        modifier = Modifier
            .padding(horizontal = ScreenPad)
            .padding(top = 12.dp)
            .animateContentSize(),
    ) {
        val kind = confirm
        when {
            !inLibrary -> OutlineButton(
                text = "+ ADD TO LIBRARY",
                description = "Add to library",
                onClick = onToggleLibrary,
                modifier = Modifier.fillMaxWidth(),
            )

            kind != null -> DeleteConfirm(
                kind = kind,
                deletable = deletable,
                sizeOf = sizeOf,
                onCancel = { confirm = null },
                onConfirm = { paths ->
                    onDeleteDownloads(paths)
                    close()
                },
            )

            open && !download.running -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stepper(
                        number = plan.size,
                        remaining = remaining,
                        canDecrease = plan.size > 1,
                        canIncrease = plan.size < remaining,
                        onStep = { delta ->
                            request = DownloadRequest.Next((plan.size + delta).coerceIn(1, maxOf(remaining, 1)))
                        },
                        onSet = { typed -> request = DownloadRequest.Next(typed) },
                        modifier = Modifier.weight(1f),
                    )
                    SplitDownload(
                        readout = "${formatBytes(estimatedBytes(plan.size))} · ${estimatedDuration(plan.size)}",
                        canDownload = plan.isNotEmpty(),
                        savedCount = downloaded.size,
                        deletable = deletable,
                        sizeOf = sizeOf,
                        onDownload = {
                            close()
                            onDownload(request)
                        },
                        onDelete = { confirm = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PresetGroup(
                        labels = listOf("10", "50"),
                        selected = when (request) {
                            DownloadRequest.Next(10) -> 0
                            DownloadRequest.Next(50) -> 1
                            else -> -1
                        },
                        onSelect = { request = DownloadRequest.Next(if (it == 0) 10 else 50) },
                        modifier = Modifier.weight(1f),
                    )
                    PresetGroup(
                        labels = listOf("UNREAD", "ALL"),
                        selected = when (request) {
                            DownloadRequest.Unread -> 0
                            DownloadRequest.All -> 1
                            else -> -1
                        },
                        onSelect = { request = if (it == 0) DownloadRequest.Unread else DownloadRequest.All },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (downloaded.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .clickable {
                                close()
                                onDownload(DownloadRequest.Refresh)
                            }
                            .semantics {
                                contentDescription = "Download the saved chapters again"
                                role = Role.Button
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Micro(text = "REFRESH ${downloaded.size} SAVED", color = scheme.primary)
                    }
                }
                // The way back to Download + In library, as wide as the two columns together.
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(36.dp)
                        .clickable { close() }
                        .semantics {
                            contentDescription = "Close download options"
                            role = Role.Button
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Micro(text = "Close", color = scheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = scheme.onSurface,
                    )
                }
            }

            else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (download.running) {
                    val fraction = if (download.total > 0) download.done.toFloat() / download.total else 0f
                    OutlineButton(
                        text = "${download.done} / ${download.total} · STOP",
                        description = "Stop downloading",
                        onClick = onCancelDownload,
                        modifier = Modifier.weight(1f),
                        fill = fraction,
                    )
                } else {
                    OutlineButton(
                        text = "DOWNLOAD",
                        description = "Download chapters",
                        onClick = { open = true },
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlineButton(
                    text = "✓ IN LIBRARY",
                    description = "In library, tap to remove",
                    onClick = onToggleLibrary,
                    modifier = Modifier.weight(1f),
                    soft = true,
                )
            }
        }
        download.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
        // With the summary's 6 dp, the same 22 dp gap the page had below its old text rows.
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * minus, the chapter count, plus: one 1 dp ink outline, 48 dp tall, hairline dividers. Tapping
 * the number turns it into a field for typing an exact amount; it is applied when you press Done,
 * tap minus or plus, or move on, and is clamped to what is left to save.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Stepper(
    number: Int,
    remaining: Int,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onStep: (Int) -> Unit,
    onSet: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    val bring = remember { BringIntoViewRequester() }

    fun commit() {
        if (!editing) return
        editing = false
        focused = false
        parseChapterCount(field.text, remaining)?.let(onSet)
        keyboard?.hide()
        focusManager.clearFocus()
    }

    LaunchedEffect(editing) { if (editing) focus.requestFocus() }
    // A preset or anything else changed the number under a half-typed one: drop what was typed.
    LaunchedEffect(number) {
        if (editing) {
            editing = false
            focused = false
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }

    Row(
        modifier = modifier
            .height(48.dp)
            .border(1.dp, scheme.onSurface)
            .bringIntoViewRequester(bring),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // While typing, minus and plus first apply what was typed instead of stepping from stale.
        StepButton(plus = false, enabled = canDecrease || editing, onStep = { if (editing) commit() else onStep(-1) })
        Box(Modifier.width(1.dp).height(24.dp).background(scheme.onSurface.copy(alpha = 0.3f)))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(enabled = remaining > 0 && !editing) {
                    val text = number.toString()
                    // Everything selected, so the first digit typed replaces the number.
                    field = TextFieldValue(text, TextRange(0, text.length))
                    editing = true
                }
                .semantics { contentDescription = "${chapterCount(number)} to download, tap to type a number" },
            contentAlignment = Alignment.Center,
        ) {
            val style = TextStyle(
                color = scheme.onSurface,
                fontFamily = FontFamily.Serif,
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
            )
            if (editing) {
                BasicTextField(
                    value = field,
                    onValueChange = { value -> field = value.copy(text = value.text.filter { it.isDigit() }.take(6)) },
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                focused = true
                                scope.launch { bring.bringIntoView() }
                            } else if (focused) {
                                commit()
                            }
                        },
                )
            } else {
                Text(text = "%,d".format(number), style = style, maxLines = 1)
            }
        }
        Box(Modifier.width(1.dp).height(24.dp).background(scheme.onSurface.copy(alpha = 0.3f)))
        StepButton(plus = true, enabled = canIncrease || editing, onStep = { if (editing) commit() else onStep(1) })
    }
}

/** A 48 dp target. Press steps once; hold repeats. */
@Composable
private fun StepButton(plus: Boolean, enabled: Boolean, onStep: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    Box(
        modifier = Modifier
            .width(48.dp)
            .fillMaxHeight()
            .alpha(if (enabled) 1f else 0.3f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        val repeat = scope.launch {
                            step()
                            delay(400)
                            while (true) {
                                delay(70)
                                step()
                            }
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            repeat.cancel()
                        }
                    },
                )
            }
            .semantics {
                contentDescription = if (plus) "Download more chapters" else "Download fewer chapters"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        if (plus) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = color,
            )
        } else {
            Box(
                Modifier
                    .width(14.dp)
                    .height(2.dp)
                    .background(color),
            )
        }
    }
}

/**
 * The go button and, on its right, the trash arm that opens the Delete menu. The two share one
 * 48 dp box; the menu hangs from the box's bottom edge, as wide as the box and flush with its
 * left edge, so it sits exactly under the column it belongs to.
 */
@Composable
private fun SplitDownload(
    readout: String,
    canDownload: Boolean,
    savedCount: Int,
    deletable: DeletePlan,
    sizeOf: (Collection<String>) -> Long,
    onDownload: () -> Unit,
    onDelete: (DeleteKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var menuOpen by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }
    val canDelete = savedCount > 0

    Box(modifier = modifier.height(48.dp).onSizeChanged { widthPx = it.width }) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.onSurface.copy(alpha = if (canDownload) 1f else 0.35f)),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(enabled = canDownload, onClick = onDownload)
                    .semantics {
                        contentDescription = "Download, $readout"
                        role = Role.Button
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "↓ DOWNLOAD",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.background,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = readout.uppercase(),
                    color = scheme.background.copy(alpha = 0.65f),
                    fontSize = 9.5.sp,
                    lineHeight = 11.sp,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                )
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.background.copy(alpha = 0.3f)))
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .fillMaxHeight()
                    .background(if (menuOpen) scheme.background.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable(enabled = canDelete) { menuOpen = true }
                    .semantics {
                        contentDescription = "Delete downloaded chapters"
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = scheme.primary.copy(alpha = if (canDelete) 1f else 0.35f),
                )
            }
        }
        if (menuOpen) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { 48.dp.roundToPx() }),
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = true),
            ) {
                DeleteMenu(
                    width = with(density) { widthPx.toDp() },
                    savedCount = savedCount,
                    deletable = deletable,
                    sizeOf = sizeOf,
                    onPick = { kind ->
                        menuOpen = false
                        onDelete(kind)
                    },
                )
            }
        }
    }
}

/** The Delete menu: a "Delete" header with the saved total, then Read chapters and All saved. */
@Composable
private fun DeleteMenu(
    width: Dp,
    savedCount: Int,
    deletable: DeletePlan,
    sizeOf: (Collection<String>) -> Long,
    onPick: (DeleteKind) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // File sizes are read off the main thread; the rows fill in a moment after the menu opens.
    val sizes by produceState(0L to 0L, deletable) {
        value = withContext(Dispatchers.IO) { sizeOf(deletable.read) to sizeOf(deletable.all) }
    }
    Column(
        modifier = Modifier
            .width(width)
            .background(scheme.surfaceContainer)
            .border(1.dp, scheme.onSurface.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Micro(text = "Delete", accent = true)
            Micro(text = "%,d · %s".format(savedCount, formatBytes(sizes.second)))
        }
        Hairline()
        DeleteRow("Read chapters", "None read yet", deletable.read.size, sizes.first) { onPick(DeleteKind.Read) }
        Hairline()
        DeleteRow("All saved", "Nothing saved", deletable.all.size, sizes.second) { onPick(DeleteKind.All) }
    }
}

@Composable
private fun DeleteRow(title: String, emptyText: String, count: Int, bytes: Long, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val enabled = count > 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (enabled) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(2.dp))
        Micro(text = if (enabled) "%,d · %s".format(count, formatBytes(bytes)) else emptyText)
    }
}

/** The question replaces the stepper and presets, with the same 48 + 8 + 40 dp footprint. */
@Composable
private fun DeleteConfirm(
    kind: DeleteKind,
    deletable: DeletePlan,
    sizeOf: (Collection<String>) -> Long,
    onCancel: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val paths = if (kind == DeleteKind.Read) deletable.read else deletable.all
    val bytes by produceState(0L, paths) { value = withContext(Dispatchers.IO) { sizeOf(paths) } }
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (kind == DeleteKind.Read) {
                    "Delete ${chapterCount(paths.size)} you have read?"
                } else {
                    "Delete all ${chapterCount(paths.size)}?"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Micro(text = "Frees ${formatBytes(bytes)} · stays in your library")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlineButton(
                text = "CANCEL",
                description = "Cancel",
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                height = 40.dp,
            )
            OutlineButton(
                text = "DELETE ${paths.size}",
                description = "Delete ${chapterCount(paths.size)}",
                onClick = { onConfirm(paths) },
                modifier = Modifier.weight(1f),
                height = 40.dp,
                danger = true,
            )
        }
    }
}

/** Two or more cells in one outlined, hairline-divided control; the chosen one is filled. */
@Composable
private fun PresetGroup(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(40.dp)
            .border(1.dp, scheme.onSurface),
    ) {
        labels.forEachIndexed { index, label ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.onSurface.copy(alpha = 0.3f)))
            val chosen = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (chosen) scheme.onSurface else Color.Transparent)
                    .clickable { onSelect(index) }
                    .semantics {
                        contentDescription = "Download $label"
                        role = Role.Button
                        this.selected = chosen
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (chosen) scheme.background else scheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** A square ink-outlined button; [fill] paints progress from the left behind the label. */
@Composable
private fun OutlineButton(
    text: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    soft: Boolean = false,
    fill: Float? = null,
    height: Dp = 48.dp,
    danger: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val border = when {
        danger -> scheme.primary
        soft -> scheme.onSurface.copy(alpha = 0.45f)
        else -> scheme.onSurface
    }
    Box(
        modifier = modifier
            .height(height)
            .border(1.dp, border)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        if (fill != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(fill.coerceIn(0f, 1f))
                    .background(scheme.primary.copy(alpha = 0.35f)),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                danger -> scheme.primary
                soft -> scheme.onSurfaceVariant
                else -> scheme.onSurface
            },
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun Stat(label: String, value: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.padding(vertical = 12.dp)) {
        Micro(text = label)
        Spacer(Modifier.height(5.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = if (accent) scheme.primary else scheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * Back arrow, vermilion rule, title and author in one row. The arrow is centred on the text
 * and its glyph (14dp, centred in a 40dp target) starts exactly on the page margin; the rule
 * sits 16dp after the glyph and the title 16dp after the rule.
 */
@Composable
internal fun TitleBlock(
    onBack: () -> Unit,
    title: String,
    below: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .padding(start = ScreenPad - 13.dp, end = ScreenPad, top = 24.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackArrow(onBack)
        Spacer(Modifier.width(3.dp))
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(scheme.primary),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                color = scheme.onSurface,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Normal,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                letterSpacing = (-0.5).sp,
            )
            below()
        }
    }
}

/** A grey bar with a slow light sweep, standing in for text that has not arrived yet. */
@Composable
private fun Shimmer(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "sweep",
    )
    Box(
        modifier = modifier.drawBehind {
            val base = scheme.surfaceContainerHigh
            val light = scheme.outlineVariant
            drawRect(
                Brush.horizontalGradient(
                    colors = listOf(base, light, base),
                    startX = size.width * (sweep - 0.5f),
                    endX = size.width * (sweep + 0.5f),
                ),
            )
        },
    )
}

/** The app's vermilion seal, pulsing: marks the step that is running. */
@Composable
private fun PulsingSeal() {
    val pulse by rememberInfiniteTransition(label = "seal").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        modifier = Modifier
            .size(9.dp)
            .graphicsLayer {
                scaleX = 1f + 0.7f * pulse
                scaleY = 1f + 0.7f * pulse
                alpha = 1f - 0.65f * pulse
            }
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

private enum class StepState { Waiting, Active, Done }

@Composable
private fun LoadingStep(label: String, state: StepState, detail: String? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
            when (state) {
                StepState.Done -> Text("✓", color = scheme.primary, fontSize = 13.sp)
                StepState.Active -> PulsingSeal()
                StepState.Waiting -> Box(
                    Modifier
                        .size(9.dp)
                        .border(1.dp, scheme.outline, CircleShape),
                )
            }
        }
        Text(
            text = label + (detail?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = if (state == StepState.Waiting) scheme.onSurfaceVariant else scheme.onSurface,
        )
    }
}

/**
 * The page while it loads: the title is real from the first frame, everything still on its way
 * is a placeholder in its final shape, and a three-step card shows what is happening. Values
 * fill in as each step finishes.
 */
@Composable
private fun LoadingPage(state: NovelDetailState, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val details = state.details
    val summaryDone = details != null
    val fraction = if (state.totalPages > 0) state.pagesDone.toFloat() / state.totalPages else 0f

    Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        TitleBlock(onBack = onBack, title = state.title ?: details?.title.orEmpty()) {
            Spacer(Modifier.height(8.dp))
            if (details != null) {
                if (details.author.isNotEmpty()) Micro(text = details.author)
            } else {
                Shimmer(Modifier.width(110.dp).height(10.dp))
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = ScreenPad).padding(top = 24.dp).height(24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (details != null) {
                details.genres.take(4).forEach { genre ->
                    Text(
                        text = genre.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .border(1.dp, scheme.outline)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            } else {
                repeat(3) { Shimmer(Modifier.width(74.dp).fillMaxHeight()) }
            }
        }

        Spacer(Modifier.height(24.dp))
        Hairline(modifier = Modifier.padding(horizontal = ScreenPad))
        Row(modifier = Modifier.padding(horizontal = ScreenPad).height(IntrinsicSize.Min)) {
            if (state.totalChapters > 0) {
                Stat("Chapters", "%,d".format(state.loadedChapters), modifier = Modifier.weight(1f))
            } else {
                StatPlaceholder("Chapters", modifier = Modifier.weight(1f))
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
            if (details != null) {
                Stat("Status", details.status.ifEmpty { "—" }, modifier = Modifier.weight(1f).padding(start = 12.dp))
            } else {
                StatPlaceholder("Status", modifier = Modifier.weight(1f).padding(start = 12.dp))
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
            StatPlaceholder("Read", modifier = Modifier.weight(1f).padding(start = 12.dp))
        }
        Hairline(modifier = Modifier.padding(horizontal = ScreenPad))

        Column(modifier = Modifier.padding(horizontal = ScreenPad).padding(top = 26.dp)) {
            Hairline(color = scheme.onSurface)
            LoadingStep("Summary", if (summaryDone) StepState.Done else StepState.Active)
            Hairline()
            LoadingStep(
                label = "Chapter list",
                state = when {
                    !summaryDone -> StepState.Waiting
                    else -> StepState.Active
                },
                detail = if (state.totalChapters > 0) {
                    "%,d / %,d".format(state.loadedChapters, state.totalChapters)
                } else {
                    null
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(scheme.outlineVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(scheme.primary),
                )
            }
            LoadingStep("Ready", StepState.Waiting)
            Hairline()
            Spacer(Modifier.height(16.dp))
            Micro(
                text = when {
                    !summaryDone -> "Fetching summary"
                    state.pagesDone == 0 -> "Summary found"
                    else -> "Reading the chapter list"
                },
            )
        }
    }
}

@Composable
private fun StatPlaceholder(label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(vertical = 12.dp)) {
        Micro(text = label)
        Spacer(Modifier.height(10.dp))
        Shimmer(Modifier.width(56.dp).height(18.dp))
        Spacer(Modifier.height(1.dp))
    }
}

/** Flips the chapter list between oldest first and newest first. */
@Composable
internal fun OrderChip(newestFirst: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .border(1.dp, scheme.onSurface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (newestFirst) "Newest first, tap for oldest first" else "Oldest first, tap for newest first" }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (newestFirst) "NEWEST FIRST" else "OLDEST FIRST",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (newestFirst) "↓" else "↑",
            style = MaterialTheme.typography.labelSmall,
            color = if (newestFirst) scheme.primary else scheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
