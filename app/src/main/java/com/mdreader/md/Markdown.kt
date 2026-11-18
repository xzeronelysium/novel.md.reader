package com.mdreader.md

import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.front.matter.YamlFrontMatterBlock
import org.commonmark.ext.front.matter.YamlFrontMatterExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.ins.Ins
import org.commonmark.ext.ins.InsExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.CustomNode
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun

/** A run of text with one combination of emphasis styles. */
data class InlineSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val underline: Boolean = false,
    val highlight: Boolean = false,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    val math: Boolean = false,
    val displayMath: Boolean = false,
    val link: String? = null,
    val title: String? = null,
    val image: Boolean = false,
    val footnote: Int? = null,
)

/** A rendered unit of a chapter. */
sealed interface Block {
    data class Heading(val level: Int, val spans: List<InlineSpan>) : Block {
        constructor(level: Int, text: String) : this(level, listOf(InlineSpan(text)))

        val text: String get() = spans.joinToString("") { it.text }
    }

    data class Paragraph(val spans: List<InlineSpan>) : Block

    data class Quote(
        val blocks: List<Block>,
        val alert: Alert? = null,
    ) : Block

    data class BulletList(
        val items: List<ListItem>,
        val tight: Boolean = true,
        val marker: Char = '-',
    ) : Block

    data class OrderedList(
        val items: List<ListItem>,
        val start: Int = 1,
        val tight: Boolean = true,
    ) : Block

    data class ListItem(
        val blocks: List<Block>,
        val task: Boolean? = null,
    )

    data class Code(
        val text: String,
        val language: String? = null,
        val fenced: Boolean = true,
    ) : Block

    data object Rule : Block

    data class Table(
        val header: List<List<InlineSpan>>,
        val alignments: List<TableAlignment>,
        val rows: List<List<List<InlineSpan>>>,
    ) : Block

    /** Raw HTML that Compose should interpret with its HTML span parser. */
    data class Html(val html: String) : Block

    data class FootnoteDefinition(
        val number: Int,
        val label: String,
        val blocks: List<Block>,
    ) : Block
}

enum class TableAlignment { NONE, LEFT, CENTER, RIGHT }

enum class Alert(val label: String) {
    NOTE("Note"),
    TIP("Tip"),
    IMPORTANT("Important"),
    WARNING("Warning"),
    CAUTION("Caution"),
}

/**
 * CommonMark + GitHub-Flavored Markdown, converted into the small render model
 * above. The parser is deliberately platform-free so the whole conversion is
 * covered by plain JVM tests.
 *
 * Alongside GFM it understands tables, task lists, autolinks, footnotes,
 * inserted (++underline++) text, ==highlight==, $inline math$, ^superscript^,
 * GitHub-style blockquote alerts, YAML front matter, and fenced math blocks.
 */
object MarkdownParser {

