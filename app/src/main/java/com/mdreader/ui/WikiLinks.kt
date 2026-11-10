package com.mdreader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/**
 * [this] with every name the wiki knows made tappable. Untouched when there is no wiki.
 * [key] is the block, line or fact the text came from: the name matching and annotation building
 * are skipped when the same block is composed again (a scroll, a size change, a theme toggle).
 */
@Composable
fun AnnotatedString.withWikiLinks(key: Any? = null): AnnotatedString {
    val wiki = LocalWiki.current ?: return this
    val linkColor = MaterialTheme.colorScheme.primary
    val cacheKey = key ?: this
    return remember(cacheKey, wiki.index, linkColor) {
        val matches = wiki.index.find(text)
        if (matches.isEmpty()) {
            this
        } else {
            val style = TextLinkStyles(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            )
            val source = this
            buildAnnotatedString {
                append(source)
                matches.forEach { (start, end, entry) ->
                    addLink(LinkAnnotation.Clickable(entry.name, style) { wiki.open(entry) }, start, end)
                }
            }
        }
    }
}
