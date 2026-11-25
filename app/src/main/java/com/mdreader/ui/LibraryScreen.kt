package com.mdreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.LocalFocusManager
import com.mdreader.data.online.NovelHit
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mdreader.data.Project
import com.mdreader.data.comics.ComicHit
import com.mdreader.data.comics.ComicSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.mdreader.data.LibrarySort
import com.mdreader.data.ageLabel
import com.mdreader.data.matching
import com.mdreader.data.ordered
import com.mdreader.data.Progress

/** Card border + inner padding, so the shelf titles start exactly under the Continue title. */
private val INDEX_COLUMN = 21.dp

/** Progress of a "check every novel" pull on the shelf. */
data class LibraryCheck(
    val refreshing: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** Short outcome shown briefly after a check ("Up to date", "3 new chapters"). */
    val result: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    projects: List<Project>,
    progressOf: (String) -> Progress?,
    loading: Boolean,
    importing: Boolean,
    snackbarHostState: SnackbarHostState,
    check: LibraryCheck,
    onCheckAll: () -> Unit,
    downloadedCount: (Project) -> Int,
    onImport: () -> Unit,
    onOpenHit: (NovelHit) -> Unit,
    onOpenComic: (ComicSource, ComicHit) -> Unit,
    onOpen: (Project) -> Unit,
    onRefresh: (Project) -> Unit,
    onRemove: (Project) -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf<Project?>(null) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.Updated) }
    var findOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    // The list ends exactly where the idle dock begins, so the last row is never covered.
    val dockClearance = AddNovelDockIdleHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            if (importing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.5.dp),
                    color = scheme.primary,
                    trackColor = Color.Transparent,
                )
            }

            LibraryHeader(novelCount = projects.size)

            Spacer(Modifier.height(32.dp))

            when {
                loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = scheme.primary,
                        strokeWidth = 1.5.dp,
                    )
                }

                projects.isEmpty() -> EmptyLibrary(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                else -> {
                    // The novel read most recently leads, frozen above the shelf.
                    val lead = projects
                        .mapNotNull { p -> progressOf(p.id)?.let { p to it } }
                        .maxByOrNull { it.second.updatedAt }
                    val shown = projects
                        .ordered(sort) { progressOf(it.id)?.updatedAt ?: 0L }
                        .matching(query)
                    val now = System.currentTimeMillis()

                    if (lead != null) {
                        val leadCover = rememberCover(lead.first.coverUrl)
                        // The cover only lends its colours: a blurred field behind the card, whose
                        // bottom padding is the 32 dp gap to the shelf bar.
                        Box(modifier = Modifier.fillMaxWidth().coverField(leadCover, FieldFade.Home)) {
                            Column(modifier = Modifier.padding(start = ScreenPad, end = ScreenPad, bottom = 32.dp)) {
                                ContinueCard(
                                    project = lead.first,
                                    progress = lead.second,
                                    glass = leadCover != null,
                                    onOpen = { onOpen(lead.first) },
                                    onRefresh = { onRefresh(lead.first) },
                                    onRemove = { pendingRemoval = lead.first },
                                )
                            }
                        }
                    }

                    // The last few reading sessions — novels and comics together, newest first —
                    // under the lead card, above the shelf.
                    if (query.isBlank()) {
                        val history = projects
                            .mapNotNull { p -> progressOf(p.id)?.let { p to it } }
                            .sortedByDescending { it.second.updatedAt }
                            .drop(if (lead != null) 1 else 0)
                            .take(5)
                        if (history.isNotEmpty()) {
                            Spacer(Modifier.height(20.dp))
                            HistorySection(history = history, onOpen = onOpen)
                        }
                    }

                    ShelfBar(
                        total = projects.size,
                        shown = shown.size,
                        sort = sort,
                        onSort = { sort = it },
                        findOpen = findOpen,
                        query = query,
                        onQuery = { query = it },
                        onFindOpen = { findOpen = it },
                    )

                    val pullState = rememberPullToRefreshState()
                    PullToRefreshBox(
                        isRefreshing = check.refreshing,
                        onRefresh = onCheckAll,
                        state = pullState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        indicator = {
                            CheckStrip(
                                fraction = pullState.distanceFraction,
                                refreshing = check.refreshing,
                                result = check.result,
                                modifier = Modifier.align(Alignment.TopCenter),
                                busyText = "Checking \u00b7 ${check.done} / ${check.total}",
                                pullText = "Pull to check all novels",
                                releaseText = "Release to check all novels",
                            )
                        },
                    ) {
                        // The list slides down to make room for the strip; the frozen part never moves.
                        // The drag distance is read inside the layer block, so a pull redraws the
                        // list instead of recomposing it on every pixel of movement.
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val shown = if (check.refreshing || check.result != null) 1f else pullState.distanceFraction.coerceIn(0f, 1f)
                                    translationY = shown * 44.dp.toPx()
                                },
                            contentPadding = PaddingValues(start = ScreenPad, end = ScreenPad, bottom = dockClearance),
                        ) {
                            if (shown.isEmpty()) {
                                item(key = "none") {
                                    Text(
                                        text = "No novel matches \u201c${query.trim()}\u201d.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = scheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 24.dp),
                                    )
                                }
                            }
                            itemsIndexed(shown, key = { _, project -> project.id }) { index, project ->
                                Column {
                                    ProjectRow(
                                        index = index,
                                        project = project,
                                        progress = progressOf(project.id),
                                        now = now,
                                        onOpen = { onOpen(project) },
                                        onRefresh = { onRefresh(project) },
                                        onRemove = { pendingRemoval = project },
                                    )
                                    Hairline()
                                }
                            }
                        }
                    }
                }
            }

        }

        if (searchActive) {
            // Dim the shelf while searching; tapping it drops focus (the dock closes itself).
            val focusManager = LocalFocusManager.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scheme.scrim.copy(alpha = 0.42f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { focusManager.clearFocus() },
            )
        }
        // The search bar is the library's footer: always there, no button to open it.
        AddNovelDock(
            active = searchActive,
            onActiveChange = { searchActive = it },
            onOpenHit = onOpenHit,
            onOpenComic = onOpenComic,
            onImportFolder = onImport,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 120.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = RectangleShape,
                containerColor = scheme.onSurface,
                contentColor = scheme.surface,
                actionContentColor = scheme.primary,
                dismissActionContentColor = scheme.onSurfaceVariant,
            )
        }
    }

    val removalTarget = pendingRemoval
    if (removalTarget != null) {
        RemoveDialog(
            project = removalTarget,
            downloaded = downloadedCount(removalTarget),
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                onRemove(removalTarget)
                pendingRemoval = null
            },
        )
    }
}