    private val EXTENSIONS: List<Extension> = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
        AutolinkExtension.create(),
        FootnotesExtension.create(),
        InsExtension.create(),
        YamlFrontMatterExtension.create(),
    )

    private val parser: Parser = Parser.builder()
        .extensions(EXTENSIONS)
        .customDelimiterProcessor(
            CustomDelimiterProcessor('=', minimumLength = 2, maximumLength = 2) { HighlightNode() },
        )
        .customDelimiterProcessor(
            CustomDelimiterProcessor('$', minimumLength = 1, maximumLength = 2) { use ->
                MathNode(display = use == 2)
            },
        )
        .customDelimiterProcessor(
            CustomDelimiterProcessor('^', minimumLength = 1, maximumLength = 1) { SuperscriptNode() },
        )
        .build()

    private val ORDERED_LIST_PATTERN = Regex("""^\d{1,9}[.)]\s+""")
    private val THEMATIC_BREAK_PATTERN =
        Regex("""^(?:(?:\*\s*){3,}|(?:-\s*){3,}|(?:_\s*){3,})$""")
    private val SETEXT_PATTERN = Regex("""^(?:=+|-+)\s*$""")
    private val FOOTNOTE_DEF_PATTERN = Regex("""^\[\^[^\]]+]:""")
    private val LINK_REFERENCE_PATTERN = Regex("""^\[[^\]]+]:\s*\S""")
    private val TABLE_DELIMITER_PATTERN =
        Regex("""^\|?\s*:?-+:?\s*(?:\|\s*:?-+:?\s*)+\|?$""")

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
        // Most offline novel exports put one paragraph per line with no blank
        // separators. Detect that shape and insert paragraph breaks before handing
        // the document to CommonMark; otherwise normal Markdown rules apply.
        val linePerParagraph = isLinePerParagraph(text)
        var prepared = preprocessBlockMath(text)
        if (linePerParagraph) prepared = insertParagraphBreaks(prepared)

        val document = parser.parse(prepared)
        val footnoteNumbers = assignFootnoteNumbers(document)
        val footnoteDefinitions = collectFootnoteDefinitions(document)
        val converter = AstConverter(footnoteNumbers)

        val blocks = converter.blocks(document).toMutableList()
        if (footnoteNumbers.isNotEmpty()) {
            val notes = footnoteNumbers.mapNotNull { (label, number) ->
                footnoteDefinitions[label]?.let { definition ->
                    Block.FootnoteDefinition(number, label, converter.blocks(definition))
                }
            }
            if (notes.isNotEmpty()) {
                blocks += Block.Rule
                blocks += notes
            }
        }
        return blocks
    }

    /** Inline-only entry point, useful for previews and tests. */
    fun parseInline(text: String): List<InlineSpan> {
        if (text.isEmpty()) return emptyList()
        val document = parser.parse(text)
        val converter = AstConverter(emptyMap())
        val spans = mutableListOf<InlineSpan>()
        document.forEachChild { child ->
            if (spans.isNotEmpty()) spans += InlineSpan("\n")
            spans += converter.inlineNodes(child)
        }
        return spans
    }

    private fun isLinePerParagraph(text: String): Boolean {
        val content = text.lines().dropLastWhile { it.isBlank() }
        val nonBlank = content.count { it.isNotBlank() }
        val blank = content.count { it.isBlank() }
        return nonBlank > 0 && blank * 10 < nonBlank
    }

    /**
     * Turns `$$ ... $$` display math into a fenced `math` block so CommonMark can
     * keep the body verbatim. Inline `$...$` is handled by a delimiter processor.
     */
    private fun preprocessBlockMath(text: String): String {
        val lines = text.lines()
        val output = StringBuilder()
        var inFence: String? = null
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()

            if (inFence != null) {
                output.append(line).append('\n')
                if (trimmed.startsWith(inFence)) inFence = null
                index++
                continue
            }

            val fence = fenceMarker(trimmed)
            if (fence != null) {
                output.append(line).append('\n')
                inFence = fence
                index++
                continue
            }

            val indent = line.length - trimmed.length
            if (indent <= 3 && trimmed.startsWith("$$")) {
                val after = trimmed.substring(2).trim()
                if (after.length >= 2 && after.endsWith("$$")) {
                    output.append("```math\n")
                        .append(after.dropLast(2).trim())
                        .append("\n```\n")
                    index++
                    continue
                }

                val body = StringBuilder()
                var next = index + 1
                var closed = false
                while (next < lines.size) {
                    val candidate = lines[next]
                    val candidateTrimmed = candidate.trim()
                    if (candidateTrimmed.endsWith("$$")) {
                        body.append(candidateTrimmed.dropLast(2))
                        closed = true
                        next++
                        break
                    }
                    body.append(candidate).append('\n')
                    next++
                }
                if (closed) {
                    output.append("```math\n")
                        .append(body.toString().trimEnd('\n'))
                        .append("\n```\n")
                    index = next
                    continue
                }
            }

            output.append(line).append('\n')
            index++
        }
        return output.toString()
    }

    private fun insertParagraphBreaks(text: String): String {
        val output = StringBuilder()
        var inFence: String? = null
        var inFrontMatter = false
        var seenContent = false
        var previousPlain = false
        var previousBlank = true
        var previousHardBreak = false

        for (line in text.lines()) {
            val trimmed = line.trimStart()

            if (!seenContent) {
                if (line.isBlank()) {
                    output.append(line).append('\n')
                    continue
                }
                seenContent = true
                if (line.trim() == "---") {
                    inFrontMatter = true
                    output.append(line).append('\n')
                    continue
                }
            }

            if (inFrontMatter) {
                output.append(line).append('\n')
                if (line.trim() == "---") inFrontMatter = false
                continue
            }

            if (inFence != null) {
                output.append(line).append('\n')
                if (trimmed.startsWith(inFence)) inFence = null
                previousPlain = false
                previousBlank = line.isBlank()
                previousHardBreak = false
                continue
            }

            val fence = fenceMarker(trimmed)
            if (fence != null) {
                output.append(line).append('\n')
                inFence = fence
                previousPlain = false
                previousBlank = false
                previousHardBreak = false
                continue
            }

            val blank = line.isBlank()
            val plain = isPlainProseLine(line)
            val blockStart = isBlockStartLine(line)
            if (previousPlain && !previousBlank && !previousHardBreak && (plain || blockStart)) {
                output.append('\n')
            }
            output.append(line).append('\n')
            previousPlain = plain
            previousBlank = blank
            previousHardBreak = line.endsWith("  ") || line.endsWith("\\")
        }
        return output.toString()
    }

    private fun fenceMarker(trimmed: String): String? = when {
        trimmed.startsWith("```") -> "```"
        trimmed.startsWith("~~~") -> "~~~"
        else -> null
    }

    private fun isPlainProseLine(line: String): Boolean {
        if (line.isBlank()) return false
        val leading = line.takeWhile { it == ' ' || it == '\t' }
        if (leading.startsWith("\t") || leading.length >= 4) return false

        val trimmed = line.trimStart()
        if (trimmed.startsWith("#")) return false
        if (trimmed.startsWith(">")) return false
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) return false
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            return false
        }
        if (ORDERED_LIST_PATTERN.containsMatchIn(trimmed)) return false
        if (THEMATIC_BREAK_PATTERN.matches(trimmed)) return false
        if (SETEXT_PATTERN.matches(trimmed)) return false
        if (trimmed.startsWith("|")) return false
        if (trimmed.startsWith("$$")) return false
        if (trimmed.startsWith("<")) return false
        if (FOOTNOTE_DEF_PATTERN.containsMatchIn(trimmed)) return false
        if (LINK_REFERENCE_PATTERN.containsMatchIn(trimmed)) return false
        if (TABLE_DELIMITER_PATTERN.matches(trimmed)) return false
        return true
    }

    /**
     * A line that starts a new block. Used only in line-per-paragraph mode to
     * keep a prose line from swallowing the list, quote or heading beneath it.
     * Setext underlines and table delimiter rows deliberately stay out: those
     * extend the line above and must remain adjacent to it.
     */
    private fun isBlockStartLine(line: String): Boolean {
        if (line.isBlank()) return false
        val trimmed = line.trimStart()
        if (trimmed.isEmpty()) return false

        val leading = line.takeWhile { it == ' ' || it == '\t' }
        if (leading.startsWith("\t") || leading.length >= 4) return true
        if (trimmed.startsWith("#") || trimmed.startsWith(">")) return true
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) return true
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            return true
        }
        if (ORDERED_LIST_PATTERN.containsMatchIn(trimmed)) return true
        if (THEMATIC_BREAK_PATTERN.matches(trimmed)) return true
        if (trimmed.startsWith("|") || trimmed.startsWith("$$") || trimmed.startsWith("<")) {
            return true
        }
        if (FOOTNOTE_DEF_PATTERN.containsMatchIn(trimmed)) return true
        if (LINK_REFERENCE_PATTERN.containsMatchIn(trimmed)) return true
        return false
    }

    private fun assignFootnoteNumbers(document: Node): Map<String, Int> {
        val numbers = LinkedHashMap<String, Int>()
        fun walk(node: Node) {
            if (node is FootnoteReference) {
                numbers.getOrPut(node.label) { numbers.size + 1 }
            }
            var child = node.firstChild
            while (child != null) {
                walk(child)
                child = child.next
            }
        }
        walk(document)
        return numbers
    }

    private fun collectFootnoteDefinitions(document: Node): Map<String, FootnoteDefinition> {
        val definitions = LinkedHashMap<String, FootnoteDefinition>()
        fun walk(node: Node) {
            if (node is FootnoteDefinition && !definitions.containsKey(node.label)) {
                definitions[node.label] = node
            }
            var child = node.firstChild
            while (child != null) {
                walk(child)
                child = child.next
            }
        }
        walk(document)
        return definitions
    }

}

