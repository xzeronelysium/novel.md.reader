package com.mdreader.md

/**
 * The reverse of [MarkdownParser]: blocks back to Markdown text that parses to the same blocks.
 *
 * Characters Markdown would act on are protected with a backslash, so a chapter line that
 * happens to start with "1." or contain a lone "*" comes back exactly as it was written.
 */
object MarkdownWriter {

    fun write(blocks: List<Block>): String =
        blocks.joinToString(separator = "\n\n") { block(it) } + "\n"

    private fun block(block: Block): String = when (block) {
        // Heading text is read back raw, so it is written raw.
        is Block.Heading -> "#".repeat(block.level.coerceIn(1, 6)) + " " + oneLine(block.text)
        is Block.Paragraph -> protectLineStart(inline(block.spans))
        is Block.Bullet -> "- " + inline(block.spans)
        is Block.Numbered -> "${block.marker}. " + inline(block.spans)
        is Block.Quote -> "> " + inline(block.spans)
        Block.Rule -> "---"
        is Block.Code -> "```\n${block.text}\n```"
    }

    private fun inline(spans: List<InlineSpan>): String = spans.joinToString("") { span(it) }

    private fun span(span: InlineSpan): String {
        val text = oneLine(span.text)
        val lead = text.takeWhile { it.isWhitespace() }
        val trail = text.takeLastWhile { it.isWhitespace() }
        val core = text.substring(lead.length, text.length - trail.length)
        // Whitespace stays outside the markers: "** bold **" is not emphasis.
        if (core.isEmpty()) return text

        var body = if (span.code && '`' !in core) "`$core`" else escape(core)
        // Bold and italic together are "**_x_**": "***x***" is ambiguous to read back.
        body = when {
            span.bold && span.italic -> "**_${body}_**"
            span.bold -> "**$body**"
            span.italic -> "*$body*"
            else -> body
        }
        if (span.strike) body = "~~$body~~"
        return lead + body + trail
    }

    private fun oneLine(text: String): String = text.replace(Regex("\\s*\\n\\s*"), " ")

    private fun escape(text: String): String {
        val out = StringBuilder(text.length + 8)
        for (ch in text) {
            if (ch in INLINE_SPECIAL) out.append('\\')
            out.append(ch)
        }
        return out.toString()
    }

    /** A line that starts like a heading, list, quote, rule or fence must not be read as one. */
    private fun protectLineStart(line: String): String {
        if (line.isEmpty()) return line
        if (line[0] in "#>-+") return "\\" + line
        val digits = line.takeWhile { it.isDigit() }
        if (digits.isNotEmpty() && digits.length < line.length && line[digits.length] in ".)") {
            return digits + "\\" + line.substring(digits.length)
        }
        return line
    }

    private const val INLINE_SPECIAL = "\\`*_~[]<"
}
