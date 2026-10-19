package com.mdreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mdreader.data.Project
import com.mdreader.data.Progress

@Composable
fun LibraryScreen(
    projects: List<Project>,
    progressOf: (String) -> Progress?,
    loading: Boolean,
    importing: Boolean,
    snackbarHostState: SnackbarHostState,
    onImport: () -> Unit,
    onOpen: (Project) -> Unit,
    onRefresh: (Project) -> Unit,
    onRemove: (Project) -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf<Project?>(null) }
    val scheme = MaterialTheme.colorScheme

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

            Spacer(Modifier.height(26.dp))
            GridRule(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenPad),
            )

            ListHead()

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

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = ScreenPad),
                ) {
                    itemsIndexed(projects, key = { _, project -> project.id }) { index, project ->
                        Column {
                            ProjectRow(
                                index = index,
                                project = project,
                                progress = progressOf(project.id),
                                onOpen = { onOpen(project) },
                                onRefresh = { onRefresh(project) },
                                onRemove = { pendingRemoval = project },
                            )
                            if (index < projects.lastIndex) Hairline()
                        }
                    }
                    item {
                        QuietNote(
                            modifier = Modifier.padding(top = 26.dp),
                        )
                    }
                }
            }

            LibraryFooter(onImport = onImport)
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 92.dp),
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
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SealDot()
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "MD READER",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Micro(text = "${novelCount.toString().padStart(2, '0')} novels")
        }

        Spacer(Modifier.height(30.dp))

        Text(
            text = "Library",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "A quiet shelf for novels kept as folders of chapters.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 250.dp),
        )
    }
}

@Composable
private fun ListHead() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ScreenPad, end = ScreenPad, top = 14.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Micro(text = "Collection")
        Micro(text = "Last read")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectRow(
    index: Int,
    project: Project,
    progress: Progress?,
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
                .padding(vertical = 20.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = (index + 1).toString().padStart(2, '0'),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier
                    .width(26.dp)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Micro(text = "$chapterCount chapters")
                    Spacer(Modifier.width(7.dp))
                    MetaDot()
                    Spacer(Modifier.width(7.dp))
                    Micro(
                        text = if (position != null && percent != null) {
                            "Ch. ${position.toString().padStart(2, '0')} · $percent%"
                        } else {
                            "Not started"
                        },
                        accent = position != null,
                    )
                }
            }

            Box {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "⋯",
                        color = scheme.onSurfaceVariant,
                        fontSize = 16.sp,
                        lineHeight = 16.sp,
                    )
                }

                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = RectangleShape,
                    containerColor = scheme.surfaceContainer,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                ) {
                    DropdownMenuItem(
                        text = { Text("Refresh chapters", style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            menuOpen = false
                            onRefresh()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove from library", style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }

        if (position != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-ScreenPad), y = 21.dp)
                    .width(2.dp)
                    .height(20.dp)
                    .background(scheme.primary),
            )
        }
    }
}

@Composable
private fun QuietNote(modifier: Modifier = Modifier) {
    Text(
        text = "Chapters stay in the folder you chose. Nothing is copied or changed.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = modifier.widthIn(max = 238.dp),
    )
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = ScreenPad)) {
        Spacer(Modifier.height(32.dp))
        Text(
            text = "No novels yet.",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Import the folder that holds your chapters. .md and .txt files " +
                "open in natural order.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp),
        )
    }
}

@Composable
private fun LibraryFooter(onImport: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Hairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = ScreenPad, end = ScreenPad, top = 16.dp, bottom = 30.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ImportAction(onClick = onImport)
            Micro(
                text = "V1.0",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun RemoveDialog(
    project: Project,
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
                    text = "\"${project.name}\" will be removed from MD Reader. " +
                        "The chapter files in the folder are not touched.",
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