@Composable
private fun LibraryHeader(novelCount: Int) {
    Column(modifier = Modifier.padding(horizontal = ScreenPad)) {
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SealDot()
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "NOVEL.MD.READER",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Micro(text = "${novelCount.toString().padStart(2, '0')} titles")
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = "Library",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

    }
}

/**
 * The bar the shelf hangs from, frozen with the Continue card. Order segments and a find glyph
 * share one 40 dp row; the glyph turns the row into a 48 dp filter field in the same place.
 */
@Composable
private fun ShelfBar(
    total: Int,
    shown: Int,
    sort: LibrarySort,
    onSort: (LibrarySort) -> Unit,
    findOpen: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onFindOpen: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(findOpen) { if (findOpen) focus.requestFocus() }

    Column {
        if (findOpen) {
            Row(
                modifier = Modifier
                    .padding(horizontal = ScreenPad)
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchGlyph()
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus),
                    decorationBox = { field ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    text = "Filter library",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                            field()
                        }
                    },
                )
                Micro(text = "$shown / $total")
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable {
                            onQuery("")
                            keyboard?.hide()
                            onFindOpen(false)
                        }
                        .semantics {
                            contentDescription = "Close filter"
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .padding(horizontal = ScreenPad)
                    .fillMaxWidth()
                    .height(40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Micro(text = "Library \u00b7 $total")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SortSegments(sort = sort, onSort = onSort)
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { onFindOpen(true) }
                            .semantics {
                                contentDescription = "Filter library"
                                role = Role.Button
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        SearchGlyph()
                    }
                }
            }
        }
        Hairline(
            modifier = Modifier.padding(horizontal = ScreenPad),
            color = if (findOpen) scheme.onSurface else scheme.outlineVariant,
        )
    }
}

