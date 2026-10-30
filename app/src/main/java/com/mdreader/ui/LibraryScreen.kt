package com.mdreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
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
import com.mdreader.data.Progress

@Composable
fun LibraryScreen(
    projects: List<Project>,
    progressOf: (String) -> Progress?,
    loading: Boolean,
    importing: Boolean,
    snackbarHostState: SnackbarHostState,
    onImport: () -> Unit,
    onOpenHit: (NovelHit) -> Unit,
    onOpen: (Project) -> Unit,
    onRefresh: (Project) -> Unit,
    onRemove: (Project) -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf<Project?>(null) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
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
                    // The novel read most recently leads; the rest keep the shelf order.
                    val lead = projects
                        .mapNotNull { p -> progressOf(p.id)?.let { p to it } }
                        .maxByOrNull { it.second.updatedAt }
                    val others = projects.filter { it.id != lead?.first?.id }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(start = ScreenPad, end = ScreenPad, bottom = 96.dp),
                    ) {
                        if (lead != null) {
                            item(key = "continue") {
                                ContinueCard(
                                    project = lead.first,
                                    progress = lead.second,
                                    onOpen = { onOpen(lead.first) },
                                    onRefresh = { onRefresh(lead.first) },
                                    onRemove = { pendingRemoval = lead.first },
                                )
                            }
                        }
                        if (others.isNotEmpty()) {
                            item(key = "label") {
                                Micro(
                                    text = if (lead != null) "Library · ${others.size}" else "Library",
                                    modifier = Modifier.padding(top = 28.dp, bottom = 4.dp),
                                )
                            }
                        }
                        itemsIndexed(others, key = { _, project -> project.id }) { index, project ->
                            Column {
                                ProjectRow(
                                    index = index,
                                    project = project,
                                    progress = progressOf(project.id),
                                    onOpen = { onOpen(project) },
                                    onRefresh = { onRefresh(project) },
                                    onRemove = { pendingRemoval = project },
                                )
                                if (index < others.lastIndex) Hairline()
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
                    text = "NOVEL.MD.READER",
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
                Micro(
                    text = listOfNotNull(
                        "Online".takeIf { project.isOnline },
                        if (position != null && percent != null) {
                            "Ch. ${position.toString().padStart(2, '0')} · $percent%"
                        } else {
                            "Not started"
                        },
                    ).joinToString(" \u00b7 "),
                    accent = position != null,
                )
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
                    .offset(x = (-ScreenPad), y = 21.dp)
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
            text = "No novels yet.",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Search for a novel below, or tap DIR to import a folder of chapters " +
                "(.md and .txt open in natural order).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp),
        )
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
                    text = "\"${project.name}\" will be removed from your library. " +
                        if (project.isOnline) {
                            "Your reading progress for it is forgotten."
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
        Micro(text = "Continue reading", accent = true, modifier = Modifier.padding(bottom = 12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, scheme.onSurface)
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
        ) {
            // The cover as a ghost behind the text, washed toward the page colour on the text side.
            val cover = rememberCover(project.coverUrl)
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.28f,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                0f to scheme.background,
                                0.25f to scheme.background,
                                1f to scheme.background.copy(alpha = 0.35f),
                            ),
                        ),
                )
            }
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
