package com.mdreader.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mdreader.data.Chapter
import com.mdreader.data.Progress
import com.mdreader.data.Project
import com.mdreader.desktop.data.FsNovelFolderAccess
import com.mdreader.md.Block
import com.mdreader.md.MarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun filterChapters(chapters: List<Chapter>, query: String): List<IndexedValue<Chapter>> {
    val needle = query.trim()
    return chapters.withIndex().filter { (_, chapter) ->
        needle.isEmpty() ||
            chapter.title.contains(needle, ignoreCase = true) ||
            chapter.name.contains(needle, ignoreCase = true)
    }
}

@Composable
private fun ReaderProgressTrack(chapters: Int, index: Int, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.primary
    val fraction = if (chapters > 0) ((index + 1f) / chapters).coerceIn(0f, 1f) else 0f
    Canvas(modifier.height(3.dp)) {
        drawRect(track)
        drawRect(fill, size = size.copy(width = size.width * fraction))
    }
}

@OptIn(FlowPreview::class)
@Composable
fun DesktopReaderScreen(
    project: Project,
    initialProgress: Progress?,
    textSizeLevel: Int,
    railOpen: Boolean,
    onToggleRail: () -> Unit,
    onTextSizeChange: (Int) -> Unit,
    onBack: () -> Unit,
    onProgress: (Progress) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val chapters = project.chapters
    val lastIndex = chapters.lastIndex
    val scope = rememberCoroutineScope()
    val readerFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val readerListState = rememberLazyListState()
    val density = LocalDensity.current
    val lineScrollPx = with(density) { 32.dp.toPx() }

    var index by rememberSaveable(project.id) {
        mutableIntStateOf((initialProgress?.chapterIndex ?: 0).coerceIn(0, lastIndex.coerceAtLeast(0)))
    }
    var blocks by remember(project.id) { mutableStateOf<List<Block>>(emptyList()) }
    var loading by remember(project.id) { mutableStateOf(true) }
    var loadError by remember(project.id) { mutableStateOf<String?>(null) }
    var pendingRestore by remember(project.id) { mutableStateOf(initialProgress) }
    var railQuery by rememberSaveable(project.id) { mutableStateOf("") }
    var searchHasFocus by remember { mutableStateOf(false) }
    var focusSearchWhenRailOpens by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { readerFocusRequester.requestFocus() }
    LaunchedEffect(railOpen, focusSearchWhenRailOpens) {
        if (railOpen && focusSearchWhenRailOpens) {
            searchFocusRequester.requestFocus()
            focusSearchWhenRailOpens = false
        }
    }
    LaunchedEffect(project.id, index) {
        loading = true
        loadError = null
        val chapter = chapters.getOrNull(index)
        if (chapter == null) {
            blocks = emptyList()
            loading = false
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            runCatching { FsNovelFolderAccess.readChapter(chapter.uri) }
        }.fold(
            onSuccess = { text ->
                blocks = MarkdownParser.parse(text, chapter.isMarkdown).withoutOpeningTitle(chapter.title)
            },
            onFailure = {
                blocks = emptyList()
                loadError = "Couldn't open \"${chapter.name}\". The folder may have moved or been renamed."
            },
        )
        loading = false
    }
    LaunchedEffect(blocks) {
        if (blocks.isEmpty()) return@LaunchedEffect
        val restore = pendingRestore
        if (restore != null && restore.chapterIndex == index) {
            readerListState.scrollToItem(
                restore.scrollIndex.coerceIn(0, blocks.lastIndex),
                restore.scrollOffset.coerceAtLeast(0),
            )
            pendingRestore = null
        } else {
            readerListState.scrollToItem(0)
        }
        readerFocusRequester.requestFocus()
    }
    LaunchedEffect(project.id, index) {
        snapshotFlow { readerListState.firstVisibleItemIndex to readerListState.firstVisibleItemScrollOffset }
            .debounce(500)
            .collect { (scrollIndex, scrollOffset) ->
                if (!loading) onProgress(Progress(index, scrollIndex, scrollOffset))
            }
    }

    fun goTo(target: Int) {
        val clamped = target.coerceIn(0, lastIndex.coerceAtLeast(0))
        if (clamped == index) return
        index = clamped
        blocks = emptyList()
        loadError = null
        loading = true
        railQuery = ""
        searchHasFocus = false
    }
    fun scrollLines(lines: Int) {
        scope.launch { readerListState.scroll { scrollBy(lineScrollPx * lines) } }
    }

    fun scrollPage(direction: Int) {
        val viewportHeight = readerListState.layoutInfo.viewportSize.height.toFloat().coerceAtLeast(120f)
        scope.launch { readerListState.scroll { scrollBy(viewportHeight * direction) } }
    }

    fun focusSearch() {
        if (railOpen) {
            searchFocusRequester.requestFocus()
        } else {
            focusSearchWhenRailOpens = true
            onToggleRail()
        }
    }

    fun handleKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (searchHasFocus) return false
        return when {
            event.isCtrlPressed && event.key == Key.F -> {
                if (!railOpen) {
                    focusSearchWhenRailOpens = true
                    onToggleRail()
                } else {
                    searchFocusRequester.requestFocus()
                }
                true
            }
            event.key == Key.DirectionLeft -> { goTo(index - 1); true }
            event.key == Key.DirectionRight -> { goTo(index + 1); true }
            event.key == Key.DirectionUp -> { scrollLines(-3); true }
            event.key == Key.DirectionDown -> { scrollLines(3); true }
            event.key == Key.PageUp -> { scrollPage(-1); true }
            event.key == Key.PageDown -> { scrollPage(1); true }
            else -> false
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .focusRequester(readerFocusRequester)
            .focusable()
            .onPreviewKeyEvent(::handleKey),
    ) {
        if (railOpen) {
            ChapterIndexRail(
                projectName = project.name,
                chapters = chapters,
                currentIndex = index,
                query = railQuery,
                onQueryChange = { railQuery = it },
                searchFocusRequester = searchFocusRequester,
                onSearchFocusChange = { searchHasFocus = it },
                onSelect = ::goTo,
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
        }

        Column(Modifier.weight(1f).background(scheme.background)) {
            ReaderHeader(
                projectName = project.name,
                index = index,
                total = chapters.size,
                textSizeLevel = textSizeLevel,
                railOpen = railOpen,
                onBack = onBack,
                onToggleRail = onToggleRail,
                onSearch = { searchHasFocus = true },
                onTextSizeChange = onTextSizeChange,
                onPrevious = { goTo(index - 1) },
                onNext = { goTo(index + 1) },
                previousEnabled = index > 0,
                nextEnabled = index < lastIndex,
            )
            ReaderProgressTrack(chapters.size, index, Modifier.padding(horizontal = 24.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .readerBaselineGrid()
                    .background(scheme.background),
                contentAlignment = Alignment.TopCenter,
            ) {
                when {
                    loadError != null -> Text(
                        loadError!!,
                        fontFamily = Sans,
                        fontSize = 14.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(ScreenPad).widthIn(max = MeasureWidth),
                    )
                    loading -> CircularProgressIndicator(
                        Modifier.align(Alignment.Center),
                        color = scheme.primary,
                        strokeWidth = 1.5.dp,
                    )
                    chapters.isEmpty() -> Text(
                        "No chapters in this novel.",
                        modifier = Modifier.align(Alignment.Center),
                        color = scheme.onSurfaceVariant,
                    )
                    else -> {
                        val textScale = when (textSizeLevel.coerceIn(0, 2)) {
                            0 -> 0.88f
                            1 -> 1f
                            else -> 1.15f
                        }
                        CompositionLocalProvider(
                            LocalDensity provides Density(density.density, density.fontScale * textScale),
                        ) {
                            Box(
                                Modifier
                                    .widthIn(max = MeasureWidth)
                                    .fillMaxWidth()
                                    .padding(horizontal = 40.dp),
                            ) {
                                Box(
                                    Modifier
                                        .align(Alignment.CenterStart)
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(scheme.outlineVariant.copy(alpha = 0.5f)),
                                )
                                Box(
                                    Modifier
                                        .align(Alignment.CenterEnd)
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(scheme.outlineVariant.copy(alpha = 0.5f)),
                                )
                                LazyColumn(
                                    state = readerListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(readerFocusRequester)
                                        .focusable()
                                        .onPreviewKeyEvent(::handleKey),
                                    contentPadding = PaddingValues(vertical = 40.dp, horizontal = 12.dp),
                                ) {
                                    val chapter = chapters[index]
                                    item(key = "opening") {
                                        Micro("Chapter ${index + 1}")
                                        Text(
                                            chapter.title,
                                            fontFamily = Serif,
                                            fontSize = 30.sp,
                                            fontWeight = FontWeight.Normal,
                                            letterSpacing = (-0.7).sp,
                                            lineHeight = 38.sp,
                                            modifier = Modifier.padding(top = 14.dp, bottom = 28.dp),
                                        )
                                    }
                                    items(blocks) { BlockView(it) }
                                }
                            }
                        }
                    }
                }
            }
            ReaderFooter(
                index = index,
                total = chapters.size,
                onPrevious = { goTo(index - 1) },
                onNext = { goTo(index + 1) },
                previousEnabled = index > 0,
                nextEnabled = index < lastIndex,
            )
        }
    }
}

@Composable
fun ReaderHeader(
    projectName: String,
    index: Int,
    total: Int,
    textSizeLevel: Int,
    railOpen: Boolean,
    onBack: () -> Unit,
    onToggleRail: () -> Unit,
    onSearch: () -> Unit,
    onTextSizeChange: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().background(scheme.background)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    GhostButton("← Library", onBack)
                    Text("/", color = scheme.outline)
                    GhostButton(if (railOpen) "Hide index" else "Show index", onToggleRail)
                    GhostButton("Find chapter", onSearch)
                    Micro("CTRL F")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    TextSizePicker(textSizeLevel, onTextSizeChange)
                    GhostButton("Prev", onPrevious, modifier = Modifier.padding(horizontal = 5.dp), enabled = previousEnabled)
                    GhostButton("Next", onNext, modifier = Modifier.padding(horizontal = 5.dp), enabled = nextEnabled)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 13.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    Micro(projectName)
                    Text("${(index + 1).toString().padStart(2, '0')} / ${total.toString().padStart(2, '0')} chapters", fontFamily = Sans, fontSize = 12.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    GhostButton("Previous", onPrevious, enabled = previousEnabled)
                    GhostButton("Next", onNext, enabled = nextEnabled)
                }
            }
        }
}

