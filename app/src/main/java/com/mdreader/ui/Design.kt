package com.mdreader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** Hairline arrow drawn on the pixel grid, so it stays optically centered. */
@Composable
fun QuietArrow(
    direction: ArrowDirection,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Canvas(
        modifier = modifier.size(width = 14.dp, height = 10.dp),
    ) {
        val stroke = 1.1.dp.toPx()
        val middle = size.height / 2f
        val startX = if (direction == ArrowDirection.Left) size.width else 0f
        val tipX = if (direction == ArrowDirection.Left) 0f else size.width
        val wingX = if (direction == ArrowDirection.Left) 4.dp.toPx() else size.width - 4.dp.toPx()

        drawLine(color, Offset(startX, middle), Offset(tipX, middle), stroke, cap = StrokeCap.Round)
        drawLine(
            color,
            Offset(tipX, middle),
            Offset(wingX, middle - 3.dp.toPx()),
            stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(tipX, middle),
            Offset(wingX, middle + 3.dp.toPx()),
            stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun SearchGlyph(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Canvas(
        modifier = modifier.size(13.dp),
    ) {
        val stroke = 1.dp.toPx()
        val radius = size.minDimension * 0.32f
        val center = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color, radius = radius, center = center, style = Stroke(width = stroke))
        drawLine(
            color,
            start = Offset(center.x + radius * 0.72f, center.y + radius * 0.72f),
            end = Offset(size.width, size.height),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