private fun languageFromInfo(info: String?): String? {
    val raw = info?.trim()?.substringBefore(' ')?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return raw
        .removePrefix("{.")
        .removePrefix("{")
        .removeSuffix("}")
        .removePrefix(".")
        .lowercase()
        .takeIf { it.isNotEmpty() }
}

private val ALERT_PATTERN =
    Regex("""^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\s*""", RegexOption.IGNORE_CASE)

private val HTML_TAG_PATTERN =
    Regex("""^<\s*(/?)\s*([a-zA-Z][a-zA-Z0-9:-]*)(.*?)\s*(/?)\s*>$""", RegexOption.DOT_MATCHES_ALL)

private val HTML_ATTRIBUTE_PATTERN = Regex(
    """([a-zA-Z_:][a-zA-Z0-9_:.\-]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>`]+)))?""",
)

private val VOID_TAGS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "param", "source", "track", "wbr",
)

private class AstConverter(private val footnoteNumbers: Map<String, Int>) {

    private data class CellData(val spans: List<InlineSpan>, val alignment: TableAlignment)

    fun blocks(parent: Node): List<Block> {
        val out = mutableListOf<Block>()
        parent.forEachChild { child ->
            when (child) {
                is FootnoteDefinition, is YamlFrontMatterBlock, is LinkReferenceDefinition -> Unit
                else -> block(child)?.let { out += it }
            }
        }
        return out
    }

