package com.mdreader.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

val ScreenPad = 32.dp
val RailWidth = 252.dp
val MeasureWidth = 672.dp
val Unit8 = 8.dp

val ColNo = 48.dp
val ColCh = 80.dp
val ColLast = 96.dp

val Sans = FontFamily.SansSerif
val Serif = FontFamily.Serif

@Composable
fun Micro(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text.uppercase(Locale.ROOT),
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.4.sp,
        color = if (accent) scheme.primary else scheme.secondary,
        textAlign = textAlign,
        modifier = modifier,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
fun GridRule(modifier: Modifier = Modifier) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    val tick = MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f)
    Canvas(modifier = modifier.fillMaxWidth().height(6.dp)) {
        val stroke = 1.dp.toPx()
        drawLine(hairline, Offset(0f, 0f), Offset(size.width, 0f), stroke)
        listOf(0.25f, 0.5f, 0.75f).forEach { f ->
            val x = size.width * f
            drawLine(tick, Offset(x, 0f), Offset(x, 5.dp.toPx()), stroke)
        }
    }
}

@Composable
fun PlusGlyph(color: Color = MaterialTheme.colorScheme.secondary) {
    Canvas(Modifier.size(10.dp)) {
        val stroke = 1.dp.toPx()
        drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), stroke)
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke)
    }
}

@Composable
fun ImportRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .indication(interaction, null)
            .background(if (hovered) scheme.surfaceVariant else scheme.surfaceContainerHigh)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .border(1.dp, scheme.secondary),
            contentAlignment = Alignment.Center,
        ) {
            PlusGlyph()
        }
        Spacer(Modifier.width(11.dp))
        Text(
            "IMPORT FOLDER",
            fontFamily = Sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            letterSpacing = 1.1.sp,
            color = scheme.onBackground,
        )
    }
}

@Composable
fun SealDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(7.dp)
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text = text.uppercase(Locale.ROOT),
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = if (!enabled) scheme.onSurfaceVariant.copy(alpha = 0.35f) else if (hovered) scheme.onBackground else scheme.secondary,
        modifier = modifier
            .hoverable(interaction, enabled = enabled)
            .indication(interaction, null)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(bottom = 4.dp),
    )
}

@Composable
fun ChromeButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border = when {
        !enabled -> scheme.outlineVariant.copy(alpha = 0.45f)
        hovered -> scheme.secondary
        else -> scheme.outlineVariant
    }
    val fg = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.35f)
        hovered -> scheme.secondary
        else -> scheme.onBackground
    }
    Text(
        text = text.uppercase(Locale.ROOT),
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = fg,
        modifier = modifier
            .hoverable(interaction, enabled = enabled)
            .indication(interaction, null)
            .border(1.dp, border)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
fun CatalogHeaderRow() {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainer)
            .padding(horizontal = ScreenPad, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Micro("No.", modifier = Modifier.width(ColNo))
        Micro("Title", modifier = Modifier.weight(1f))
        Micro("Ch.", modifier = Modifier.width(ColCh), textAlign = TextAlign.End)
        Micro("Last", modifier = Modifier.width(ColLast), textAlign = TextAlign.End)
    }
    Hairline()
}

fun Modifier.readerBaselineGrid(lineEvery: Dp = 32.dp): Modifier = composed {
    val line = MaterialTheme.colorScheme.secondary.copy(alpha = 0.06f)
    drawBehind {
        val step = lineEvery.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
    }
}

fun Modifier.plainClickable(onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    clickable(interactionSource = interaction, indication = null, onClick = onClick)
}
