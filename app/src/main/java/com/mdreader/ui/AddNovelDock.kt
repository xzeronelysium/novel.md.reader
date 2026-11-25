package com.mdreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mdreader.data.comics.ComicHit
import com.mdreader.data.comics.ComicMemo
import com.mdreader.data.comics.ComicSource
import com.mdreader.data.comics.ComicSources
import com.mdreader.data.online.FreeWebNovelClient
import com.mdreader.data.online.NovelHit
import com.mdreader.data.online.OnlineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Which site the dock searches: the novel source, or one of the two hardcoded comic sources. */
enum class DockSource(val label: String, val comicId: String?) {
    Novel("NOVEL", null),
    Weeb("WEEB", "weebcentral"),
    Bat("BAT", "batcave"),
}

sealed interface DockHit {
    val title: String
    val meta: String
    val path: String

    data class Novel(val hit: NovelHit) : DockHit {
        override val title get() = hit.title
        override val meta get() = hit.rating
        override val path get() = hit.path
    }

    data class Comic(val source: ComicSource, val hit: ComicHit) : DockHit {
        override val title get() = hit.title
        override val meta get() = hit.meta
        override val path get() = hit.path
    }
}

private sealed interface DockState {
    data object Idle : DockState
    data object Searching : DockState
    data class Results(val hits: List<DockHit>, val page: Int, val more: Boolean) : DockState
    data class Failed(val message: String) : DockState
}

private const val MIN_QUERY = 3
private const val TYPING_PAUSE_MS = 500L

/** Retyping the same search in the dock answers from memory; sites don't need to see it twice. */
private const val SEARCH_TTL_MS = 3 * 60 * 1000L

/** One memoized comic search, shared by the typing search and the list's next-page load. */
private suspend fun comicSearch(source: ComicSource, term: String, page: Int): List<ComicHit> =
    ComicMemo.remember("search|${source.id}|$term|$page", SEARCH_TTL_MS) { source.search(term, page) }
        .hits

/**
 * The library's one way in: a field pinned to the bottom that searches while you type, over the
 * novel source or one of the two comic sources. Results open details; nothing is saved until you
 * add it. DIR still imports a local folder of chapters.
 */
