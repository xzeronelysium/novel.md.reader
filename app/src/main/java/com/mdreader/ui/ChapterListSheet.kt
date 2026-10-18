package com.mdreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mdreader.data.Chapter
import com.mdreader.data.Project

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListSheet(
    project: Project,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    val trimmedQuery = query.trim()

    val chapters = project.chapters
    val filtered = remember(trimmedQuery, chapters) {
        chapters.matching(trimmedQuery)
    }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0),
    )

    LaunchedEffect(trimmedQuery) {
        if (trimmedQuery.isNotEmpty()) listState.scrollToItem(0)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = scheme.surfaceContainer,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        tonalElevation = 0.dp,
        dragHandle = { SheetGrip() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = ScreenPad, end = ScreenPad, top = 22.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "Chapters",
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Micro(text = "${chapters.size} files")
            }

            SearchRow(
                query = query,
                onQueryChange = { query = it },
                onClear = { query = "" },
                onSearch = { keyboard?.hide() },
            )

            Hairline()

            if (filtered.isEmpty()) {
                Text(
                    text = "No chapters match.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = ScreenPad, vertical = 26.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                ) {
                    itemsIndexed(
                        filtered,
                        key = { _, indexed -> indexed.value.uri },
                    ) { position, indexed ->
                        val chapter = indexed.value
                        val chapterIndex = indexed.index
                        ChapterRow(
                            index = chapterIndex,
                            title = chapter.title,
                            current = chapterIndex == currentIndex,
                            onClick = { onSelect(chapterIndex) },
                        )
                        if (position < filtered.lastIndex) Hairline()
                    }
                }
            }

            Column(modifier = Modifier.navigationBarsPadding()) {
                Hairline()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = ScreenPad, end = ScreenPad, top = 12.dp, bottom = 30.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Micro(text = if (trimmedQuery.isEmpty()) "Tap to jump" else "Filtered")
                    Spacer(Modifier.weight(1f))
                    Micro(
                        text = if (trimmedQuery.isEmpty()) {
                            "${chapters.size} chapters"
                        } else {
                            "${filtered.size} matches"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ScreenPad, end = ScreenPad, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchGlyph(color = scheme.onSurfaceVariant.copy(alpha = 0.8f))
        Spacer(Modifier.width(10.dp))

        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search chapters",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    innerTextField()
                }
            },
        )

        if (query.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = "CLEAR",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                modifier = Modifier
                    .clickable(onClick = onClear)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SheetGrip() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(30.dp)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
        )
    }
}

@Composable
private fun ChapterRow(
    index: Int,
    title: String,
    current: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ScreenPad)
            .height(53.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = (index + 1).toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelSmall,
            color = if (current) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.width(30.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (current) {
            Spacer(Modifier.width(10.dp))
            Micro(text = "Reading", accent = true)
        }
    }
}

/** Case-insensitive chapter filter over both the display title and the filename. */
internal fun List<Chapter>.matching(query: String): List<IndexedValue<Chapter>> {
    val needle = query.trim()
    return withIndex().filter { (_, chapter) ->
        needle.isEmpty() ||
            chapter.title.contains(needle, ignoreCase = true) ||
            chapter.name.contains(needle, ignoreCase = true)
    }
}