    fun inlineNodes(parent: Node): List<InlineSpan> = inlines(parent, InlineSpan(""))

    private fun block(node: Node): Block? = when (node) {
        is Paragraph -> {
            val spans = inlines(node, InlineSpan(""))
            if (spans.isEmpty()) null else Block.Paragraph(spans)
        }

        is Heading -> Block.Heading(node.level, inlines(node, InlineSpan("")))

        is ThematicBreak -> Block.Rule

        is BlockQuote -> {
            val children = blocks(node).toMutableList()
            Block.Quote(children, detectAlert(children))
        }

        is BulletList -> Block.BulletList(
            items = listItems(node),
            tight = node.isTight,
            marker = node.marker?.firstOrNull() ?: '-',
        )

        is OrderedList -> Block.OrderedList(
            items = listItems(node),
            start = node.markerStartNumber ?: 1,
            tight = node.isTight,
        )

        is FencedCodeBlock -> Block.Code(
            text = node.literal.trimEnd('\n'),
            language = languageFromInfo(node.info),
            fenced = true,
        )

        is IndentedCodeBlock -> Block.Code(
            text = node.literal.trimEnd('\n'),
            language = null,
            fenced = false,
        )

        is TableBlock -> table(node)

        is HtmlBlock -> {
            val html = node.literal.trim()
            when {
                html.isEmpty() -> null
                else -> standaloneImage(html)?.let { Block.Paragraph(listOf(it)) } ?: Block.Html(html)
            }
        }

        is FootnoteDefinition, is YamlFrontMatterBlock, is LinkReferenceDefinition -> null

        else -> null
    }

    private fun listItems(parent: Node): List<Block.ListItem> {
        val items = mutableListOf<Block.ListItem>()
        parent.forEachChild { child ->
            if (child !is ListItem) return@forEachChild

            var task: Boolean? = null
            var node = child.firstChild
            if (node is TaskListItemMarker) {
                task = node.isChecked
                node = node.next
            }

            val itemBlocks = mutableListOf<Block>()
            while (node != null) {
                block(node)?.let { itemBlocks += it }
                node = node.next
            }
            items += Block.ListItem(itemBlocks, task)
        }
        return items
    }

