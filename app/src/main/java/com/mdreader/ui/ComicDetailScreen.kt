package com.mdreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mdreader.data.Project
import com.mdreader.data.comics.ComicChapter
import com.mdreader.data.comics.ComicDetails
import com.mdreader.data.comics.ComicSource
import com.mdreader.data.comics.ComicSources
import java.util.Locale

/** What the comic page shows while its details and chapter list load. */
data class ComicDetailState(
    val sourceId: String? = null,
    val path: String = "",
    val title: String = "",
    /** The search hit's cover, so the hero renders before details arrive. */
    val coverUrl: String = "",
    val details: ComicDetails? = null,
    val chapters: List<ComicChapter> = emptyList(),
    /** True from the moment details render until the chapter list lands. */
    val chaptersLoading: Boolean = false,
    /** Why the chapter list failed, when the poster itself is already up. */
    val chaptersError: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val inLibrary: Boolean = false,
    val project: Project? = null,
    val resumeIndex: Int? = null,
) {
    val source: ComicSource? get() = ComicSources.byId(sourceId)
}

/** What the comic page shows about saving to the user's own storage folder. */
data class ComicSaveState(
    /** The picked folder's name, or null before the first pick. */
    val rootName: String? = null,
    val running: Boolean = false,
    /** The chapter currently being written, as its issue number. */
    val chapterLabel: String = "",
    val chapterDone: Int = 0,
    val chapterTotal: Int = 0,
    val pageDone: Int = 0,
    val pageTotal: Int = 0,
    val finished: String? = null,
    val error: String? = null,
)

/**
 * A comic's poster page in the Kuro skin: cover full-bleed with the title over its own scrim,
 * a ticker of facts, one paper-white Continue button, then every chapter by issue number.
 * Nothing of the novel page's paper grid survives here.
 */