@Composable
private fun SortSegments(sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val options = listOf(
        LibrarySort.Updated to "UPDATED",
        LibrarySort.Read to "READ",
        LibrarySort.Title to "A\u2013Z",
    )
    Row(modifier = Modifier.height(32.dp).border(1.dp, scheme.onSurface)) {
        options.forEach { (option, label) ->
            val on = option == sort
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(if (on) scheme.onSurface else Color.Transparent)
                    .clickable { onSort(option) }
                    .padding(horizontal = 10.dp)
                    .semantics {
                        contentDescription = "Order by $label"
                        role = Role.RadioButton
                        this.selected = on
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) scheme.background else scheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * The last few reading sessions, novels and comics side by side: name, chapter and percent,
 * how long ago. Tapping one opens it where it was left.
 */
@Composable
private fun HistorySection(history: List<Pair<Project, Progress>>, onOpen: (Project) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val now = System.currentTimeMillis()
    Column(modifier = Modifier.padding(horizontal = ScreenPad)) {
        Micro(text = "History")
        Spacer(Modifier.height(6.dp))
        Hairline()
        history.forEachIndexed { position, (project, progress) ->
            val total = project.chapters.size.coerceAtLeast(1)
            val chapter = (progress.chapterIndex + 1).coerceIn(1, total)
            val percent = (chapter * 100 / total).coerceIn(1, 100)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(project) }
                    .padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (position + 1).toString().padStart(2, '0'),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier
                        .width(INDEX_COLUMN)
                        .padding(top = 3.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Micro(text = "Ch. ${chapter.toString().padStart(2, '0')} · $percent%", accent = true)
                }
                Micro(text = ageLabel(now, progress.updatedAt))
            }
            if (position < history.lastIndex) Hairline()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectRow(
    index: Int,
    project: Project,
    progress: Progress?,
    now: Long,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val chapterCount = project.chapters.size
    val position = progress
        ?.chapterIndex
        ?.coerceIn(0, (chapterCount - 1).coerceAtLeast(0))
        ?.plus(1)
    val percent = if (position != null && chapterCount > 0) {
        (position * 100 / chapterCount).coerceIn(1, 100)
    } else {
        null
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menuOpen = true },
                )
                .padding(vertical = 15.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = (index + 1).toString().padStart(2, '0'),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier
                    .width(INDEX_COLUMN)
                    .padding(top = 4.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                Micro(
                    text = listOfNotNull(
                        if (project.isComic) "Comic" else "Online".takeIf { project.isOnline },
                        if (position != null && percent != null) {
                            "Ch. ${position.toString().padStart(2, '0')} · $percent%"
                        } else {
                            "Not started"
                        },
                    ).joinToString(" \u00b7 "),
                    accent = position != null,
                )
            }

            // Newest news on the title line, how long ago on the meta line.
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(56.dp)) {
                Box(modifier = Modifier.height(24.dp), contentAlignment = Alignment.CenterEnd) {
                    if (project.newChapters > 0) {
                        Text(
                            text = "+${project.newChapters}",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                Micro(text = ageLabel(now, maxOf(project.updatedAt, project.importedAt)))
            }

            ProjectMenu(
                project = project,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onRefresh = onRefresh,
                onRemove = onRemove,
            )
        }

        if (position != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-ScreenPad), y = 16.dp)
                    .width(2.dp)
                    .height(20.dp)
                    .background(scheme.primary),
            )
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = ScreenPad)) {
        Spacer(Modifier.height(32.dp))
        Text(
            text = "Nothing here yet.",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Search for a novel or a comic below, or tap DIR to import a folder of " +
                "chapters (.md and .txt open in natural order).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp),
        )
    }
}

@Composable
internal fun RemoveDialog(
    project: Project,
    downloaded: Int = 0,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Micro(text = "Library", accent = true)
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Remove from library?",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "\"${project.name}\" will be removed from your library. " +
                        if (project.isOnline) {
                            "Your reading progress for it is forgotten." +
                                if (downloaded > 0) {
                                    " Its $downloaded downloaded chapters are deleted from this device."
                                } else {
                                    ""
                                }
                        } else {
                            "The chapter files in the folder are not touched."
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = "CANCEL",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(8.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = "REMOVE",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onConfirm)
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

/** The long-press menu shared by the shelf rows and the Continue card; there is no visible button. */
@Composable
private fun ProjectMenu(
    project: Project,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier = modifier) {
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { onMenuOpenChange(false) },
            shape = RectangleShape,
            containerColor = scheme.surfaceContainer,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (project.isOnline) "Check for new chapters" else "Refresh chapters",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                onClick = {
                    onMenuOpenChange(false)
                    onRefresh()
                },
            )
            DropdownMenuItem(
                text = { Text("Remove from library", style = MaterialTheme.typography.bodyMedium) },
                onClick = {
                    onMenuOpenChange(false)
                    onRemove()
                },
            )
        }
    }
}

/** The novel you were last in, big, with how far along you are. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(
    project: Project,
    progress: Progress,
    glass: Boolean,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val total = project.totalChapters ?: project.chapters.size
    val position = (progress.chapterIndex + 1).coerceIn(1, total.coerceAtLeast(1))
    val fraction = (position.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    val percent = (fraction * 100).toInt().coerceIn(1, 100)

    Column {
        Micro(text = "Continue reading", accent = true, modifier = Modifier.padding(bottom = 16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // On a cover's colour field the card is a glass pane; otherwise the plain ink frame.
                .then(if (glass) Modifier.glassPane() else Modifier.border(1.dp, scheme.onSurface))
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
        ) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp)) {
                Text(
                    text = project.name,
                    color = scheme.onSurface,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Normal,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    letterSpacing = (-0.3).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Micro(text = "Ch. $position of ${"%,d".format(total)} \u00b7 ")
                    Micro(text = "$percent%", accent = true)
                }
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(scheme.outlineVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(2.dp)
                            .background(scheme.primary),
                    )
                }
            }
            ProjectMenu(
                project = project,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onRefresh = onRefresh,
                onRemove = onRemove,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}
