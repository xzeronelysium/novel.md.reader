package com.mdreader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.mdreader.data.WikiEntry
import com.mdreader.data.WikiIndex

/** The wiki's names for the text on screen, and what a tap on one does. */
class WikiLinks(val index: WikiIndex, val open: (WikiEntry) -> Unit)

val LocalWiki = staticCompositionLocalOf<WikiLinks?> { null }

/** [this] with every name the wiki knows made tappable. Untouched when there is no wiki. */
@Composable
fun AnnotatedString.withWikiLinks(): AnnotatedString {
    val wiki = LocalWiki.current ?: return this
    val matches = wiki.index.find(text)
    if (matches.isEmpty()) return this
    val style = TextLinkStyles(
        SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
    )
    val source = this
    return buildAnnotatedString {
        append(source)
        matches.forEach { (start, end, entry) ->
            addLink(LinkAnnotation.Clickable(entry.name, style) { wiki.open(entry) }, start, end)
        }
    }
}
