package com.mdreader.ui

import android.content.ClipData
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.mdreader.data.ChapterSource
import com.mdreader.md.Alert
import com.mdreader.md.Block
import com.mdreader.md.InlineSpan
import com.mdreader.md.TableAlignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URL

@Composable
fun List<InlineSpan>.toAnnotatedString(): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val mathColor = MaterialTheme.colorScheme.primary

    return buildAnnotatedString {
        this@toAnnotatedString.forEach { span ->
            val decorations = buildList {
                if (span.strike) add(TextDecoration.LineThrough)
                if (span.underline) add(TextDecoration.Underline)
            }

            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic || span.math) FontStyle.Italic else null,
                fontFamily = when {
                    span.code -> FontFamily.Monospace
                    span.math -> FontFamily.Serif
                    else -> null
                },
                fontSize = when {
                    span.superscript || span.subscript -> 12.sp
                    span.code -> 15.sp
                    else -> TextUnit.Unspecified
                },
                textDecoration = when {
                    decorations.isEmpty() -> null
                    decorations.size == 1 -> decorations.single()
                    else -> TextDecoration.combine(decorations)
                },
                background = when {
                    span.code -> codeBackground
                    span.highlight -> highlightColor
                    else -> Color.Unspecified
                },
                color = when {
                    span.link != null && !span.image && isSafeLink(span.link) -> linkColor
                    span.footnote != null -> linkColor
                    span.math -> mathColor
                    else -> Color.Unspecified
                },
                baselineShift = when {
                    span.superscript -> BaselineShift.Superscript
                    span.subscript -> BaselineShift.Subscript
                    else -> null
                },
            )

            val text = if (span.image) "\uD83D\uDDBC ${span.text}" else span.text
            val link = span.link?.takeIf { !span.image && isSafeLink(it) }

            if (link != null) {
                withLink(
                    LinkAnnotation.Url(
                        url = link,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                    ),
                ) {
                    withStyle(style) { append(text) }
                }
            } else {
                withStyle(style) { append(text) }
            }
        }
    }
}

@Composable
fun BlockView(
    block: Block,
    imageBaseUri: String? = null,
    nested: Boolean = false,
    quote: Boolean = false,
) {
    when (block) {
        is Block.Heading -> HeadingView(block, nested)
        is Block.Paragraph -> ParagraphView(block, imageBaseUri, nested, quote)
        is Block.Quote -> QuoteView(block, imageBaseUri, nested)
        is Block.BulletList -> ListView(
            items = block.items,
            ordered = false,
            start = 1,
            tight = block.tight,
            imageBaseUri = imageBaseUri,
            nested = nested,
            quote = quote,
        )

        is Block.OrderedList -> ListView(
            items = block.items,
            ordered = true,
            start = block.start,
            tight = block.tight,
            imageBaseUri = imageBaseUri,
            nested = nested,
            quote = quote,
        )

        is Block.Code -> CodeBlockView(block, nested)
        Block.Rule -> HorizontalDivider(
            modifier = Modifier.padding(vertical = if (nested) 12.dp else 22.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        is Block.Table -> TableBlockView(block, nested)
        is Block.Html -> HtmlBlockView(block, nested)
        is Block.FootnoteDefinition -> FootnoteDefinitionView(block, imageBaseUri)
    }
}

@Composable
private fun bodyTextStyle(): TextStyle {
    val colors = MaterialTheme.colorScheme
    return TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.1.sp,
        color = colors.onSurface,
    )
}

@Composable
private fun ParagraphView(
    block: Block.Paragraph,
    imageBaseUri: String?,
    nested: Boolean,
    quote: Boolean,
) {
    val meaningful = block.spans.filter { it.image || it.text.isNotBlank() }
    val image = meaningful.singleOrNull()?.takeIf { it.image }
    if (image != null) {
        MarkdownImage(image, imageBaseUri)
        return
    }

    val style = bodyTextStyle().let { base ->
        if (quote) {
            base.copy(
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            base
        }
    }

    Text(
        text = block.spans.toAnnotatedString(),
        style = style,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (nested) 2.dp else 9.dp),
    )
}

@Composable
private fun HeadingView(block: Block.Heading, nested: Boolean) {
    val colors = MaterialTheme.colorScheme
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

        3 -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp,
        )

        else -> TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            letterSpacing = 0.1.sp,
        )
    }

    Text(
        text = block.spans.toAnnotatedString(),
        style = style,
        color = colors.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = if (nested) 14.dp else 26.dp,
                bottom = if (nested) 4.dp else 8.dp,
            ),
    )
}