    private fun table(node: TableBlock): Block.Table {
        var header: List<List<InlineSpan>> = emptyList()
        var alignments: List<TableAlignment> = emptyList()
        val rows = mutableListOf<List<List<InlineSpan>>>()

        node.forEachChild { section ->
            when (section) {
                is TableHead -> section.forEachChild { row ->
                    if (row is TableRow) {
                        val cells = rowCells(row)
                        header = cells.map { it.spans }
                        alignments = cells.map { it.alignment }
                    }
                }

                is TableBody -> section.forEachChild { row ->
                    if (row is TableRow) rows += rowCells(row).map { it.spans }
                }

                is TableRow -> rows += rowCells(section).map { it.spans }
            }
        }

        val columns = header.size.coerceAtLeast(rows.maxOfOrNull { it.size } ?: 0)
        if (columns == 0) return Block.Table(emptyList(), emptyList(), emptyList())

        return Block.Table(
            header = List(columns) { index -> header.getOrElse(index) { emptyList() } },
            alignments = List(columns) { index ->
                alignments.getOrElse(index) { TableAlignment.NONE }
            },
            rows = rows.map { row ->
                List(columns) { index -> row.getOrElse(index) { emptyList() } }
            },
        )
    }

    private fun rowCells(row: TableRow): List<CellData> {
        val cells = mutableListOf<CellData>()
        row.forEachChild { cell ->
            if (cell is TableCell) {
                cells += CellData(
                    spans = inlines(cell, InlineSpan("")),
                    alignment = when (cell.alignment) {
                        TableCell.Alignment.LEFT -> TableAlignment.LEFT
                        TableCell.Alignment.CENTER -> TableAlignment.CENTER
                        TableCell.Alignment.RIGHT -> TableAlignment.RIGHT
                        else -> TableAlignment.NONE
                    },
                )
            }
        }
        return cells
    }

    private fun detectAlert(blocks: MutableList<Block>): Alert? {
        val first = blocks.firstOrNull() as? Block.Paragraph ?: return null
        val text = first.spans.joinToString("") { it.text }
        val match = ALERT_PATTERN.find(text) ?: return null
        if (match.range.first != 0) return null

        val remaining = first.spans.dropPrefix(match.value.length)
        if (remaining.isEmpty() || remaining.all { it.text.isBlank() }) {
            blocks.removeAt(0)
        } else {
            blocks[0] = Block.Paragraph(remaining)
        }
        return runCatching { Alert.valueOf(match.groupValues[1].uppercase()) }.getOrNull()
    }

    private fun inlines(parent: Node, baseStyle: InlineSpan): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()
        val htmlStack = ArrayDeque<Pair<String, InlineSpan>>()
        var style = baseStyle

