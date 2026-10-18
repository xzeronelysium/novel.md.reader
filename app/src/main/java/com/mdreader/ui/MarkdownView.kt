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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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
    val typography = MaterialTheme.typography
    val bodyStyle = typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 30.sp)
    val colors = MaterialTheme.colorScheme

    when (block) {
        is Block.Heading -> {
            val style = when (block.level) {
                1 -> typography.headlineSmall
                2 -> typography.titleLarge
                3 -> typography.titleMedium
                else -> typography.titleSmall
            }
            Text(
                text = block.text,
                style = style,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 4.dp),
            )
        }

        is Block.Paragraph -> Text(
            text = block.spans.toAnnotatedString(),
            style = bodyStyle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )

        is Block.Bullet -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "\u2022", style = bodyStyle)
            Text(
                text = block.spans.toAnnotatedString(),
                style = bodyStyle,
                modifier = Modifier.weight(1f),
            )
        }

        is Block.Numbered -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "${block.marker}.",
                style = bodyStyle,
                modifier = Modifier.widthIn(min = 24.dp),
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
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(colors.outlineVariant, RoundedCornerShape(2.dp)),
            )
            Text(
                text = block.spans.toAnnotatedString(),
                style = bodyStyle.copy(fontStyle = FontStyle.Italic),
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        Block.Rule -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 18.dp),
            color = colors.outlineVariant,
        )

        is Block.Code -> Surface(
            color = colors.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = block.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}