@Composable
private fun QuoteView(block: Block.Quote, imageBaseUri: String?, nested: Boolean) {
    val colors = MaterialTheme.colorScheme
    val alert = block.alert

    if (alert != null) {
        val accent = alertColor(alert)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (nested) 6.dp else 10.dp)
                .background(accent.copy(alpha = 0.06f))
                .border(1.dp, accent.copy(alpha = 0.35f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Micro(text = alert.label, color = accent)
            Spacer(Modifier.height(6.dp))
            block.blocks.forEach { child ->
                BlockView(child, imageBaseUri, nested = true, quote = true)
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(vertical = if (nested) 6.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(colors.outline),
            )
            Column(Modifier.weight(1f)) {
                block.blocks.forEach { child ->
                    BlockView(child, imageBaseUri, nested = true, quote = true)
                }
            }
        }
    }
}

@Composable
private fun alertColor(alert: Alert): Color = when (alert) {
    Alert.NOTE -> MaterialTheme.colorScheme.primary
    Alert.TIP -> MaterialTheme.colorScheme.tertiary
    Alert.IMPORTANT -> MaterialTheme.colorScheme.secondary
    Alert.WARNING -> MaterialTheme.colorScheme.tertiary
    Alert.CAUTION -> MaterialTheme.colorScheme.error
}

@Composable
private fun ListView(
    items: List<Block.ListItem>,
    ordered: Boolean,
    start: Int,
    tight: Boolean,
    imageBaseUri: String?,
    nested: Boolean,
    quote: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (nested) 2.dp else 6.dp),
    ) {
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = if (tight) 2.dp else 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .width(if (ordered) 30.dp else 22.dp)
                        .padding(top = 7.dp),
                ) {
                    if (item.task != null) {
                        TaskMark(item.task)
                    } else {
                        Text(
                            text = if (ordered) "${start + index}." else "•",
                            style = if (ordered) {
                                MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Normal,
                                )
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Column(Modifier.weight(1f)) {
                    item.blocks.forEach { child ->
                        BlockView(child, imageBaseUri, nested = true, quote = quote)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskMark(checked: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(15.dp)
            .border(1.dp, if (checked) colors.primary else colors.outline),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text(
                text = "✓",
                color = colors.primary,
                fontSize = 10.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun CodeBlockView(block: Block.Code, nested: Boolean) {
    if (block.language in MATH_LANGUAGES) {
        MathBlockView(block.text, nested)
        return
    }

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RectangleShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (nested) 6.dp else 10.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Micro(text = block.language?.uppercase() ?: "CODE")
                Spacer(Modifier.weight(1f))
                Text(
                    text = "COPY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText("Code", block.text),
                                    ),
                                )
                            }
                            Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 2.dp, horizontal = 4.dp),
                )
            }

            SelectionContainer {
                Text(
                    text = highlightCode(block.text, block.language),
                    color = MaterialTheme.colorScheme.onSurface,
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
}

@Composable
private fun MathBlockView(text: String, nested: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RectangleShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (nested) 6.dp else 10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Micro(text = "MATH")
            Spacer(Modifier.height(8.dp))
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 18.sp,
                lineHeight = 28.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TableBlockView(block: Block.Table, nested: Boolean) {
    if (block.header.isEmpty()) return

    val scrollState = rememberScrollState()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (nested) 6.dp else 12.dp),
    ) {
        val columns = block.header.size.coerceAtLeast(1)
        val minimum = 110.dp * columns.toFloat()
        val tableWidth = if (maxWidth < minimum) minimum else maxWidth

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
        ) {
            Column(Modifier.width(tableWidth)) {
                TableRowView(block.header, block.alignments, header = true)
                block.rows.forEach { row ->
                    TableRowView(row, block.alignments, header = false)
                }
            }
        }
    }
}

@Composable
private fun TableRowView(
    cells: List<List<InlineSpan>>,
    alignments: List<TableAlignment>,
    header: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (header) colors.surfaceVariant else Color.Transparent),
        ) {
            cells.forEachIndexed { index, cell ->
                Text(
                    text = cell.toAnnotatedString(),
                    style = if (header) {
                        MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        bodyTextStyle().copy(fontSize = 14.sp, lineHeight = 22.sp)
                    },
                    color = colors.onSurface,
                    textAlign = alignments.getOrElse(index) { TableAlignment.NONE }.toTextAlign(),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant)
    }
}

private fun TableAlignment.toTextAlign(): TextAlign = when (this) {
    TableAlignment.CENTER -> TextAlign.Center
    TableAlignment.RIGHT -> TextAlign.End
    else -> TextAlign.Start
}

@Composable
private fun HtmlBlockView(block: Block.Html, nested: Boolean) {
    val annotated = remember(block.html) {
        runCatching { AnnotatedString.fromHtml(block.html) }
            .getOrElse { AnnotatedString(block.html) }
    }

    Text(
        text = annotated,
        style = bodyTextStyle(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (nested) 2.dp else 9.dp),
    )
}

@Composable
private fun FootnoteDefinitionView(block: Block.FootnoteDefinition, imageBaseUri: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = block.number.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .width(24.dp)
                .padding(top = 8.dp),
        )
        Column(Modifier.weight(1f)) {
            block.blocks.forEach { child ->
                BlockView(child, imageBaseUri, nested = true)
            }
        }
    }
}