@Composable
fun AddNovelDock(
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onOpenHit: (NovelHit) -> Unit,
    onOpenComic: (ComicSource, ComicHit) -> Unit,
    onImportFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(DockSource.Novel) }
    var state by remember { mutableStateOf<DockState>(DockState.Idle) }

    suspend fun loadNextPage(current: DockState.Results): Unit {
        val term = query.trim()
        val page = current.page + 1
        val next: List<DockHit> = try {
            when (scope) {
                DockSource.Novel -> FreeWebNovelClient.search(term, page).map { DockHit.Novel(it) }
                else -> {
                    val source = ComicSources.byId(scope.comicId) ?: return
                    comicSearch(source, term, page).map { DockHit.Comic(source, it) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val known = current.hits.map { it.path }.toSet()
        val fresh = next.filter { it.path !in known }
        state = DockState.Results(current.hits + fresh, page, more = fresh.isNotEmpty())
    }

    fun close() {
        query = ""
        focusManager.clearFocus()
        keyboard?.hide()
    }

    BackHandler(enabled = active || query.isNotEmpty()) { close() }

    // Search once typing pauses; a new keystroke (or a different source) cancels the pending one.
    LaunchedEffect(query, scope) {
        val term = query.trim()
        if (term.length < MIN_QUERY) {
            state = DockState.Idle
            return@LaunchedEffect
        }
        delay(TYPING_PAUSE_MS)
        state = DockState.Searching
        state = try {
            val hits: List<DockHit> = when (scope) {
                DockSource.Novel -> FreeWebNovelClient.search(term, 1).map { DockHit.Novel(it) }
                else -> {
                    val source = ComicSources.byId(scope.comicId)!!
                    comicSearch(source, term, 1).map { DockHit.Comic(source, it) }
                }
            }
            DockState.Results(hits, page = 1, more = hits.isNotEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DockState.Failed(
                (e as? OnlineException)?.message
                    ?: (e as? com.mdreader.data.comics.ComicException)?.message
                    ?: "Search failed.",
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.background)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Hairline(color = if (active) scheme.onSurface else scheme.outlineVariant)
        when (val current = state) {
            DockState.Idle -> Unit
            DockState.Searching -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = scheme.primary,
                    strokeWidth = 1.5.dp,
                )
            }

            is DockState.Failed -> Text(
                text = current.message,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenPad, vertical = 16.dp),
            )

            is DockState.Results -> if (current.hits.isEmpty()) {
                Text(
                    text = "Nothing matched that title.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = ScreenPad, vertical = 16.dp),
                )
            } else {
                val listState = rememberLazyListState()
                val atEnd by remember {
                    derivedStateOf {
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                        last != null && last.index >= listState.layoutInfo.totalItemsCount - 1
                    }
                }
                LaunchedEffect(atEnd, current.hits.size) {
                    if (atEnd && current.more) loadNextPage(current)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 340.dp),
                    contentPadding = PaddingValues(horizontal = ScreenPad),
                ) {
                    itemsIndexed(current.hits, key = { _, hit -> "${scope.name}:${hit.path}" }) { index, hit ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val opened = hit
                                    close()
                                    when (opened) {
                                        is DockHit.Novel -> onOpenHit(opened.hit)
                                        is DockHit.Comic -> onOpenComic(opened.source, opened.hit)
                                    }
                                }
                                .padding(vertical = 13.dp),
                        ) {
                            Text(
                                text = hit.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = scheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (hit.meta.isNotEmpty()) {
                                Spacer(Modifier.size(5.dp))
                                if (hit is DockHit.Novel) {
                                    HitMeta(rating = hit.meta, meta = "")
                                } else {
                                    Micro(text = hit.meta)
                                }
                            }
                        }
                        if (index < current.hits.lastIndex) Hairline()
                    }
                }
            }
        }
        if (state != DockState.Idle) Hairline()

        if (active || query.isNotEmpty() || state != DockState.Idle) {
            SourceRow(scope = scope, onScope = { scope = it })
            Hairline(modifier = Modifier.padding(horizontal = ScreenPad))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ScreenPad, end = ScreenPad, top = 12.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { onActiveChange(it.isFocused) },
                decorationBox = { field ->
                    Box(modifier = Modifier.padding(vertical = 8.dp)) {
                        if (query.isEmpty()) {
                            Text(
                                text = when (scope) {
                                    DockSource.Novel -> "Search"
                                    else -> "Search comics"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                        field()
                    }
                },
            )
            Spacer(Modifier.width(10.dp))
            DockChip(text = "DIR", description = "Import a folder", onClick = onImportFolder)
            if (active || query.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                DockChip(text = "✕", description = "Close", onClick = ::close)
            }
        }
    }
}

/** Three segments picking the site to search, the same shape as the shelf's sort row. */
@Composable
private fun SourceRow(scope: DockSource, onScope: (DockSource) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPad, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.height(32.dp).border(1.dp, scheme.onSurface)) {
            DockSource.entries.forEach { option ->
                val on = option == scope
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(if (on) scheme.onSurface else Color.Transparent)
                        .clickable { onScope(option) }
                        .padding(horizontal = 10.dp)
                        .semantics {
                            contentDescription = "Search ${option.label.lowercase()}"
                            role = Role.RadioButton
                            selected = on
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (on) scheme.background else scheme.onSurfaceVariant,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Micro(
            text = when (scope) {
                DockSource.Novel -> "Free Web Novel"
                DockSource.Weeb -> "Weeb Central"
                DockSource.Bat -> "BatCave"
            },
        )
    }
}

/** The dock with nothing open: hairline 1 + top 12 + field 48 + bottom 22, before the system inset. */
val AddNovelDockIdleHeight = 83.dp

@Composable
private fun DockChip(text: String, description: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(40.dp)
            .border(1.dp, scheme.onSurface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurface,
        )
    }
}
