package com.mdreader.md

/** A run of text with one combination of emphasis styles. */
data class InlineSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
)

/** A rendered unit of a chapter. */
sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val spans: List<InlineSpan>) : Block
    data class Bullet(val spans: List<InlineSpan>) : Block
    data class Numbered(val marker: String, val spans: List<InlineSpan>) : Block
    /** A blockquote; consecutive `>` lines stay one block, joined by `"\n"` spans. */
    data class Quote(val spans: List<InlineSpan>) : Block
    data object Rule : Block
    data class Code(val text: String) : Block
}

/**
 * A deliberately small Markdown subset, sized for novels rather than documents:
 * headings, emphasis, lists, quotes, rules and fenced code.
 *
 * No Android types are used here so the parser is covered by plain JVM tests.
 */
object MarkdownParser {

    private val rulePattern = Regex("^(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val numberedPattern = Regex("^(\\d{1,3})[.)]\\s+")

    fun parse(source: String, markdown: Boolean): List<Block> {
        val text = source
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        if (text.isBlank()) return emptyList()
        return if (markdown) parseMarkdown(text) else parsePlainText(text)
    }

    /**
     * Plain text files are treated as one paragraph per line: most offline novel
     * exports are line-per-paragraph, and joining them would produce a wall of text.
     */
    private fun parsePlainText(text: String): List<Block> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { Block.Paragraph(listOf(InlineSpan(it))) }
        .toList()

    private fun parseMarkdown(text: String): List<Block> {
        val lines = text.lines()
        // Most offline novel exports put one paragraph per line with no blank
        // separators. Detect that shape and keep the line breaks; otherwise use
        // normal Markdown rules where consecutive lines join into a paragraph.
        val contentLines = lines.dropLastWhile { it.isBlank() }
        val nonBlankLines = contentLines.count { it.isNotBlank() }
        val blankLines = contentLines.count { it.isBlank() }
        val linePerParagraph = nonBlankLines > 0 && blankLines * 10 < nonBlankLines

        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        val code = StringBuilder()
        var inFence = false
        // A run of `>` lines is one quote, so a status screen stays one panel.
        var inQuote = false

        fun flushParagraph() {
            val value = paragraph.toString().trim()
            paragraph.clear()
            if (value.isNotEmpty()) blocks += Block.Paragraph(parseInline(value))
        }

        fun flushCode() {
            blocks += Block.Code(code.toString().trimEnd('\n'))
            code.clear()
            inFence = false
        }

        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            val trimmed = line.trimStart()

            if (inFence) {
                if (isFence(trimmed)) flushCode() else code.append(line).append('\n')
                continue
            }

            if (isFence(trimmed)) {
                flushParagraph()
                inFence = true
                continue
            }

            if (line.isBlank()) {
                flushParagraph()
                inQuote = false
                continue
            }

            val headingLevel = headingLevel(trimmed)
            if (headingLevel > 0) {
                flushParagraph()
                blocks += Block.Heading(headingLevel, trimmed.dropWhile { it == '#' }.trim())
                continue
            }

            if (rulePattern.matches(trimmed)) {
                flushParagraph()
                blocks += Block.Rule
                continue
            }

            if (trimmed.startsWith(">")) {
                flushParagraph()
                val content = trimmed.removePrefix(">").trim()
                val spans = if (content.isEmpty()) emptyList() else parseInline(content)
                val last = blocks.lastOrNull()
                if (inQuote && last is Block.Quote) {
                    blocks[blocks.lastIndex] = Block.Quote(last.spans + InlineSpan("\n") + spans)
                } else if (spans.isNotEmpty()) {
                    blocks += Block.Quote(spans)
                    inQuote = true
                }
                continue
            }
            inQuote = false

            if (isBullet(trimmed)) {
                flushParagraph()
                blocks += Block.Bullet(parseInline(trimmed.drop(2).trim()))
                continue
            }

            val numbered = numberedPattern.find(trimmed)
            if (numbered != null) {
                flushParagraph()
                blocks += Block.Numbered(
                    marker = numbered.groupValues[1],
                    spans = parseInline(trimmed.substring(numbered.range.last + 1).trim()),
                )
                continue
            }

            if (linePerParagraph) {
                flushParagraph()
                blocks += Block.Paragraph(parseInline(trimmed))
            } else {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }

        if (inFence) flushCode()
        flushParagraph()
        return blocks
    }

    private fun isFence(trimmed: String): Boolean =
        trimmed.startsWith("```") || trimmed.startsWith("~~~")

    private fun headingLevel(trimmed: String): Int {
        var level = 0
        while (level < trimmed.length && trimmed[level] == '#' && level < 6) level++
        if (level == 0) return 0
        if (level >= trimmed.length || !trimmed[level].isWhitespace()) return 0
        return level
    }

    private fun isBullet(trimmed: String): Boolean =
        trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")

    /** Characters a backslash can protect, as in standard Markdown. */
    const val ESCAPABLE = "\\`*_~#>-+.![](){}|<"

