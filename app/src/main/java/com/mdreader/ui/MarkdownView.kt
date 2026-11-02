package com.mdreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import com.mdreader.md.ChatMessage
import com.mdreader.md.chatMessages
import com.mdreader.md.isSystemDisplay

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

    // System and status screens get their own flat panel, whatever Markdown they arrived in.
    if (block.isSystemDisplay()) {
        SystemPanel(if (block is Block.Quote) block.spans else (block as Block.Paragraph).spans)
        return
    }
    block.chatMessages()?.let {
        ChatPanel(it, single = block is Block.Paragraph)
        return
    }

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

        Block.Rule -> SceneBreak()

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

/**
 * A system notice or status screen: a flat panel with a vermilion edge, in the interface's sans
 * serif rather than the story's serif, so the game's voice reads apart from the narrator's.
 * Square corners and no shadow, like the rest of the app.
 */
@Composable
private fun SystemPanel(all: List<InlineSpan>) {
    val colors = MaterialTheme.colorScheme
    // The lines of one screen sit close together: a blank `>` line between them is not a gap here.
    val spans = all.filterIndexed { i, span -> !(span.text == "\n" && all.getOrNull(i - 1)?.text == "\n") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .height(IntrinsicSize.Min)
            .background(colors.surfaceContainerHigh),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .background(colors.primary),
        )
        Text(
            // An all-bold screen is the panel's normal weight; a mix keeps its bold labels.
            text = (if (spans.filter { it.text.isNotBlank() }.all { it.bold }) spans.map { it.copy(bold = false) } else spans)
                .toAnnotatedString(),
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.5.sp,
                lineHeight = 23.sp,
                letterSpacing = 0.15.sp,
                color = colors.onSurface,
            ),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 13.dp),
        )
    }
}

/** A scene break: three small squares, the middle one vermilion, instead of a full-width line. */
@Composable
private fun SceneBreak() {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 26.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(colors.outline, colors.primary, colors.outline).forEach { color ->
            Box(Modifier.size(4.dp).background(color))
        }
    }
}

/**
 * A chat log: each message under its sender's name in small vermilion capitals, in the interface's
 * sans serif. A neutral edge keeps it apart from the vermilion-edged system panel. A one-message
 * log ([single], a scraped `Name: "..."` line) sits tight so a run of them reads as one log.
 */
@Composable
private fun ChatPanel(messages: List<ChatMessage>, single: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (single) 3.dp else 12.dp)
            .height(IntrinsicSize.Min)
            .background(colors.surfaceContainer),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .background(colors.outline),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            messages.forEach { message ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (message.sender != null) {
                        Text(
                            text = message.sender.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                        )
                    }
                    Text(
                        text = message.spans.toAnnotatedString(),
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 15.sp,
                            lineHeight = 23.sp,
                            color = if (message.sender != null) colors.onSurface else colors.onSurfaceVariant,
                            fontStyle = if (message.sender != null) FontStyle.Normal else FontStyle.Italic,
                        ),
                    )
                }
            }
        }
    }
}
