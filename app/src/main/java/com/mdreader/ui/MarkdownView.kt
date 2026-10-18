package com.mdreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import com.mdreader.md.Block
import com.mdreader.md.InlineSpan

@Composable
fun List<InlineSpan>.toAnnotatedString(): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return buildAnnotatedString {
        this@toAnnotatedString.forEach { span ->
            withStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                    background = if (span.code) codeBackground else Color.Unspecified,
                )
            ) {
                append(span.text)
            }
        }
    }
}

@Composable
fun BlockView(block: Block) {
    val colors = MaterialTheme.colorScheme
    val bodyStyle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.1.sp,
        color = colors.onSurface,
    )

    when (block) {
        is Block.Heading -> {
            val style = when (block.level) {
                1 -> TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Normal,
                    fontSize = 26.sp,
                    lineHeight = 34.sp,
                    letterSpacing = (-0.4).sp,
                )
                2 -> TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 21.sp,
                    lineHeight = 28.sp,
                    letterSpacing = (-0.2).sp,
                )
                else -> TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    letterSpacing = 0.1.sp,
                )
            }
            Text(
                text = block.text,
                style = style,
                color = colors.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 26.dp, bottom = 8.dp),
            )
        }

        is Block.Paragraph -> Text(
            text = block.spans.toAnnotatedString(),
            style = bodyStyle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 9.dp),
        )

        is Block.Bullet -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "•",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 11.dp),
            )
            Text(
                text = block.spans.toAnnotatedString(),
                style = bodyStyle,
                modifier = Modifier.weight(1f),
            )
        }

        is Block.Numbered -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = block.marker,
                style = bodyStyle.copy(
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                ),
                modifier = Modifier
                    .widthIn(min = 20.dp)
                    .padding(top = 4.dp),
            )
            Text(
                text = block.spans.toAnnotatedString(),
                style = bodyStyle,
                modifier = Modifier.weight(1f),
            )
        }

        is Block.Quote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(colors.outline),
            )
            Text(
                text = block.spans.toAnnotatedString(),
                style = bodyStyle.copy(
                    fontStyle = FontStyle.Italic,
                    color = colors.onSurfaceVariant,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp),
            )
        }

        Block.Rule -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 22.dp),
            thickness = 1.dp,
            color = colors.outlineVariant,
        )

        is Block.Code -> Surface(
            color = colors.surfaceVariant,
            shape = RectangleShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        ) {
            Text(
                text = block.text,
                color = colors.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(14.dp),
            )
        }
    }
}
