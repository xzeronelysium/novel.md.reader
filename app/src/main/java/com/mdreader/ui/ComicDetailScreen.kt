package com.mdreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mdreader.data.Project
import com.mdreader.data.comics.ComicChapter
import com.mdreader.data.comics.ComicDetails
import com.mdreader.data.comics.ComicSource
import com.mdreader.data.comics.ComicSources
import kotlinx.coroutines.launch
import java.util.Locale

/** What the comic page shows while its details and chapter list load. */
data class ComicDetailState(
    val sourceId: String? = null,
    val path: String = "",
    val title: String = "",
    /** The search hit's cover, so the poster renders before details arrive. */
    val coverUrl: String = "",
    val details: ComicDetails? = null,
    val chapters: List<ComicChapter> = emptyList(),
    /** True from the moment details render until the chapter list lands. */
    val chaptersLoading: Boolean = false,
    /** Why the chapter list failed, when the poster itself is already up. */
    val chaptersError: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val inLibrary: Boolean = false,
    val project: Project? = null,
    val resumeIndex: Int? = null,
) {
    val source: ComicSource? get() = ComicSources.byId(sourceId)
}

/** What the comic page shows about saving to the user's own storage folder. */
data class ComicSaveState(
    /** The picked folder's name, or null before the first pick. */
    val rootName: String? = null,
    val running: Boolean = false,
    /** The chapter currently being written, as its issue number. */
    val chapterLabel: String = "",
    val chapterDone: Int = 0,
    val chapterTotal: Int = 0,
    val pageDone: Int = 0,
    val pageTotal: Int = 0,
    val finished: String? = null,
    val error: String? = null,
)

/** Rows before the chapters in the lazy list: the header and the "Chapters" label. */
private const val LEADING_ITEMS = 2

/**
 * A comic's page, the same design as the novel's: the cover's frosted field, the serif title
 * block, the facts in hairlines, the ink Continue button, the outline library actions, the
 * summary and the jump field, then every chapter in reading order. Only the poster differs —
 * and the storage section, where a comic offers what a novel's downloads offer.
 */
