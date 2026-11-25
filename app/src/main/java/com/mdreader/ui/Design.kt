package com.mdreader.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

/** The one horizontal margin every screen shares. */
val ScreenPad = 28.dp

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