    /** Index of [token] at or after [from], skipping any character a backslash protects. */
    private fun indexOfUnescaped(input: String, token: String, from: Int): Int {
        var i = from
        while (i < input.length) {
            if (input[i] == '\\' && i + 1 < input.length && input[i + 1] in ESCAPABLE) {
                i += 2
                continue
            }
            if (input.startsWith(token, i)) return i
            i++
        }
        return -1
    }

    fun parseInline(text: String): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()

        fun emit(value: String, style: InlineSpan) {
            if (value.isNotEmpty()) spans += style.copy(text = value)
        }

        fun walk(input: String, style: InlineSpan) {
            var i = 0
            val buffer = StringBuilder()

            fun flush() {
                emit(buffer.toString(), style)
                buffer.clear()
            }

            while (i < input.length) {
                if (i + 1 < input.length) {
                    val pair = input.substring(i, i + 2)
                    if (pair == "**" || pair == "__" || pair == "~~") {
                        val end = indexOfUnescaped(input, pair, i + 2)
                        if (end > i + 2) {
                            flush()
                            val inner = input.substring(i + 2, end)
                            walk(
                                inner,
                                if (pair == "~~") style.copy(strike = true) else style.copy(bold = true),
                            )
                            i = end + 2
                            continue
                        }
                    }
                }

                val ch = input[i]

                // A backslash before a Markdown character means the character itself.
                if (ch == '\\' && i + 1 < input.length && input[i + 1] in ESCAPABLE) {
                    buffer.append(input[i + 1])
                    i += 2
                    continue
                }

                if (ch == '`') {
                    val end = input.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush()
                        emit(input.substring(i + 1, end), style.copy(code = true))
                        i = end + 1
                        continue
                    }
                }

                if (ch == '*' || ch == '_') {
                    val canOpen = ch != '_' || i == 0 || !input[i - 1].isLetterOrDigit()
                    val end = if (canOpen) indexOfUnescaped(input, ch.toString(), i + 1) else -1
                    val canClose = end > i + 1 &&
                        input[end - 1] != ' ' &&
                        (ch != '_' || end + 1 >= input.length || !input[end + 1].isLetterOrDigit())
                    if (canClose) {
                        flush()
                        walk(input.substring(i + 1, end), style.copy(italic = true))
                        i = end + 1
                        continue
                    }
                }

                buffer.append(ch)
                i++
            }

            flush()
        }

        walk(text, InlineSpan(""))
        return spans
    }
}

/**
 * A system or status display: a quote whose text is all bold, or any quote or paragraph that is a
 * bracketed notice such as `[You have slain a dormant beast.]` or `<Level up>`.
 */
fun Block.isSystemDisplay(): Boolean {
    val spans = when (this) {
        is Block.Quote -> spans
        is Block.Paragraph -> spans
        else -> return false
    }
    val text = spans.joinToString("") { it.text }.trim()
    if (text.isEmpty()) return false
    val bracketed = text.first() in "[<【「" && text.last() in "]>】」"
    if (this is Block.Paragraph) return bracketed
    return bracketed || spans.filter { it.text.isNotBlank() }.all { it.bold }
}

/** One message of a chat panel: the sender (null for a group notice) and what was said. */
data class ChatMessage(val sender: String?, val spans: List<InlineSpan>)

private val chatParagraph = Regex("""^([A-Z][\p{L}'’.-]*(?: [A-Z][\p{L}'’.-]*){0,3}):\s*([“"].*)$""")

/**
 * The messages of a chat log, or null when the block is not one. A quote is a chat log when its
 * lines start with a bold sender (`> **Fang Yun:** “...”`); a lone paragraph of the form
 * `Fang Yun: "..."`, as scraped chapters print them, is a one-message log.
 */
fun Block.chatMessages(): List<ChatMessage>? {
    if (isSystemDisplay()) return null
    return when (this) {
        is Block.Quote -> {
            val lines = mutableListOf(mutableListOf<InlineSpan>())
            spans.forEach { if (it.text == "\n") lines += mutableListOf<InlineSpan>() else lines.last() += it }
            val messages = lines.filter { line -> line.any { it.text.isNotBlank() } }.map { line ->
                val head = line.first()
                if (head.bold && head.text.trim().endsWith(":") && line.size > 1) {
                    ChatMessage(head.text.trim().removeSuffix(":").trim(), line.drop(1).let { rest ->
                        listOf(rest.first().copy(text = rest.first().text.trimStart())) + rest.drop(1)
                    })
                } else {
                    ChatMessage(null, line)
                }
            }
            messages.takeIf { list -> list.any { it.sender != null } }
        }
        is Block.Paragraph -> {
            val text = spans.joinToString("") { it.text }.trim()
            chatParagraph.find(text)?.let { listOf(ChatMessage(it.groupValues[1], listOf(InlineSpan(it.groupValues[2])))) }
        }
        else -> null
    }
}