        parent.forEachChild { node ->
            when (node) {
                is Text -> append(spans, node.literal, style)
                is SoftLineBreak -> append(spans, " ", style)
                is HardLineBreak -> append(spans, "\n", style)
                is Code -> append(spans, node.literal, style.copy(code = true))
                is Emphasis -> spans += inlines(node, style.copy(italic = true))
                is StrongEmphasis -> spans += inlines(node, style.copy(bold = true))
                is Strikethrough -> spans += inlines(node, style.copy(strike = true))
                is Ins -> spans += inlines(node, style.copy(underline = true))
                is Link -> spans += inlines(
                    node,
                    style.copy(link = node.destination, title = node.title),
                )

                is Image -> append(
                    spans,
                    nodeText(node),
                    style.copy(image = true, link = node.destination, title = node.title),
                )

                is FootnoteReference -> {
                    val number = footnoteNumbers[node.label]
                    if (number != null) {
                        append(spans, number.toString(), style.copy(footnote = number))
                    } else {
                        append(spans, "[^${node.label}]", style)
                    }
                }

                is HtmlInline -> {
                    style = applyHtmlTag(node.literal, style, htmlStack, spans)
                }

                is HighlightNode -> spans += inlines(node, style.copy(highlight = true))
                is MathNode -> append(
                    spans,
                    nodeText(node),
                    style.copy(math = true, displayMath = node.display),
                )

                is SuperscriptNode -> spans += inlines(node, style.copy(superscript = true))

                else -> if (node.firstChild != null) {
                    spans += inlines(node, style)
                }
            }
        }
        return spans
    }

    private fun applyHtmlTag(
        raw: String,
        current: InlineSpan,
        stack: ArrayDeque<Pair<String, InlineSpan>>,
        spans: MutableList<InlineSpan>,
    ): InlineSpan {
        val tag = raw.trim()
        if (tag.startsWith("<!--") || tag.startsWith("<!") || tag.startsWith("<?")) {
            return current
        }

        val match = HTML_TAG_PATTERN.matchEntire(tag) ?: return current
        val closing = match.groupValues[1] == "/"
        val name = match.groupValues[2].lowercase()
        val rest = match.groupValues[3]
        val selfClosing = match.groupValues[4] == "/" || name in VOID_TAGS

        if (closing) {
            val index = stack.indexOfLast { it.first == name }
            if (index < 0) return current
            var restored = current
            while (stack.size > index) {
                val (_, previous) = stack.removeLast()
                restored = previous
            }
            return restored
        }

        if (name == "br" || name == "hr") {
            append(spans, "\n", current)
            return current
        }

        if (name == "img") {
            val attributes = parseAttributes(rest)
            val source = attributes["src"].orEmpty()
            if (source.isNotEmpty()) {
                append(
                    spans,
                    attributes["alt"].orEmpty(),
                    current.copy(image = true, link = source, title = attributes["title"]),
                )
            }
            return current
        }

        val styled = when (name) {
            "b", "strong" -> current.copy(bold = true)
            "i", "em" -> current.copy(italic = true)
            "u", "ins" -> current.copy(underline = true)
            "s", "strike", "del" -> current.copy(strike = true)
            "code", "kbd", "samp", "tt" -> current.copy(code = true)
            "mark" -> current.copy(highlight = true)
            "sup" -> current.copy(superscript = true)
            "sub" -> current.copy(subscript = true)
            "a" -> {
                val attributes = parseAttributes(rest)
                val href = attributes["href"]
                if (href == null) current else current.copy(link = href, title = attributes["title"])
            }

            else -> null
        }

        if (styled != null && !selfClosing) {
            stack.addLast(name to current)
            return styled
        }
        return current
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        HTML_ATTRIBUTE_PATTERN.findAll(raw).forEach { match ->
            val name = match.groupValues[1].lowercase()
            val value = match.groupValues[2]
                .ifEmpty { match.groupValues[3] }
                .ifEmpty { match.groupValues[4] }
            attributes[name] = decodeEntities(value)
        }
        return attributes
    }

    private fun standaloneImage(html: String): InlineSpan? {
        val match = HTML_TAG_PATTERN.matchEntire(html) ?: return null
        if (match.groupValues[1] == "/") return null
        if (match.groupValues[2].lowercase() != "img") return null

        val attributes = parseAttributes(match.groupValues[3])
        val source = attributes["src"].orEmpty()
        if (source.isEmpty()) return null

        return InlineSpan(
            text = attributes["alt"].orEmpty(),
            image = true,
            link = source,
            title = attributes["title"],
        )
    }

    private fun append(out: MutableList<InlineSpan>, text: String, style: InlineSpan) {
        if (text.isEmpty()) return

        val last = out.lastOrNull()
        if (last != null && last.copy(text = "") == style.copy(text = "")) {
            out[out.lastIndex] = last.copy(text = last.text + text)
        } else {
            out += style.copy(text = text)
        }
    }

    private fun nodeText(node: Node): String {
        val out = StringBuilder()
        fun walk(current: Node) {
            when (current) {
                is Text -> out.append(current.literal)
                is Code -> out.append(current.literal)
                is SoftLineBreak, is HardLineBreak -> out.append(' ')
                is HtmlInline -> out.append(current.literal)
                else -> {
                    var child = current.firstChild
                    while (child != null) {
                        walk(child)
                        child = child.next
                    }
                }
            }
        }
        walk(node)
        return out.toString()
    }
}

private fun Node.forEachChild(action: (Node) -> Unit) {
    var child = firstChild
    while (child != null) {
        action(child)
        child = child.next
    }
}

