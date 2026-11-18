package com.mdreader.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mdreader.data.Progress
import com.mdreader.data.Project

@Composable
fun DesktopLibraryScreen(
    projects: List<Project>,
    progressOf: (String) -> Progress?,
    loading: Boolean,
    importing: Boolean,
    snackbarHostState: SnackbarHostState,
    onOpen: (Project) -> Unit,
    onRefresh: (Project) -> Unit,
    onRemove: (Project) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var pendingRemoval by remember { mutableStateOf<Project?>(null) }

    Box(Modifier.fillMaxSize().background(scheme.background)) {
        Column(Modifier.fillMaxSize()) {
            if (importing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = scheme.primary,
                    trackColor = scheme.outlineVariant,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 72.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 28.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column {
                        Micro("The collection  /  01")
                        Text(
                            "Library",
                            fontFamily = Sans,
                            fontSize = 52.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-2.4).sp,
                            lineHeight = 56.sp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            "Novels, kept in their own folders.",
                            fontFamily = Serif,
                            fontSize = 17.sp,
                            lineHeight = 25.sp,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            projects.size.toString().padStart(2, '0'),
                            fontFamily = Serif,
                            fontSize = 48.sp,
                            lineHeight = 52.sp,
                            color = scheme.onSurface,
                        )
                        Micro("Titles", modifier = Modifier.padding(top = 2.dp))
                    }
                }
                GridRule()
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Micro("No.", modifier = Modifier.width(52.dp))
                    Micro("Title", modifier = Modifier.weight(1f))
                    Micro("Chapters", modifier = Modifier.width(104.dp), textAlign = TextAlign.End)
                    Micro("Progress", modifier = Modifier.width(132.dp), textAlign = TextAlign.End)
                    Spacer(Modifier.width(32.dp))
                }
                Hairline()
                when {
                    loading -> Box(
                        Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = scheme.primary, strokeWidth = 1.5.dp)
                    }
                    projects.isEmpty() -> Box(
                        Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column {
                            Text(
                                "A shelf begins with a story.",
                                fontFamily = Serif,
                                fontSize = 28.sp,
                                color = scheme.onSurface,
                            )
                            Text(
                                "Choose a folder of .md or .txt chapters to add your first title.",
                                fontFamily = Sans,
                                fontSize = 14.sp,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                    else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(projects, key = { _, project -> project.id }) { index, project ->
                            CatalogRow(
                                index = index,
                                project = project,
                                lastChapter = progressOf(project.id),
                                onOpen = { onOpen(project) },
                                onRefresh = { onRefresh(project) },
                                onRemove = { pendingRemoval = project },
                            )
                        }
                        item {
                            Text(
                                "Your chapter files stay exactly where you keep them.",
                                fontFamily = Serif,
                                fontSize = 14.sp,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 28.dp, bottom = 36.dp),
                            )
                        }
                    }
                }
            }
            Hairline()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 72.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Micro("Read in place")
                Micro("間", accent = true)
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    pendingRemoval?.let { project ->
        RemoveProjectDialog(
            project = project,
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                onRemove(project)
                pendingRemoval = null
            },
        )
    }
}

@Composable
private fun CatalogRow(
    index: Int,
    project: Project,
    lastChapter: Progress?,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val position = lastChapter?.chapterIndex?.plus(1)
        ?.coerceIn(1, project.chapters.size.coerceAtLeast(1))
    val percent = if (position != null && project.chapters.isNotEmpty()) {
        (position * 100 / project.chapters.size).coerceIn(1, 100)
    } else {
        0
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .indication(interaction, null)
            .background(if (hovered) scheme.surfaceVariant else scheme.background)
            .plainClickable(onOpen)
            .padding(vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (index + 1).toString().padStart(2, '0'),
            fontFamily = Sans,
            fontSize = 11.sp,
            letterSpacing = 0.9.sp,
            color = scheme.secondary,
            modifier = Modifier.width(52.dp),
        )
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (lastChapter != null) {
                SealDot()
                Spacer(Modifier.width(10.dp))
            }
            Text(
                project.name,
                fontFamily = Serif,
                fontSize = 27.sp,
                lineHeight = 34.sp,
                letterSpacing = (-0.35).sp,
                fontWeight = FontWeight.Normal,
            )
        }
        Text(
            project.chapters.size.toString(),
            fontFamily = Sans,
            fontSize = 15.sp,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(104.dp),
        )
        Column(Modifier.width(132.dp), horizontalAlignment = Alignment.End) {
            Text(
                if (position == null) "NOT STARTED" else "CHAPTER ${position.toString().padStart(2, '0')}",
                fontFamily = Sans,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
                color = scheme.onSurfaceVariant,
            )
            Row(
                Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .width(62.dp)
                        .height(2.dp)
                        .background(scheme.outlineVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(percent / 100f)
                            .background(scheme.primary),
                    )
                }
                Text(
                    "$percent%",
                    fontFamily = Sans,
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
        Box {
            Text(
                "⋯",
                fontFamily = Sans,
                fontSize = 20.sp,
                lineHeight = 20.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(width = 32.dp, height = 34.dp)
                    .clickable { menuOpen = true },
                textAlign = TextAlign.End,
            )
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
                    text = { Text("Refresh chapters") },
                    onClick = { menuOpen = false; onRefresh() },
                )
                DropdownMenuItem(
                    text = { Text("Remove from library") },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        }
    }
    Hairline()
}

@Composable
private fun RemoveProjectDialog(
    project: Project,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RectangleShape,
            color = scheme.surfaceContainer,
            border = BorderStroke(1.dp, scheme.outlineVariant),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(Modifier.padding(24.dp)) {
                Micro("Library", accent = true)
                Text(
                    "Remove from library?",
                    fontFamily = Sans,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    "\"${project.name}\" will be removed from your library. The chapter files in the folder are not touched.",
                    fontFamily = Sans,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text("CANCEL", color = scheme.onSurfaceVariant, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("REMOVE", color = scheme.primary, modifier = Modifier.clickable(onClick = onConfirm).padding(8.dp))
                }
            }
        }
    }
}
