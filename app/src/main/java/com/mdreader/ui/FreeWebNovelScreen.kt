package com.mdreader.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mdreader.online.OnlineNovel
import kotlinx.coroutines.launch

@Composable
fun FreeWebNovelScreen(
    onBack: () -> Unit,
    onSearch: suspend (String) -> List<OnlineNovel>,
    onOpen: suspend (OnlineNovel) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf(OnlineSearchState<OnlineNovel>()) }
    var draft by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf(OnlineOpenState<OnlineNovel>()) }
    val scheme = MaterialTheme.colorScheme

    fun submit() {
        val next = search.startSearch(draft) ?: return
        search = next
        scope.launch {
            val result = runCatching { onSearch(next.query) }
            search = result.fold(
                onSuccess = { search.onResults(next.requestId, it) },
                onFailure = { search.onFailure(next.requestId, onlineSearchErrorMessage(it)) },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPad, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "BACK",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
                modifier = Modifier.clickable(onClick = onBack),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "Free Web Novel",
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
            )
        }
        Hairline()
        Column(modifier = Modifier.padding(horizontal = ScreenPad, vertical = 22.dp)) {
            Micro(text = "Search", accent = true)
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Column {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            if (draft.isEmpty()) {
                                Text(
                                    text = "Novel title",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                        Hairline()
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (search.isSearching) "SEARCHING" else "SEARCH",
                style = MaterialTheme.typography.labelLarge,
                color = if (search.isSearching) scheme.onSurfaceVariant else scheme.primary,
                modifier = Modifier.clickable(enabled = !search.isSearching, onClick = ::submit),
            )
        }

        when (val phase = search.phase) {
            OnlineSearchPhase.Idle -> ReaderNote(
                "Search Free Web Novel, then add one book to the library.",
            )
            is OnlineSearchPhase.Searching -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = scheme.primary,
                    strokeWidth = 1.5.dp,
                )
            }
            is OnlineSearchPhase.Failed -> ReaderNote(phase.message)
            is OnlineSearchPhase.Loaded -> {
                if (phase.items.isEmpty()) {
                    ReaderNote("No novels matched \"${phase.query}\".")
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = ScreenPad, vertical = 8.dp),
                    ) {
                        items(phase.items, key = { it.url }) { novel ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !opening.isOpening) {
                                        opening = opening.begin(novel)
                                        scope.launch {
                                            val result = runCatching { onOpen(novel) }
                                            opening = result.fold(
                                                onSuccess = { opening.succeeded() },
                                                onFailure = {
                                                    opening.failed(novel, onlineOpenErrorMessage(it, novel.title))
                                                },
                                            )
                                        }
                                    }
                                    .padding(vertical = 16.dp),
                            ) {
                                Text(
                                    text = novel.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = scheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(6.dp))
                                if (opening.opening == novel) {
                                    Micro(text = "Adding", accent = true)
                                } else if (opening.failedItem == novel && opening.error != null) {
                                    Text(
                                        text = opening.error.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.primary,
                                    )
                                } else {
                                    Micro(text = "Free Web Novel")
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

@Composable
private fun ReaderNote(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPad, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
