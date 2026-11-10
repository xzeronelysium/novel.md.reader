package com.mdreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mdreader.data.BibleStore
import com.mdreader.data.WikiEntry
import com.mdreader.data.WikiFact
import com.mdreader.data.WikiIndex
import com.mdreader.data.WikiPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the sheet is showing: one entry, or (null) the wiki's front page. */
private data class Screen(val entry: WikiEntry?)

private val KIND_LABEL = mapOf("people" to "PERSON", "places" to "PLACE", "systems" to "SYSTEM")

/**
 * The novel's wiki beside the text: a person, place or system as far as the reader has got
 * (nothing cited past [upTo]), with names inside it tappable too. A null [start] opens the front page.
 * Flat, square and hairline-ruled like the rest of the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WikiSheet(bible: BibleStore, index: WikiIndex, upTo: Int, start: WikiEntry?, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val stack = remember { mutableStateListOf(Screen(start)) }
    val current = stack.last()
    val wiki = remember(index) { WikiLinks(index) { stack.add(Screen(it)) } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape,
        containerColor = scheme.surfaceContainer,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        tonalElevation = 0.dp,
        dragHandle = { SheetGrip() },
    ) {
        CompositionLocalProvider(LocalWiki provides wiki) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
                // Top row: where you are, and the way out of it.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = ScreenPad, end = ScreenPad - 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Micro(
                        text = if (current.entry == null) "Wiki" else KIND_LABEL[current.entry.kind] ?: current.entry.kind,
                        accent = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (stack.size > 1) Action("Back") { stack.removeAt(stack.lastIndex) }
                    if (current.entry != null) Action("All") { stack.clear(); stack.add(Screen(null)) }
                }
                if (current.entry == null) {
                    FrontPage(bible, index, upTo) { stack.add(Screen(it)) }
                } else {
                    val entry = current.entry
                    val page by produceState<WikiPage?>(null, entry, upTo) {
                        value = withContext(Dispatchers.IO) { bible.page(entry.kind, entry.name, upTo) }
                    }
                    PageBody(entry, page, upTo)
                }
            }
        }
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Box(Modifier.height(44.dp).clickable(onClick = onClick).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Micro(text = label, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The spoiler guard, said once and quietly: a vermilion square and the chapter the wiki stops at. */
@Composable
private fun KnownThrough(upTo: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.primary))
        Micro(text = "Known through chapter $upTo · nothing later")
    }
}