@Composable
fun ComicDetailScreen(
    state: ComicDetailState,
    storage: ComicSaveState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRead: (Int) -> Unit,
    onContinue: () -> Unit,
    onToggleLibrary: () -> Unit,
    onOpenLibrary: () -> Unit,
    onPickStorage: () -> Unit,
    onSave: (Int?) -> Unit,
    onStopSave: () -> Unit,
) {
    ComicSystemBars()
    BackHandler { onBack() }
    val details = state.details
    val source = state.source
    val chapters = state.chapters
    val coverUrl = details?.cover ?: state.coverUrl.ifEmpty { state.project?.coverUrl.orEmpty() }
    // The hero fills the screen width; decode it at the panel scale, not the shelf's 720.
    val cover = rememberComicCover(source, coverUrl.ifEmpty { null }, maxWidth = 1080)
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ComicSkin.Stage)
            .statusBarsPadding(),
    ) {
        when {
            state.error != null && details == null -> Column(
                modifier = Modifier.padding(horizontal = ScreenPad, vertical = 24.dp),
            ) {
                ComicBackArrow(onBack)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ComicSkin.Sub,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "TRY AGAIN",
                    style = MaterialTheme.typography.labelLarge,
                    color = ComicSkin.Accent,
                    modifier = Modifier.clickable(onClick = onRetry).padding(vertical = 8.dp),
                )
            }

            details == null -> Column(
                modifier = Modifier.padding(horizontal = ScreenPad, vertical = 24.dp),
            ) {
                ComicBackArrow(onBack)
                Spacer(Modifier.height(24.dp))
                Micro(text = state.title.ifEmpty { "Comic" }, color = ComicSkin.Sub)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Loading…",
                    style = MaterialTheme.typography.titleLarge,
                    color = ComicSkin.Sub,
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            ) {
                item {
                    // The poster: the cover full-bleed, the title riding its own scrim.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp),
                    ) {
                        if (cover != null) {
                            Image(
                                bitmap = cover,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(Modifier.fillMaxSize().background(ComicSkin.Panel))
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.35f),
                                        0.32f to Color.Transparent,
                                        0.6f to Color.Transparent,
                                        1f to ComicSkin.Stage,
                                    ),
                                ),
                        )
                        ComicBackArrow(
                            onBack = onBack,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 8.dp),
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = ScreenPad, vertical = 24.dp),
                        ) {
                            source?.let {
                                Text(
                                    text = it.name.uppercase(Locale.ROOT),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ComicSkin.Stage,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .background(ComicSkin.Accent)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                            Text(
                                text = details.title,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = 26.sp,
                                    lineHeight = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = Color.White,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (details.author.isNotEmpty()) {
                                Spacer(Modifier.height(7.dp))
                                Text(
                                    text = details.author.uppercase(Locale.ROOT),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ComicSkin.Sub,
                                )
                            }
                        }
                    }

                    if (details.genres.isNotEmpty()) {
                        Row(
                            modifier = Modifier.padding(horizontal = ScreenPad).padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            details.genres.take(4).forEach { genre ->
                                Text(
                                    text = genre.uppercase(Locale.ROOT),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ComicSkin.Sub,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .border(1.dp, ComicSkin.LineSoft)
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Hairline(color = ComicSkin.Line)
                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        ComicStat("Chapters", chapters.size.toString(), Modifier.weight(1f))
                        Box(Modifier.width(1.dp).fillMaxHeight().background(ComicSkin.Line))
                        ComicStat(
                            "Status",
                            details.status.ifEmpty { "—" },
                            Modifier.weight(1f),
                        )
                        Box(Modifier.width(1.dp).fillMaxHeight().background(ComicSkin.Line))
                        val read = state.resumeIndex?.let {
                            ((it + 1) * 100 / chapters.size.coerceAtLeast(1)).coerceIn(1, 100)
                        }
                        ComicStat(
                            "Read",
                            read?.let { "$it%" } ?: "—",
                            Modifier.weight(1f),
                            accent = read != null,
                        )
                    }
                    Hairline(color = ComicSkin.Line)

                    Spacer(Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .padding(horizontal = ScreenPad)
                            .fillMaxWidth()
                            .background(ComicSkin.Ink)
                            .clickable {
                                if (state.resumeIndex != null) onContinue() else onRead(0)
                            }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when {
                                state.resumeIndex != null -> "CONTINUE · #${(state.resumeIndex + 1).toString().padStart(3, '0')}"
                                chapters.isNotEmpty() -> "READ FIRST"
                                else -> "LOADING CHAPTERS…"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = ComicSkin.Stage,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    if (!state.chaptersLoading) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = ScreenPad)
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .border(1.dp, ComicSkin.LineSoft)
                                    .clickable(onClick = onToggleLibrary)
                                    .padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (state.inLibrary) "✓ IN LIBRARY" else "ADD TO LIBRARY",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ComicSkin.Ink,
                                )
                            }
                            if (state.inLibrary) {
                                Spacer(Modifier.width(12.dp))
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(1.dp, ComicSkin.LineSoft)
                                        .clickable(onClick = onOpenLibrary)
                                        .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "OPEN",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = ComicSkin.Ink,
                                    )
                                }
                            }
                        }
                    }

                    // Plain files in a folder of the user's own: pick it once, save whole chapters.
                    Spacer(Modifier.height(24.dp))
                    Column(modifier = Modifier.padding(horizontal = ScreenPad)) {
                        Micro(text = "Storage", color = ComicSkin.Accent)
                        Spacer(Modifier.height(10.dp))
                        if (storage.rootName == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, ComicSkin.LineSoft)
                                    .clickable(onClick = onPickStorage)
                                    .padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "SAVE TO A FOLDER IN STORAGE",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ComicSkin.Ink,
                                )
                            }
                        } else if (storage.running) {
                            Micro(
                                text = "${storage.chapterLabel} · page ${storage.pageDone}/${storage.pageTotal}",
                                color = ComicSkin.Sub,
                            )
                            Spacer(Modifier.height(6.dp))
                            val fraction = if (storage.pageTotal > 0) {
                                (storage.pageDone.toFloat() / storage.pageTotal).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(ComicSkin.Line)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction)
                                        .fillMaxHeight()
                                        .background(ComicSkin.Accent),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Micro(
                                    text = "Chapter ${storage.chapterDone + 1} of ${storage.chapterTotal} · ${storage.rootName}",
                                    color = ComicSkin.Sub,
                                )
                                Text(
                                    text = "STOP",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ComicSkin.Accent,
                                    modifier = Modifier
                                        .clickable(onClick = onStopSave)
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                )
                            }
                        } else {
                            Micro(text = "Folder · ${storage.rootName}", color = ComicSkin.Sub)
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                listOf("NEXT 10" to 10, "NEXT 50" to 50, "ALL" to null).forEach { (label, count) ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .border(1.dp, ComicSkin.LineSoft)
                                            .clickable { onSave(count) }
                                            .padding(vertical = 13.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = ComicSkin.Ink,
                                        )
                                    }
                                }
                            }
                            storage.error?.let {
                                Spacer(Modifier.height(8.dp))
                                Micro(text = it, color = ComicSkin.Sub)
                            }
                            storage.finished?.let {
                                Spacer(Modifier.height(8.dp))
                                Micro(text = it, color = ComicSkin.Sub)
                            }
                        }
                    }

                    if (details.description.isNotEmpty()) {
                        var expanded by remember { mutableStateOf(false) }
                        Column(modifier = Modifier.padding(horizontal = ScreenPad).padding(top = 22.dp)) {
                            Micro(text = "Synopsis", color = ComicSkin.Accent)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = details.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ComicSkin.Sub,
                                maxLines = if (expanded) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { expanded = !expanded },
                            )
                            Spacer(Modifier.height(8.dp))
                            Micro(
                                text = if (expanded) "Show less" else "Read more",
                                color = ComicSkin.Accent,
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
                        Micro(text = "Chapters · ${chapters.size}", color = ComicSkin.Sub)
                        Micro(text = "Newest first", color = ComicSkin.Sub)
                    }
                    Hairline(color = ComicSkin.Line)
                }

                if (state.chaptersLoading) {
                    item {
                        Text(
                            text = "Loading chapters…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ComicSkin.Sub,
                            modifier = Modifier.padding(horizontal = ScreenPad, vertical = 24.dp),
                        )
                    }
                } else {
                    itemsIndexed(chapters.reversed(), key = { _, chapter -> chapter.path }) { shownIndex, chapter ->
                        val index = chapters.lastIndex - shownIndex
                        val resume = state.resumeIndex
                        val read = resume != null && index < resume
                        Column(modifier = Modifier.padding(horizontal = ScreenPad)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onRead(index) }
                                    .padding(vertical = 14.dp),
                            ) {
                                Text(
                                    text = "#${(index + 1).toString().padStart(3, '0')}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ComicSkin.Accent,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.width(48.dp).padding(top = 9.dp),
                                )
                                Text(
                                    text = chapter.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (read) ComicSkin.Sub else ComicSkin.Ink,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Hairline(color = ComicSkin.Line)
                        }
                    }

                    if (chapters.isEmpty()) {
                        item {
                            Column(modifier = Modifier.padding(horizontal = ScreenPad, vertical = 24.dp)) {
                                Text(
                                    text = state.chaptersError ?: "This comic has no chapters yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ComicSkin.Sub,
                                )
                                if (state.chaptersError != null) {
                                    Spacer(Modifier.height(14.dp))
                                    Text(
                                        text = "TRY AGAIN",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = ComicSkin.Accent,
                                        modifier = Modifier
                                            .clickable(onClick = onRetry)
                                            .padding(vertical = 8.dp),
                                    )
                                }
                            }
                        }
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
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Micro(text = label, color = ComicSkin.Sub)
        Spacer(Modifier.width(7.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = if (accent) ComicSkin.Accent else ComicSkin.Ink,
        )
    }
}