@Composable
private fun ReaderFooter(
    index: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Micro(if (previousEnabled) "← Previous chapter" else "Beginning")
            Micro("${(index + 1).toString().padStart(2, '0')} / ${total.toString().padStart(2, '0')}")
            Micro(if (nextEnabled) "Next chapter →" else "End")
        }
    }
}

@Composable
private fun TextSizePicker(level: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(end = 4.dp)) {
        listOf("S", "M", "L").forEachIndexed { option, label ->
            val selected = option == level.coerceIn(0, 2)
            val scheme = MaterialTheme.colorScheme
            Text(label, fontFamily = Sans, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.plainClickable { onChange(option) }.padding(horizontal = 7.dp, vertical = 8.dp))
        }
    }
}

@Composable
internal fun ChapterIndexRail(
    projectName: String,
    chapters: List<Chapter>,
    currentIndex: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    searchFocusRequester: FocusRequester,
    onSearchFocusChange: (Boolean) -> Unit,
    onSelect: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val results = remember(chapters, query) { filterChapters(chapters, query) }
    val railListState = rememberLazyListState()
    LaunchedEffect(currentIndex, query) {
        if (query.isBlank() && chapters.isNotEmpty()) railListState.scrollToItem((currentIndex - 2).coerceAtLeast(0))
        else if (query.isNotBlank()) railListState.scrollToItem(0)
    }
    Column(Modifier.width(300.dp).fillMaxHeight().background(scheme.surfaceContainerHigh)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Micro("Chapter index")
                    Text(projectName, fontFamily = Serif, fontSize = 18.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 7.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Micro("${chapters.size} total")
            }
            Spacer(Modifier.height(18.dp))
            ChapterSearchField(query, onQueryChange, results.size, searchFocusRequester, onSearchFocusChange)
        }
        Hairline()
        if (results.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.Center) {
                Micro("No results")
                Text("Try a title, filename, or chapter number.", fontFamily = Serif, fontSize = 16.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            LazyColumn(state = railListState, modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                items(results, key = { it.value.uri }) { entry ->
                    val chapterIndex = entry.index
                    val chapter = entry.value
                    val active = chapterIndex == currentIndex
                    val interaction = remember { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    Row(
                        Modifier.fillMaxWidth().hoverable(interaction).background(when { active -> scheme.surfaceContainer; hovered -> scheme.surfaceVariant; else -> Color.Transparent })
                            .clickable(interactionSource = interaction, indication = null) { onSelect(chapterIndex) }
                            .padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(3.dp).height(42.dp).background(if (active) scheme.primary else Color.Transparent))
                        Column(Modifier.weight(1f).padding(start = 15.dp, top = 9.dp, bottom = 9.dp)) {
                            Text(chapter.title, fontFamily = Sans, fontSize = 13.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("CH. ${(chapterIndex + 1).toString().padStart(2, '0')}", fontFamily = Sans, fontSize = 9.sp, letterSpacing = 1.sp, color = if (active) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                        }
                        if (active) Text("READING", fontFamily = Sans, fontSize = 8.sp, letterSpacing = 1.sp, color = scheme.primary)
                    }
                }
            }
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Micro(if (query.isBlank()) "Click a chapter to jump" else "Filtered")
            Micro(if (results.size == 1) "1 match" else "${results.size} matches")
        }
    }
}

@Composable
private fun ChapterSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    matches: Int,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(scheme.background).border(1.dp, scheme.outlineVariant).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.width(14.dp).height(14.dp)) {
            val color = scheme.onSurfaceVariant
            drawCircle(color, radius = size.minDimension * .32f, center = Offset(size.width * .42f, size.height * .42f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
            drawLine(color, Offset(size.width * .64f, size.height * .64f), Offset(size.width, size.height), 1.dp.toPx())
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isBlank()) Text("Search chapters…", fontFamily = Sans, fontSize = 13.sp, color = scheme.onSurfaceVariant.copy(alpha = .75f))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onFocusChange(false) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { onFocusChange(it.isFocused) }.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onFocusChange(false)
                        false
                    } else false
                },
            )
        }
        if (query.isNotBlank()) {
            Text("×", fontSize = 18.sp, color = scheme.onSurfaceVariant, modifier = Modifier.clickable { onQueryChange("") }.padding(start = 10.dp))
        } else {
            Micro("CTRL F", modifier = Modifier.testTag("chapter-search-count"))
        }
    }
}

private fun List<Block>.withoutOpeningTitle(title: String): List<Block> {
    val first = firstOrNull() as? Block.Heading ?: return this
    if (first.level > 2) return this
    val heading = first.text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    val expected = title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    if (expected.isNotEmpty() && heading == expected) return drop(1)
    if (Regex("^chapter\\s+\\d+$").matches(heading)) return drop(1)
    return this
}
