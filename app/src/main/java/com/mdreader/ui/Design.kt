package com.mdreader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
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

/** Hairline with two quarter marks — a quiet piece of Swiss grid furniture. */
@Composable
fun GridRule(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = modifier
            .height(5.dp),
    ) {
        val stroke = 1.dp.toPx()
        drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), stroke)
        listOf(0.25f, 0.75f).forEach { fraction ->
            val x = size.width * fraction
            drawLine(color, Offset(x, 0f), Offset(x, 4.dp.toPx()), stroke)
        }
    }
}

/** Flat, square import affordance. No FAB, no shadow. */
@Composable
fun ImportAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .border(1.dp, MaterialTheme.colorScheme.onSurface),
            contentAlignment = Alignment.Center,
        ) {
            PlusGlyph()
        }
        Spacer(Modifier.width(11.dp))
        Text(
            text = "IMPORT FOLDER",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
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

@Composable
fun MetaDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(3.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
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

/** Exact plus sign: two centered hairlines instead of a font glyph. */
@Composable
fun PlusGlyph(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Canvas(
        modifier = modifier.size(10.dp),
    ) {
        val stroke = 1.dp.toPx()
        drawLine(
            color,
            Offset(size.width / 2f, 0f),
            Offset(size.width / 2f, size.height),
            stroke,
        )
        drawLine(
            color,
            Offset(0f, size.height / 2f),
            Offset(size.width, size.height / 2f),
            stroke,
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
