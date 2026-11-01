package com.mdreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mdreader.data.online.FreeWebNovelClient
import com.mdreader.data.online.NovelHit
import kotlinx.coroutines.CancellationException

/** Where an author page was opened from, so Back can return to that novel. Survives rotation. */
data class AuthorTrail(val name: String, val path: String, val origin: String?) : java.io.Serializable

/** The novels loaded so far for one author; kept by the caller so returning doesn't refetch. */
class AuthorList {
    var hits by mutableStateOf<List<NovelHit>>(emptyList())
    var page by mutableIntStateOf(0)
    var ended by mutableStateOf(false)
}

/**
 * Every novel by one author, laid out like the chapter list on the detail page: the same title
 * block, the same number column, serif titles over a Micro line, hairlines between rows. Further
 * pages load as the end of the list scrolls into view.
 */
@Composable
fun AuthorScreen(
    name: String,
    path: String,
    list: AuthorList,
    onBack: () -> Unit,
    onOpen: (NovelHit) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    BackHandler(onBack = onBack)
    var loading by remember(path) { mutableStateOf(false) }
    var error by remember(path) { mutableStateOf<String?>(null) }

    suspend fun load() {
        if (loading) return
        loading = true
        error = null
        try {
            val next = FreeWebNovelClient.loadAuthor(path, list.page + 1)
            val fresh = next.hits.filter { hit -> list.hits.none { it.path == hit.path } }
            list.hits = list.hits + fresh
            list.page++
            // A repeated page means the site ran out; so does a page without a link to the next.
            list.ended = fresh.isEmpty() || !next.hasNext
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this author."
        } finally {
            loading = false
        }
    }

    // The footer only exists while there is more to fetch, so it is also the "near the end" signal.
    val wantMore = !list.ended && error == null
    LaunchedEffect(path, list.page, wantMore) { if (wantMore) load() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        item {
            TitleBlock(onBack = onBack, title = name) {
                Spacer(Modifier.height(8.dp))
                Micro(text = if (list.ended) "Novels · ${list.hits.size}" else "Novels")
            }
            // 24 dp under the block, the same as the 24 dp above it.
            Column(modifier = Modifier.padding(horizontal = ScreenPad).padding(top = 24.dp)) { Hairline() }
        }
        itemsIndexed(list.hits, key = { _, hit -> hit.path }) { index, hit ->
            Column(modifier = Modifier.padding(horizontal = ScreenPad)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(hit) }
                        // The 32 sp title line already carries about 7 dp of leading above the
                        // glyphs, so the top is 6 dp shorter than the bottom to look even.
                        .padding(top = 8.dp, bottom = 14.dp),
                ) {
                    // 48 dp: titles start on the same x (76 dp) as the title block's text.
                    // The number is centred on the first 32 sp title line (9 dp + half of 14).
                    Text(
                        text = (index + 1).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.width(48.dp).padding(top = 9.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = hit.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (hit.meta.isNotEmpty()) Micro(text = hit.meta)
                    }
                }
                Hairline()
            }
        }
        item {
            Column(modifier = Modifier.padding(horizontal = ScreenPad, vertical = 20.dp)) {
                when {
                    error != null -> {
                        Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Text(
                            text = "TRY AGAIN",
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.primary,
                            modifier = Modifier
                                .clickable { error = null }
                                .padding(vertical = 12.dp),
                        )
                    }

                    !list.ended -> Row(
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Spinner in the number column, text on the title edge.
                        Box(modifier = Modifier.width(48.dp)) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = scheme.primary,
                                strokeWidth = 1.5.dp,
                            )
                        }
                        Micro(text = "Loading…")
                    }

                    list.hits.isEmpty() -> Text(
                        text = "No novels found for this author.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
