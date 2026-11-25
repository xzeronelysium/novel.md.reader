package com.mdreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mdreader.data.Project
import com.mdreader.data.comics.ComicChapter
import com.mdreader.data.comics.ComicDetails
import com.mdreader.data.comics.ComicSource
import com.mdreader.data.comics.ComicSources

/** What the comic page shows while its details and chapter list load. */
data class ComicDetailState(
    val sourceId: String? = null,
    val path: String = "",
    val title: String = "",
    val details: ComicDetails? = null,
    val chapters: List<ComicChapter> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val inLibrary: Boolean = false,
    val project: Project? = null,
    val resumeIndex: Int? = null,
) {
    val source: ComicSource? get() = ComicSources.byId(sourceId)
}

/**
 * A comic's page: cover, facts, one Continue button, then every chapter. Sibling of the novel
 * detail page — same grid, same hairlines, no per-page downloads.
 */
@Composable
fun ComicDetailScreen(
    state: ComicDetailState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRead: (Int) -> Unit,
    onContinue: () -> Unit,
    onToggleLibrary: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    BackHandler { onBack() }
    val details = state.details
    val source = state.source
    val chapters = state.chapters
    val coverUrl = details?.cover ?: state.project?.coverUrl.orEmpty()
    val cover = rememberComicCover(source, coverUrl.ifEmpty { null })
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding(),
    ) {
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

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            ) {
                item {
                    Column(modifier = Modifier.coverField(cover, FieldFade.Detail)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = ScreenPad, top = 4.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            BackArrow(onBack)
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp, top = 12.dp)) {
                                Text(
                                    text = details.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = scheme.onSurface,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (details.author.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Micro(text = details.author, accent = true)
                                }
                            }
                        }

                        if (details.genres.isNotEmpty()) {
                            Spacer(Modifier.height(20.dp))
                            Row(
                                modifier = Modifier.padding(horizontal = ScreenPad),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                details.genres.take(4).forEach { genre ->
                                    Text(
                                        text = genre.uppercase(),
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
                            ComicStat("Chapters", chapters.size.toString(), Modifier.weight(1f))
                            Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
                            ComicStat(
                                "Status",
                                details.status.ifEmpty { "—" },
                                Modifier.weight(1f).padding(start = 12.dp),
                            )
                            Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
                            val read = state.resumeIndex?.let {
                                ((it + 1) * 100 / chapters.size.coerceAtLeast(1)).coerceIn(1, 100)
                            }
                            ComicStat(
                                "Read",
                                read?.let { "$it%" } ?: "—",
                                Modifier.weight(1f).padding(start = 12.dp),
                                accent = read != null,
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
                            text = when {
                                state.resumeIndex != null -> "CONTINUE · CH. ${state.resumeIndex + 1}"
                                chapters.isNotEmpty() -> "READ FIRST"
                                else -> "LOADING CHAPTERS…"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.background,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

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

                    if (details.description.isNotEmpty()) {
                        var expanded by remember { mutableStateOf(false) }
                        Column(modifier = Modifier.padding(horizontal = ScreenPad).padding(top = 22.dp)) {
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

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ScreenPad)
                            .padding(top = 26.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Micro(text = "Chapters · ${chapters.size}")
                        Micro(text = "Newest first")
                    }
                    Hairline(modifier = Modifier.padding(horizontal = ScreenPad))
                }

                itemsIndexed(chapters.reversed(), key = { _, chapter -> chapter.path }) { shownIndex, chapter ->
                    val index = chapters.lastIndex - shownIndex
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

                if (chapters.isEmpty() && !state.loading) {
                    item {
                        Text(
                            text = "This comic has no chapters yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = ScreenPad, vertical = 24.dp),
                        )
                    }
                }

                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

@Composable
private fun ComicStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    Column(modifier = modifier.padding(vertical = 14.dp)) {
        Micro(text = label)
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
