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
import com.mdreader.data.Project
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

/** The number a site chapter carries in its path (`.../chapter-2343`), or its position. */
private fun Chapter.number(index: Int): Int =
    Regex("(\\d+)$").find(uri)?.value?.toIntOrNull() ?: (index + 1)

/** Index of chapter [number]: matched by the site's own numbering, else by position. */
private fun List<Chapter>.indexOfNumber(number: Int): Int? {
    val byNumber = indexOfFirst { chapter -> Regex("(\\d+)$").find(chapter.uri)?.value?.toIntOrNull() == number }
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
    onRetry: () -> Unit,
    onRead: (Int) -> Unit,
    onContinue: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleOrder: () -> Unit,
    onRefresh: () -> Unit,
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
                val stripShown = if (state.refreshing || state.refreshResult != null) {
                    1f
                } else {
                    pullState.distanceFraction.coerceIn(0f, 1f)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = stripShown * 44.dp.toPx() }
                        .navigationBarsPadding(),
                ) {
                    item {
                        Header(
                            details = details,
                            project = project,
                            inLibrary = state.inLibrary,
                            resumeIndex = state.resumeIndex,
                            onBack = onBack,
                            onRead = onRead,
                            onContinue = onContinue,
                            onToggleLibrary = onToggleLibrary,
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
                                    modifier = Modifier.width(42.dp).padding(top = 4.dp),
                                )
                                Text(
                                    text = chapter.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (read) scheme.onSurfaceVariant else scheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
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
private fun BackArrow(onBack: () -> Unit, modifier: Modifier = Modifier) {
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
private fun CheckStrip(fraction: Float, refreshing: Boolean, result: String?, modifier: Modifier = Modifier) {
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
                refreshing -> "Checking…"
                result != null -> result
                fraction >= 1f -> "Release to check"
                else -> "Pull to check for new chapters"
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
    onRead: (Int) -> Unit,
    onContinue: () -> Unit,
    onToggleLibrary: () -> Unit,
    onGoTo: (Int) -> Int?,
    onOpenNumber: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
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

    Column {
        TitleBlock(onBack = onBack, title = details.title) {
            if (details.author.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Micro(text = details.author)
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
        Text(
            text = if (inLibrary) "✓ IN LIBRARY · REMOVE" else "+ ADD TO LIBRARY",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleLibrary)
                .padding(vertical = 16.dp),
        )

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

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, accent: Boolean = false) {
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
private fun TitleBlock(
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
private fun OrderChip(newestFirst: Boolean, onClick: () -> Unit) {
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