@Composable
private fun FrontPage(bible: BibleStore, index: WikiIndex, upTo: Int, open: (WikiEntry) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val story by produceState(emptyList<String>(), upTo) { value = withContext(Dispatchers.IO) { bible.storySoFar(upTo) } }
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val groups = listOf("people" to "People", "places" to "Places", "systems" to "Systems")

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = ScreenPad, end = ScreenPad, top = 6.dp, bottom = 40.dp)) {
        item {
            Text("Wiki", style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp), color = scheme.onSurface)
            Box(Modifier.padding(top = 8.dp, bottom = 16.dp)) { KnownThrough(upTo) }
            if (!index.isEmpty()) {
                Column(Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        decorationBox = { inner ->
                            if (query.isEmpty()) Text("Search names", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                            inner()
                        },
                    )
                }
            }
        }
        groups.forEach { (kind, label) ->
            val entries = index.entries.filter { e ->
                e.kind == kind && (q.isEmpty() || e.name.lowercase().contains(q) || e.aliases.any { it.lowercase().contains(q) })
            }
            if (entries.isEmpty()) return@forEach
            item(key = "h-$kind") { SectionHead(label, entries.size) }
            items(entries, key = { "${it.kind}/${it.name}" }) { e ->
                Column(Modifier.fillMaxWidth().clickable { open(e) }) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text(e.name, style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 24.sp), color = scheme.onSurface)
                        if (e.aliases.isNotEmpty()) {
                            Text(
                                e.aliases.take(3).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    Hairline()
                }
            }
        }
        if (q.isEmpty() && story.isNotEmpty()) {
            item(key = "h-story") { SectionHead("Story so far", story.size) }
            items(story) { line ->
                val chapter = Regex("""^Ch\s*(\d+)\s*:\s*""", RegexOption.IGNORE_CASE).find(line)
                Row(Modifier.padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(34.dp)) { Micro(text = chapter?.groupValues?.get(1) ?: "", accent = true) }
                    Text(
                        text = AnnotatedString(chapter?.let { line.removeRange(it.range) } ?: line).withWikiLinks(line),
                        style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 15.sp, lineHeight = 23.sp),
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (index.isEmpty()) {
            item {
                Text(
                    "Nothing here yet. The wiki fills as chapters are read into the novel's memory.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHead(label: String, count: Int? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 26.dp)) {
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro(text = label, color = MaterialTheme.colorScheme.onSurface)
            if (count != null) Micro(text = count.toString())
        }
        Hairline()
    }
}

@Composable
private fun PageBody(entry: WikiEntry, page: WikiPage?, upTo: Int) {
    val scheme = MaterialTheme.colorScheme
    var showEarlier by rememberSaveable(entry.name) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = ScreenPad, end = ScreenPad, top = 6.dp, bottom = 48.dp)) {
        item {
            Text(entry.name, style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.4).sp), color = scheme.onSurface)
            val aliases = page?.aliases.orEmpty()
            if (aliases.isNotEmpty()) {
                Text(
                    "also ${aliases.joinToString(" · ")}",
                    style = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 16.sp, lineHeight = 22.sp),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                page?.let { Micro(text = "First seen ch ${it.firstSeen}") }
                KnownThrough(upTo)
            }
        }
        if (page == null) {
            item { Text("Nothing known yet.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp)) }
            return@LazyColumn
        }
        page.now?.let { now ->
            item(key = "now") {
                // The state panel echoes the reader's system panel: flat, vermilion edge.
                Row(Modifier.fillMaxWidth().padding(top = 22.dp).height(IntrinsicSize.Min).background(scheme.surfaceContainerHigh)) {
                    Box(Modifier.fillMaxHeight().width(2.dp).background(scheme.primary))
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Micro(text = "Now · ch ${now.chapter}", accent = true)
                        Text(AnnotatedString(now.text).withWikiLinks(now), style = FactStyle(), color = scheme.onSurface)
                    }
                }
            }
        }
        val sections = listOf(
            "Role" to page.known("role"),
            "Appearance" to page.known("appearance"),
            "Personality" to page.known("personality") + page.voice,
            "Abilities" to page.known("ability"),
            "Rules" to page.known("rules"),
            "Relationships" to page.known("relation"),
            "Background" to page.known("background"),
            "Also known" to page.known(null),
        )
        sections.forEach { (label, facts) ->
            if (facts.isEmpty()) return@forEach
            item(key = "h-$label") { SectionHead(label) }
            items(facts.sortedBy { it.chapter }) { FactRow(it) }
        }
        if (page.inferred.isNotEmpty()) {
            item(key = "h-inf") { SectionHead("Inferred · not stated outright") }
            items(page.inferred) { FactRow(it, inferred = true) }
        }
        if (page.history.isNotEmpty()) {
            item(key = "h-earlier") {
                Column(Modifier.fillMaxWidth().padding(top = 26.dp).clickable { showEarlier = !showEarlier }) {
                    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Micro(text = "Earlier states", color = scheme.onSurface)
                        Micro(text = page.history.size.toString())
                        Micro(text = if (showEarlier) "hide" else "show", accent = true)
                    }
                    Hairline()
                }
            }
            if (showEarlier) items(page.history.reversed()) { FactRow(it) }
        }
    }
}

@Composable
private fun FactStyle() = TextStyle(fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 25.sp)

@Composable
private fun FactRow(fact: WikiFact, inferred: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(34.dp).padding(top = 5.dp)) { Micro(text = fact.chapter.toString(), accent = true) }
        Text(
            text = AnnotatedString(fact.text).withWikiLinks(fact),
            style = FactStyle().copy(fontStyle = if (inferred) FontStyle.Italic else FontStyle.Normal),
            color = if (inferred) scheme.onSurfaceVariant else scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