@Composable
fun ComicDetailScreen(
    state: ComicDetailState,
    storage: ComicSaveState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRead: (Int) -> Unit,
    onContinue: () -> Unit,
    onToggleLibrary: () -> Unit,
    onOpenLibrary: () -> Unit,
    onPickStorage: () -> Unit,
    onSave: (Int?) -> Unit,
    onStopSave: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding(),
    ) {
        val details = state.details
        val chapters = state.chapters
        when {
            state.error != null && details == null -> Column(
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

            details == null -> Column(
                modifier = Modifier.padding(horizontal = ScreenPad, vertical = 24.dp),
            ) {
                BackArrow(onBack)
                Spacer(Modifier.height(24.dp))
                Micro(text = state.title.ifEmpty { "Comic" })
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Loading…",
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurfaceVariant,
                )
            }

            else -> {
                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                var newestFirst by rememberSaveable(state.path) { mutableStateOf(false) }
                val shownChapters = remember(chapters, newestFirst) {
                    if (newestFirst) chapters.asReversed() else chapters
                }
                val keyboard = LocalSoftwareKeyboardController.current
                val focus = LocalFocusManager.current
                var expanded by remember { mutableStateOf(false) }
                var jump by remember { mutableStateOf("") }
                var jumpMissing by remember { mutableStateOf(false) }
                val total = chapters.size

                fun goTo() {
                    val number = jump.trim().toIntOrNull() ?: return
                    val index = number - 1
                    jumpMissing = index !in chapters.indices
                    if (!jumpMissing) {
                        keyboard?.hide()
                        focus.clearFocus()
                        // The list may be shown reversed; land where the chapter sits on screen.
                        val shown = if (newestFirst) chapters.lastIndex - index else index
                        scope.launch { listState.animateScrollToItem(LEADING_ITEMS + shown) }
                    }
                }

                val cover = rememberCover(details.cover.ifEmpty { state.coverUrl })
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                ) {
                    item {
                        Column(modifier = Modifier.coverField(cover, FieldFade.Page, alpha = 0.4f)) {
                            Column(modifier = Modifier.coverField(cover, FieldFade.Detail)) {
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
                                                text = genre.uppercase(Locale.ROOT),
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
                                    val read = state.resumeIndex?.let {
                                        ((it + 1) * 100 / total.coerceAtLeast(1)).coerceIn(1, 100)
                                    }
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
                                    .clickable {
                                        if (state.resumeIndex != null) onContinue() else onRead(0)
                                    }
                                    .padding(vertical = 15.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (state.resumeIndex != null) {
                                        "CONTINUE · CH. ${state.resumeIndex + 1}"
                                    } else if (chapters.isNotEmpty()) {
                                        "READ FIRST"
                                    } else {
                                        "LOADING CHAPTERS…"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = scheme.background,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            if (!state.chaptersLoading) {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = ScreenPad)
                                        .padding(top = 12.dp)
                                        .fillMaxWidth(),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .border(1.dp, scheme.onSurface)
                                            .clickable(onClick = onToggleLibrary)
                                            .padding(vertical = 14.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = if (state.inLibrary) "✓ IN LIBRARY" else "ADD TO LIBRARY",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = scheme.onSurface,
                                        )
                                    }
                                    if (state.inLibrary) {
                                        Spacer(Modifier.width(12.dp))
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .border(1.dp, scheme.onSurface)
                                                .clickable(onClick = onOpenLibrary)
                                                .padding(vertical = 14.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "OPEN",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = scheme.onSurface,
                                            )
                                        }
                                    }
                                }
                            }

                            StorageSection(
                                storage = storage,
                                onPickStorage = onPickStorage,
                                onSave = onSave,
                                onStopSave = onStopSave,
                            )

                            if (details.description.isNotEmpty()) {
                                Column(modifier = Modifier.padding(horizontal = ScreenPad).padding(top = 24.dp)) {
                                    Micro(text = "Summary", accent = true)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = details.description,
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
                            if (chapters.isNotEmpty()) {
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
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Number,
                                                imeAction = ImeAction.Go,
                                            ),
                                            keyboardActions = KeyboardActions(onGo = { goTo() }),
                                            modifier = Modifier.weight(1f),
                                            decorationBox = { field ->
                                                Box {
                                                    if (jump.isEmpty()) {
                                                        Text(
                                                            text = "Jump to chapter",
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                        )
                                                    }
                                                    field()
                                                }
                                            },
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Micro(
                                            text = if (jumpMissing) "No chapter $jump" else "of $total",
                                            accent = !jumpMissing,
                                            modifier = Modifier.clickable(onClick = ::goTo),
                                        )
                                    }
                                    Hairline(modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
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
                                Micro(text = if (state.chaptersLoading) "Chapters · loading…" else "Chapters · $total")
                                if (chapters.isNotEmpty()) {
                                    OrderChip(
                                        newestFirst = newestFirst,
                                        onClick = { newestFirst = !newestFirst },
                                    )
                                }
                            }
                            Hairline()
                        }
                    }

                    itemsIndexed(shownChapters, key = { _, chapter -> chapter.path }) { shownIndex, chapter ->
                        // Position in the comic, whichever way the list is displayed.
                        val index = if (newestFirst) chapters.lastIndex - shownIndex else shownIndex
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
                                    text = (index + 1).toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (current) scheme.primary else scheme.onSurfaceVariant,
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
                            }
                            Hairline()
                        }
                    }

                    if (chapters.isEmpty() && !state.chaptersLoading) {
                        item {
                            Column(modifier = Modifier.padding(horizontal = ScreenPad, vertical = 24.dp)) {
                                Text(
                                    text = state.chaptersError ?: "This comic has no chapters yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                )
                                if (state.chaptersError != null) {
                                    Spacer(Modifier.height(14.dp))
                                    Text(
                                        text = "TRY AGAIN",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = scheme.primary,
                                        modifier = Modifier
                                            .clickable(onClick = onRetry)
                                            .padding(vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A comic's answer to the novel's download dock: plain files in a folder of the user's own.
 * Pick it once, then NEXT 10 / NEXT 50 / ALL, with the live progress the dock shows.
 */
@Composable
private fun StorageSection(
    storage: ComicSaveState,
    onPickStorage: () -> Unit,
    onSave: (Int?) -> Unit,
    onStopSave: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(horizontal = ScreenPad).padding(top = 18.dp)) {
        Micro(text = "Storage", accent = true)
        Spacer(Modifier.height(10.dp))
        if (storage.rootName == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, scheme.onSurface)
                    .clickable(onClick = onPickStorage)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "SAVE TO A FOLDER IN STORAGE",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface,
                )
            }
        } else if (storage.running) {
            Text(
                text = "${storage.chapterLabel} · page ${storage.pageDone}/${storage.pageTotal}",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            val fraction = if (storage.pageTotal > 0) {
                (storage.pageDone.toFloat() / storage.pageTotal).coerceIn(0f, 1f)
            } else {
                0f
            }
            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(scheme.outlineVariant)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(scheme.primary),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Micro(
                    text = "Chapter ${storage.chapterDone + 1} of ${storage.chapterTotal} · ${storage.rootName}",
                )
                Text(
                    text = "STOP",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onStopSave)
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                )
            }
        } else {
            Micro(text = "Folder · ${storage.rootName}")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("NEXT 10" to 10, "NEXT 50" to 50, "ALL" to null).forEach { (label, count) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, scheme.onSurface)
                            .clickable { onSave(count) }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.onSurface,
                        )
                    }
                }
            }
            storage.error?.let {
                Spacer(Modifier.height(8.dp))
                Micro(text = it)
            }
            storage.finished?.let {
                Spacer(Modifier.height(8.dp))
                Micro(text = it)
            }
        }
    }
}
