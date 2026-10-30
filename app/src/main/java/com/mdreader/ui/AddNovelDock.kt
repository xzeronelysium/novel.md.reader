package com.mdreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mdreader.data.online.FreeWebNovelClient
import com.mdreader.data.online.NovelHit
import com.mdreader.data.online.OnlineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private sealed interface DockState {
    data object Idle : DockState
    data object Searching : DockState
    data class Results(val hits: List<NovelHit>, val page: Int, val more: Boolean) : DockState
    data class Failed(val message: String) : DockState
}

private const val MIN_QUERY = 3
private const val TYPING_PAUSE_MS = 500L

/**
 * The library's one way to get a novel: a field pinned to the bottom that
 * searches Free Web Novel while you type, with a DIR chip for a local folder.
 * A result opens the novel's details; nothing is saved until you add it.
 */
@Composable
fun AddNovelDock(
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onOpenHit: (NovelHit) -> Unit,
    onImportFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf<DockState>(DockState.Idle) }

    // Fetch the next results page when the list is scrolled to its end.
    suspend fun loadNextPage(current: DockState.Results) {
        val term = query.trim()
        val next = try {
            FreeWebNovelClient.search(term, current.page + 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val known = current.hits.map { it.path }.toSet()
        val fresh = next.filter { it.path !in known }
        // A page with nothing new means we've reached the end (the site repeats its last page).
        state = DockState.Results(current.hits + fresh, current.page + 1, more = fresh.isNotEmpty())
    }

    fun close() {
        query = ""
        focusManager.clearFocus()
        keyboard?.hide()
    }

    BackHandler(enabled = active || query.isNotEmpty()) { close() }

    // Search once typing pauses; a new keystroke cancels the pending/in-flight search.
    LaunchedEffect(query) {
        val term = query.trim()
        if (term.length < MIN_QUERY) {
            state = DockState.Idle
            return@LaunchedEffect
        }
        delay(TYPING_PAUSE_MS)
        state = DockState.Searching
        state = try {
            val hits = FreeWebNovelClient.search(term, 1)
            DockState.Results(hits, page = 1, more = hits.isNotEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DockState.Failed((e as? OnlineException)?.message ?: "Search failed.")
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
                // About four rows tall; the rest scrolls.
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 340.dp),
                    contentPadding = PaddingValues(horizontal = ScreenPad),
                ) {
                    itemsIndexed(current.hits, key = { _, hit -> hit.path }) { index, hit ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val opened = hit
                                    close()
                                    onOpenHit(opened)
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
                                Micro(text = hit.meta)
                            }
                        }
                        if (index < current.hits.lastIndex) Hairline()
                    }
                }
            }
        }
        if (state != DockState.Idle) Hairline()

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
                                text = "Search",
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

@Composable
private fun DockChip(text: String, description: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(30.dp)
            .border(1.dp, scheme.onSurface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurface,
        )
    }
}
