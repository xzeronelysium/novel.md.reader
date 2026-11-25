package com.mdreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

/** The one horizontal margin every screen shares. */
val ScreenPad = 28.dp

/*
 * Kuro: the comic skin. Novels live on warm paper with hairlines; comics live
 * on an always-black stage whatever the system theme says — artwork first,
 * paper-white ink, vermilion issue numbers, no light mode.
 */
object ComicSkin {
    /** The poster page and sheets. */
    val Stage = Color(0xFF0D0D0F)

    /** The reader's deeper black, so pages read edge-to-edge. */
    val PageStage = Color(0xFF000000)

    /** Loading placeholders and empty poster backs. */
    val Panel = Color(0xFF131316)

    /** Paper-white text, the inverse of the novel's ink. */
    val Ink = Color(0xFFF2EFE8)

    /** Quiet metadata on black. */
    val Sub = Color(0xFF8E8B85)

    /** The dark-theme vermilion, bright enough for a black stage. */
    val Accent = Color(0xFFE05A44)

    /** Hairline on black. */
    val Line = Color(0xFF242427)

    /** Chip and outline borders on black. */
    val LineSoft = Color(0x59FFFFFF)
}

/** Small uppercase metadata, the Swiss label of the layout. */
@Composable
fun Micro(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    accent: Boolean = false,
) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelMedium,
        color = if (accent) MaterialTheme.colorScheme.primary else color,
        modifier = modifier,
    )
}

/** The site's score and vote count in one quiet line: `4.6 / 5 · 1,940 votes`, score in the accent. */
@Composable
fun RatingLine(rating: String, votes: Int) {
    val count = if (votes == 1) "1 vote" else "%,d votes".format(votes)
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Rated $rating out of 5, $count"
        },
    ) {
        Micro(text = rating, accent = true)
        Micro(text = " / 5 · $count")
    }
}

/** A search hit's line under its title: the score in the accent colour, then type and genres. */
@Composable
fun HitMeta(rating: String, meta: String) {
    if (rating.isEmpty() && meta.isEmpty()) return
    Row {
        if (rating.isNotEmpty()) Micro(text = rating, accent = true)
        if (meta.isNotEmpty()) Micro(text = if (rating.isEmpty()) meta else " · $meta")
    }
}

@Composable
fun Hairline(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = color,
    )
}

@Composable
fun SealDot(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .size(7.dp)
            .background(color),
    )
}

enum class ArrowDirection { Left, Right }

/** Platform back and forward icons. They mirror in right-to-left layouts. */
@Composable
fun QuietArrow(
    direction: ArrowDirection,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Icon(
        imageVector = if (direction == ArrowDirection.Left) {
            Icons.AutoMirrored.Filled.ArrowBack
        } else {
            Icons.AutoMirrored.Filled.ArrowForward
        },
        contentDescription = null,
        modifier = modifier.size(22.dp),
        tint = color,
    )
}

/** Back arrow in the comic skin's paper-white ink, for the always-black comic screens. */
@Composable
internal fun ComicBackArrow(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clickable(onClick = onBack)
            .semantics {
                contentDescription = "Back"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        QuietArrow(direction = ArrowDirection.Left, color = ComicSkin.Ink)
    }
}

@Composable
fun SearchGlyph(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Icon(
        imageVector = Icons.Filled.Search,
        contentDescription = null,
        modifier = modifier.size(20.dp),
        tint = color,
    )
}
