package com.mdreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mdreader.data.Progress
import com.mdreader.data.Project
import com.mdreader.data.comics.ComicCache
import com.mdreader.data.comics.ComicException
import com.mdreader.data.comics.ComicSource
import com.mdreader.data.remapTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The comic reader: one continuous vertical strip of page images, nothing else. No paged mode, no
 * tap zones and no middle-tap menu — chapter access is the top bar's INDEX, exactly like the text
 * reader. Position is the chapter plus the page at the top of the screen.
 */
@OptIn(FlowPreview::class, ExperimentalMaterial3Api::class)
@Composable
fun ComicReaderScreen(
    project: Project,
    source: ComicSource,
    initialProgress: Progress?,
    onBack: () -> Unit,
    onProgress: (Progress) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scheme = MaterialTheme.colorScheme
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
        listState.scrollToItem(target + 1)
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

    // Warm the next couple of pages, and the next chapter's page list near the end.
    LaunchedEffect(pages, index) {
        if (pages.isEmpty()) return@LaunchedEffect
        delay(400)
        val page = firstPage
        (page..(page + 2)).forEach { i ->
            pages.getOrNull(i)?.let { url -> ComicCache.bitmap(context, source, url, 1440) }
        }
        if (page >= pages.lastIndex - 2) {
            chapters.getOrNull(index + 1)?.let { next ->
                runCatching { ComicCache.pages(context, source, next.uri) }
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

    BackHandler { leave() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(start = 4.dp, end = 16.dp),
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
                QuietArrow(direction = ArrowDirection.Left)
            }
            Text(
                text = project.name.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .clickable(enabled = chapters.isNotEmpty()) { showChapters = true }
                    .padding(horizontal = 12.dp)
                    .semantics {
                        contentDescription = "Open chapter index"
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "INDEX",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurface,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
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
                        color = scheme.primary,
                        strokeWidth = 1.5.dp,
                    )
                }

                pages.isEmpty() -> ComicMessage(
                    title = "No pages",
                    body = "This chapter came back empty.",
                    action = "Try again",
                    onAction = { attempt++ },
                )

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(key = "opening") {
                            Column(modifier = Modifier.padding(horizontal = ScreenPad, vertical = 22.dp)) {
                                Micro(
                                    text = "Chapter ${(index + 1).toString().padStart(2, '0')}",
                                    accent = true,
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = chapters.getOrNull(index)?.title.orEmpty(),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = scheme.onSurface,
                                )
                                Spacer(Modifier.height(6.dp))
                                Micro(text = "${pages.size} pages · vertical strip")
                            }
                        }
                        items(pages) { url ->
                            ComicPage(source = source, url = url)
                        }
                        item(key = "end") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ScreenPad, vertical = 30.dp),
                            ) {
                                Micro(text = "End of chapter")
                                Spacer(Modifier.height(14.dp))
                                Row {
                                    if (index > 0) {
                                        Text(
                                            text = "PREVIOUS",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = scheme.primary,
                                            modifier = Modifier
                                                .clickable { goTo(index - 1) }
                                                .padding(vertical = 8.dp, horizontal = 2.dp),
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    if (index < chapters.lastIndex) {
                                        Text(
                                            text = "NEXT CHAPTER",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = scheme.primary,
                                            modifier = Modifier
                                                .clickable { goTo(index + 1) }
                                                .padding(vertical = 8.dp, horizontal = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (chapters.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                Hairline()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
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
                                QuietArrow(direction = ArrowDirection.Left)
                                Spacer(Modifier.width(8.dp))
                                Micro(text = "Prev", color = scheme.onSurface)
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
                        color = scheme.onSurfaceVariant,
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
                                Micro(text = "Next", color = scheme.onSurface)
                                Spacer(Modifier.width(8.dp))
                                QuietArrow(direction = ArrowDirection.Right)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showChapters) {
        ModalBottomSheet(
            onDismissRequest = { showChapters = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RectangleShape,
            containerColor = scheme.surfaceContainer,
            scrimColor = scheme.scrim.copy(alpha = 0.32f),
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
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Micro(text = "${chapters.size}")
                }
                Hairline()
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
                                    text = (i + 1).toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (on) scheme.primary else scheme.onSurfaceVariant,
                                    modifier = Modifier.width(44.dp).padding(top = 8.dp),
                                )
                                Text(
                                    text = chapter.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (on) scheme.primary else scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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

@Composable
private fun ComicMessage(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPad, vertical = 48.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = action.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
            modifier = Modifier.clickable(onClick = onAction).padding(vertical = 8.dp),
        )
    }
}
