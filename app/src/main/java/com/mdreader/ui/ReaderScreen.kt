package com.mdreader.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mdreader.data.ChapterSource
import com.mdreader.data.Progress
import com.mdreader.data.Project
import com.mdreader.md.Block
import com.mdreader.md.MarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ReaderScreen(
    project: Project,
    initialProgress: Progress?,
    onBack: () -> Unit,
    onProgress: (Progress) -> Unit,
) {
    val context = LocalContext.current
    val chapters = project.chapters
    val lastIndex = chapters.lastIndex

    var index by rememberSaveable(project.id) {
        mutableIntStateOf((initialProgress?.chapterIndex ?: 0).coerceIn(0, lastIndex.coerceAtLeast(0)))
    }
    var blocks by remember(project.id) { mutableStateOf<List<Block>>(emptyList()) }
    var loading by remember(project.id) { mutableStateOf(true) }
    var loadError by remember(project.id) { mutableStateOf<String?>(null) }
    var showChapters by remember { mutableStateOf(false) }
    var pendingRestore by remember(project.id) { mutableStateOf(initialProgress) }
    val listState = rememberLazyListState()

    LaunchedEffect(project.id, index) {
        loading = true
        loadError = null
        val chapter = chapters.getOrNull(index)
        if (chapter == null) {
            blocks = emptyList()
            loading = false
            return@LaunchedEffect
        }

        val outcome = withContext(Dispatchers.IO) {
            runCatching { ChapterSource.read(context, Uri.parse(chapter.uri)) }
        }
        outcome.fold(
            onSuccess = { text -> blocks = MarkdownParser.parse(text, chapter.isMarkdown) },
            onFailure = {
                blocks = emptyList()
                loadError = "Couldn't open \"${chapter.name}\". The folder may have moved, " +
                    "been renamed, or lost its permission."
            },
        )
        loading = false
    }

    // Restore the saved scroll position once per visit, otherwise start at the top.
    LaunchedEffect(blocks) {
        if (blocks.isEmpty()) return@LaunchedEffect
        val restore = pendingRestore
        if (restore != null && restore.chapterIndex == index) {
            listState.scrollToItem(
                index = restore.scrollIndex.coerceIn(0, blocks.lastIndex),
                scrollOffset = restore.scrollOffset.coerceAtLeast(0),
            )
            pendingRestore = null
        } else {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(project.id, index) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .debounce(500)
            .collect { (scrollIndex, scrollOffset) ->
                // Ignore positions from the previous chapter while the new one loads.
                if (!loading) onProgress(Progress(index, scrollIndex, scrollOffset))
            }
    }

    fun saveAndLeave() {
        onProgress(Progress(index, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset))
        onBack()
    }

    BackHandler { saveAndLeave() }

    fun goTo(target: Int) {
        val clamped = target.coerceIn(0, lastIndex.coerceAtLeast(0))
        if (clamped == index) return
        index = clamped
        blocks = emptyList()
        loadError = null
        loading = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (chapters.isEmpty()) {
                                "No chapters"
                            } else {
                                "Chapter ${index + 1} of ${chapters.size}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { saveAndLeave() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to library",
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showChapters = true },
                        enabled = chapters.isNotEmpty(),
                    ) {
                        Text("Chapters")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            if (chapters.isNotEmpty()) {
                Surface(
                    tonalElevation = 3.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = { goTo(index - 1) },
                            enabled = index > 0,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Previous")
                        }
                        Button(
                            onClick = { goTo(index + 1) },
                            enabled = index < lastIndex,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                        ) {
                            Text("Next")
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val message = loadError
            when {
                message != null -> ReaderError(message)

                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                chapters.isEmpty() -> ReaderError(
                    "This project has no chapters left. Re-import the folder from the library.",
                )

                blocks.isEmpty() -> Text(
                    text = "This chapter is empty.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    textAlign = TextAlign.Center,
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 24.dp,
                        end = 24.dp,
                        top = 8.dp,
                        bottom = 40.dp,
                    ),
                ) {
                    items(blocks) { block ->
                        BlockView(block)
                    }
                }
            }
        }
    }

    if (showChapters) {
        ChapterListSheet(
            project = project,
            currentIndex = index,
            onSelect = { selected ->
                showChapters = false
                goTo(selected)
            },
            onDismiss = { showChapters = false },
        )
    }
}

@Composable
private fun ReaderError(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Couldn't open this chapter",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