@Composable
private fun MarkdownImage(image: InlineSpan, baseUri: String?) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val source = image.link.orEmpty()

    val bitmap by produceState<ImageBitmap?>(initialValue = null, source, baseUri) {
        value = loadImage(context, source, baseUri)
    }

    val loaded = bitmap
    if (loaded != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        ) {
            Image(
                bitmap = loaded,
                contentDescription = image.text.ifBlank { "Image" },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
            )
            if (!image.title.isNullOrBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = image.title.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .clickable(enabled = isSafeLink(source)) {
                if (isSafeLink(source)) uriHandler.openUri(source)
            }
            .padding(14.dp),
    ) {
        Micro(text = "Image")
        Spacer(Modifier.height(5.dp))
        Text(
            text = image.text.ifBlank { image.title ?: "Untitled image" },
            style = bodyTextStyle(),
        )
        if (source.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = source,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private suspend fun loadImage(
    context: Context,
    source: String,
    baseUri: String?,
): ImageBitmap? {
    if (source.isBlank()) return null

    return withContext(Dispatchers.IO) {
        runCatching {
            val resolved = if (baseUri != null) {
                ChapterSource.resolveImage(baseUri.toUri(), source)
            } else {
                source.toUri()
            } ?: return@runCatching null

            val stream = when (resolved.scheme?.lowercase()) {
                "content", "android.resource" -> context.contentResolver.openInputStream(resolved)
                "file" -> resolved.path?.let { FileInputStream(File(it)) }
                "http", "https" -> URL(resolved.toString()).openConnection().apply {
                    connectTimeout = 8_000
                    readTimeout = 15_000
                }.getInputStream()

                "data" -> dataUriStream(resolved)
                else -> null
            } ?: return@runCatching null

            stream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }.getOrNull()
    }
}

private fun dataUriStream(uri: Uri): java.io.InputStream? {
    val raw = uri.toString()
    val comma = raw.indexOf(',')
    if (comma < 0) return null

    val metadata = raw.substring(0, comma)
    val payload = raw.substring(comma + 1)
    return if (metadata.contains(";base64", ignoreCase = true)) {
        runCatching { Base64.decode(payload, Base64.DEFAULT).inputStream() }.getOrNull()
    } else {
        ByteArrayInputStream(Uri.decode(payload).toByteArray(Charsets.UTF_8))
    }
}

private fun isSafeLink(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return false
    val scheme = trimmed.substringBefore(':', "").lowercase()
    return scheme in setOf("http", "https", "mailto", "tel")
}

private val MATH_LANGUAGES = setOf("math", "latex", "tex")