private fun List<InlineSpan>.dropPrefix(length: Int): List<InlineSpan> {
    if (length <= 0) return this

    var remaining = length
    val result = mutableListOf<InlineSpan>()
    for (span in this) {
        if (remaining <= 0) {
            result += span
            continue
        }
        if (span.text.length <= remaining) {
            remaining -= span.text.length
        } else {
            result += span.copy(text = span.text.substring(remaining))
            remaining = 0
        }
    }

    while (result.isNotEmpty() && result.first().text.isEmpty()) result.removeAt(0)
    if (result.isNotEmpty()) {
        val first = result.first()
        val trimmed = first.text.trimStart()
        if (trimmed.isEmpty()) result.removeAt(0) else result[0] = first.copy(text = trimmed)
    }
    return result
}

private fun decodeEntities(value: String): String {
    if ('&' !in value) return value
    return ENTITY_PATTERN.replace(value) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x", ignoreCase = true) ->
                body.drop(2).toIntOrNull(16)?.let(::codePointToString) ?: match.value

            body.startsWith("#") ->
                body.drop(1).toIntOrNull()?.let(::codePointToString) ?: match.value

            else -> COMMON_ENTITIES[body] ?: match.value
        }
    }
}

private fun codePointToString(value: Int): String? {
    if (value < 0 || value > 0x10FFFF || value in 0xD800..0xDFFF) return null
    return runCatching { String(Character.toChars(value)) }.getOrNull()
}

private val ENTITY_PATTERN = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]+);""")

private val COMMON_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to "\u00A0",
    "ndash" to "\u2013",
    "mdash" to "\u2014",
    "hellip" to "\u2026",
    "laquo" to "\u00AB",
    "raquo" to "\u00BB",
    "ldquo" to "\u201C",
    "rdquo" to "\u201D",
    "lsquo" to "\u2018",
    "rsquo" to "\u2019",
    "bull" to "\u2022",
    "middot" to "\u00B7",
    "copy" to "\u00A9",
    "reg" to "\u00AE",
    "trade" to "\u2122",
    "deg" to "\u00B0",
    "plusmn" to "\u00B1",
    "times" to "\u00D7",
    "divide" to "\u00F7",
    "micro" to "\u00B5",
    "para" to "\u00B6",
    "sect" to "\u00A7",
    "euro" to "\u20AC",
    "pound" to "\u00A3",
    "yen" to "\u00A5",
    "cent" to "\u00A2",
    "larr" to "\u2190",
    "rarr" to "\u2192",
    "uarr" to "\u2191",
    "darr" to "\u2193",
    "harr" to "\u2194",
    "hearts" to "\u2665",
    "spades" to "\u2660",
    "clubs" to "\u2663",
    "diams" to "\u2666",
)

private class HighlightNode : CustomNode()

private class MathNode(val display: Boolean) : CustomNode()

private class SuperscriptNode : CustomNode()

private class CustomDelimiterProcessor(
    private val character: Char,
    private val minimumLength: Int,
    private val maximumLength: Int,
    private val createNode: (Int) -> Node,
) : DelimiterProcessor {

    override fun getOpeningCharacter(): Char = character

    override fun getClosingCharacter(): Char = character

    override fun getMinLength(): Int = minimumLength

    override fun process(openingRun: DelimiterRun, closingRun: DelimiterRun): Int {
        val openingLength = openingRun.length()
        val closingLength = closingRun.length()

        val use = when {
            minimumLength >= 2 -> {
                if (openingLength >= minimumLength && closingLength >= minimumLength) {
                    minimumLength
                } else {
                    0
                }
            }

            maximumLength >= 2 && openingLength >= 2 && closingLength >= 2 -> 2
            else -> 1
        }
        if (use == 0) return 0

        val opener = openingRun.opener
        val closer = closingRun.closer
        val wrapper = createNode(use)

        var child = opener.next
        while (child != null && child !== closer) {
            val next = child.next
            child.unlink()
            wrapper.appendChild(child)
            child = next
        }
        opener.insertAfter(wrapper)
        return use
    }
}
