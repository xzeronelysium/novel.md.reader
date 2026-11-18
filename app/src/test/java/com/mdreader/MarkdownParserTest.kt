package com.mdreader

import com.mdreader.md.Alert
import com.mdreader.md.Block
import com.mdreader.md.InlineSpan
import com.mdreader.md.MarkdownParser
import com.mdreader.md.TableAlignment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownParserTest {

    @Test
    fun `atx headings keep their level and text`() {
        val blocks = MarkdownParser.parse("# Chapter One\n## Scene Two", markdown = true)

        assertEquals(Block.Heading(1, "Chapter One"), blocks[0])
        assertEquals(Block.Heading(2, "Scene Two"), blocks[1])
    }

    @Test
    fun `setext headings are recognised`() {
        val blocks = MarkdownParser.parse("Chapter One\n===========\n\nScene\n-----", markdown = true)

        assertEquals(Block.Heading(1, "Chapter One"), blocks[0])
        assertEquals(Block.Heading(2, "Scene"), blocks[1])
    }

    @Test
    fun `consecutive lines join into one paragraph in normal markdown`() {
        val blocks = MarkdownParser.parse(
            "The road was long\nand the night was cold.\n\nHe kept walking.",
            markdown = true,
        )

        assertEquals(2, blocks.size)
        assertEquals(
            listOf(InlineSpan("The road was long and the night was cold.")),
            (blocks[0] as Block.Paragraph).spans,
        )
        assertEquals(
            listOf(InlineSpan("He kept walking.")),
            (blocks[1] as Block.Paragraph).spans,
        )
    }

    @Test
    fun `line per paragraph files keep their line breaks`() {
        val blocks = MarkdownParser.parse(
            "First paragraph.\nSecond paragraph.\nThird paragraph.",
            markdown = true,
        )

        assertEquals(3, blocks.size)
        assertEquals(
            listOf(
                InlineSpan("First paragraph."),
                InlineSpan("Second paragraph."),
                InlineSpan("Third paragraph."),
            ),
            blocks.map { (it as Block.Paragraph).spans.single() },
        )
    }

    @Test
    fun `plain text treats every non empty line as a paragraph`() {
        val blocks = MarkdownParser.parse(
            "# not a heading\n\nSecond line",
            markdown = false,
        )

        assertEquals(2, blocks.size)
        assertEquals(
            listOf(InlineSpan("# not a heading")),
            (blocks[0] as Block.Paragraph).spans,
        )
    }

    @Test
    fun `lists quotes and rules are recognised`() {
        val blocks = MarkdownParser.parse(
            "- first\n- second\n\n1. one\n2. two\n\n> quoted\n\n---",
            markdown = true,
        )

        val bullets = blocks[0] as Block.BulletList
        assertEquals(2, bullets.items.size)
        assertEquals(
            listOf(InlineSpan("first")),
            (bullets.items[0].blocks.single() as Block.Paragraph).spans,
        )

        val numbers = blocks[1] as Block.OrderedList
        assertEquals(1, numbers.start)
        assertEquals(2, numbers.items.size)

        val quote = blocks[2] as Block.Quote
        assertEquals(
            listOf(InlineSpan("quoted")),
            (quote.blocks.single() as Block.Paragraph).spans,
        )

        assertEquals(Block.Rule, blocks[3])
    }

    @Test
    fun `nested lists and task items are preserved`() {
        val blocks = MarkdownParser.parse(
            """
            - [x] done
              - nested
            - [ ] todo
            """.trimIndent(),
            markdown = true,
        )

        val list = blocks.single() as Block.BulletList
        assertEquals(2, list.items.size)
        assertEquals(true, list.items[0].task)
        assertEquals(false, list.items[1].task)

        val firstItem = list.items[0].blocks
        assertEquals(
            listOf(InlineSpan("done")),
            (firstItem[0] as Block.Paragraph).spans,
        )
        val nested = firstItem[1] as Block.BulletList
        assertEquals(
            listOf(InlineSpan("nested")),
            (nested.items.single().blocks.single() as Block.Paragraph).spans,
        )
    }

    @Test
    fun `ordered lists keep their starting number`() {
        val blocks = MarkdownParser.parse("3. three\n4. four", markdown = true)

        val list = blocks.single() as Block.OrderedList
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
    }

    @Test
    fun `blockquote alerts become typed callouts`() {
        val blocks = MarkdownParser.parse(
            "> [!NOTE]\n> Pay attention to **this**.",
            markdown = true,
        )

        val quote = blocks.single() as Block.Quote
        assertEquals(Alert.NOTE, quote.alert)
        val paragraph = quote.blocks.single() as Block.Paragraph
        assertEquals("Pay attention to this.", paragraph.spans.joinToString("") { it.text })
        assertTrue(paragraph.spans.any { it.bold })
    }

    @Test
    fun `fenced code blocks are kept verbatim with their language`() {
        val blocks = MarkdownParser.parse(
            "Before.\n\n```kotlin\nval x = 1\nval y = 2\n```\n\nAfter.",
            markdown = true,
        )

        assertEquals(3, blocks.size)
        assertEquals(Block.Code("val x = 1\nval y = 2", language = "kotlin"), blocks[1])
    }

    @Test
    fun `indented code is recognised`() {
        val blocks = MarkdownParser.parse("    val x = 1\n    val y = 2", markdown = true)

        assertEquals(Block.Code("val x = 1\nval y = 2", fenced = false), blocks.single())
    }

    @Test
    fun `bold italic and strikethrough become spans`() {
        val spans = MarkdownParser.parseInline("Hello **world** and *you* with ~~style~~.")

        assertEquals(
            listOf(
                InlineSpan("Hello "),
                InlineSpan("world", bold = true),
                InlineSpan(" and "),
                InlineSpan("you", italic = true),
                InlineSpan(" with "),
                InlineSpan("style", strike = true),
                InlineSpan("."),
            ),
            spans,
        )
    }

    @Test
    fun `nested emphasis combines on one span`() {
        val spans = MarkdownParser.parseInline("***both*** and **bold *inside***")

        assertTrue(spans.any { it.bold && it.italic && it.text == "both" })
        assertTrue(spans.any { it.bold && it.italic && it.text == "inside" })
    }

    @Test
    fun `underline highlight math and superscript extensions work`() {
        assertTrue(
            MarkdownParser.parseInline("++inserted++")
                .single().underline,
        )
        assertTrue(
            MarkdownParser.parseInline("==highlighted==")
                .single().highlight,
        )

        val math = MarkdownParser.parseInline("\$x^2 + y\$").single()
        assertTrue(math.math)
        assertEquals("x^2 + y", math.text)

        val superscript = MarkdownParser.parseInline("x^2^").last()
        assertTrue(superscript.superscript)
        assertEquals("2", superscript.text)
    }

    @Test
    fun `currency dollars are not mistaken for math`() {
        val spans = MarkdownParser.parseInline("It costs \$5 and \$10.")
        assertEquals("It costs \$5 and \$10.", spans.joinToString("") { it.text })
        assertTrue(spans.none { it.math })
    }

    @Test
    fun `underscores inside words are not emphasis`() {
        val spans = MarkdownParser.parseInline("snake_case_name stays intact")

        assertEquals(listOf(InlineSpan("snake_case_name stays intact")), spans)
    }

    @Test
    fun `unmatched markers stay literal`() {
        val spans = MarkdownParser.parseInline("2 * 3 = 6")

        assertEquals(listOf(InlineSpan("2 * 3 = 6")), spans)
    }

    @Test
    fun `code spans are literal`() {
        val spans = MarkdownParser.parseInline("Use `**not bold**` now")

        assertEquals(
            listOf(
                InlineSpan("Use "),
                InlineSpan("**not bold**", code = true),
                InlineSpan(" now"),
            ),
            spans,
        )
    }

    @Test
    fun `inline links and autolinks become link spans`() {
        val inline = MarkdownParser.parseInline("[OpenAI](https://openai.com \"OpenAI\")").single()
        assertEquals("OpenAI", inline.text)
        assertEquals("https://openai.com", inline.link)
        assertEquals("OpenAI", inline.title)

        val autolink = MarkdownParser.parseInline("<https://example.com>").single()
        assertEquals("https://example.com", autolink.link)

        val bare = MarkdownParser.parseInline("Visit www.example.com today")
        assertTrue(bare.any { it.link == "http://www.example.com" })
    }

    @Test
    fun `reference links resolve`() {
        val spans = MarkdownParser.parseInline(
            "Read [the docs][docs].\n\n[docs]: https://example.com \"Docs\"",
        )

        val link = spans.first { it.text == "the docs" }
        assertEquals("https://example.com", link.link)
        assertEquals("Docs", link.title)
    }

    @Test
    fun `images keep alt text and source`() {
        val image = MarkdownParser.parseInline("![A cover](cover.png \"Cover\")").single()

        assertTrue(image.image)
        assertEquals("A cover", image.text)
        assertEquals("cover.png", image.link)
        assertEquals("Cover", image.title)
    }

    @Test
    fun `tables keep alignment and inline formatting`() {
        val blocks = MarkdownParser.parse(
            """
            | Name | Value |
            | :--- | ---: |
            | **A** | 1 |
            | B | 2 |
            """.trimIndent(),
            markdown = true,
        )

        val table = blocks.single() as Block.Table
        assertEquals(2, table.header.size)
        assertEquals(TableAlignment.LEFT, table.alignments[0])
        assertEquals(TableAlignment.RIGHT, table.alignments[1])
        assertEquals(listOf(InlineSpan("Name")), table.header[0])
        assertEquals(2, table.rows.size)
        assertTrue(table.rows[0][0].single().bold)
        assertEquals(listOf(InlineSpan("2")), table.rows[1][1])
    }

    @Test
    fun `footnotes are numbered and collected`() {
        val blocks = MarkdownParser.parse(
            "Text[^a] and again[^a].\n\n[^a]: Footnote with **bold**.",
            markdown = true,
        )

        val paragraph = blocks.first() as Block.Paragraph
        val refs = paragraph.spans.filter { it.footnote != null }
        assertEquals(listOf(1, 1), refs.map { it.footnote })

        assertTrue(blocks.any { it is Block.Rule })
        val definition = blocks.filterIsInstance<Block.FootnoteDefinition>().single()
        assertEquals(1, definition.number)
        assertEquals("a", definition.label)
        val note = definition.blocks.single() as Block.Paragraph
        assertEquals("Footnote with bold.", note.spans.joinToString("") { it.text })
        assertTrue(note.spans.any { it.bold })
    }

    @Test
    fun `inline html maps common tags`() {
        val spans = MarkdownParser.parseInline("a <b>bold</b> <mark>marked</mark><br>c")

        assertTrue(spans.any { it.text == "bold" && it.bold })
        assertTrue(spans.any { it.text == "marked" && it.highlight })
        assertEquals("a bold marked\nc", spans.joinToString("") { it.text })
    }

    @Test
    fun `html blocks survive as html`() {
        val blocks = MarkdownParser.parse(
            "<div>Hello <b>world</b></div>",
            markdown = true,
        )

        val html = blocks.single() as Block.Html
        assertTrue(html.html.contains("<b>world</b>"))
    }

    @Test
    fun `display math becomes a math code block`() {
        val blocks = MarkdownParser.parse(
            "Before.\n\n$$\nE = mc^2\n$$\n\nAfter.",
            markdown = true,
        )

        assertEquals(3, blocks.size)
        assertEquals(Block.Code("E = mc^2", language = "math"), blocks[1])
    }

    @Test
    fun `yaml front matter is hidden`() {
        val blocks = MarkdownParser.parse(
            "---\ntitle: A Book\nauthor: Someone\n---\n\nBody.",
            markdown = true,
        )

        assertEquals(
            listOf(InlineSpan("Body.")),
            (blocks.single() as Block.Paragraph).spans,
        )
    }

    @Test
    fun `front matter survives line per paragraph mode`() {
        val blocks = MarkdownParser.parse(
            "---\ntitle: A Book\nauthor: Someone\n---\nBody.",
            markdown = true,
        )

        assertEquals(
            listOf(InlineSpan("Body.")),
            (blocks.single() as Block.Paragraph).spans,
        )
    }

    @Test
    fun `line per paragraph mode does not swallow a following list`() {
        val blocks = MarkdownParser.parse("Intro line.\n3. three\n4. four", markdown = true)

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is Block.Paragraph)
        val list = blocks[1] as Block.OrderedList
        assertEquals(3, list.start)
    }

    @Test
    fun `escapes entities and hard breaks are handled`() {
        val escaped = MarkdownParser.parseInline("\\*not emphasis\\*")
        assertEquals("*not emphasis*", escaped.joinToString("") { it.text })

        val entities = MarkdownParser.parseInline("Tom &amp; Jerry &#169;")
        assertEquals("Tom & Jerry ©", entities.joinToString("") { it.text })

        val breaks = MarkdownParser.parse("line  \nnext", markdown = true)
        assertEquals(
            "line\nnext",
            (breaks.single() as Block.Paragraph).spans.joinToString("") { it.text },
        )
    }

    @Test
    fun `windows line endings are normalised`() {
        val blocks = MarkdownParser.parse("# Title\r\n\r\nBody\r\n", markdown = true)

        assertEquals(Block.Heading(1, "Title"), blocks[0])
        assertEquals(
            listOf(InlineSpan("Body")),
            (blocks[1] as Block.Paragraph).spans,
        )
    }

    @Test
    fun `blank source produces no blocks`() {
        assertEquals(emptyList<Block>(), MarkdownParser.parse("   \n\n", markdown = true))
    }

    @Test
    fun `nested quote blocks keep their structure`() {
        val blocks = MarkdownParser.parse(
            "> Quote\n>\n> - one\n> - two",
            markdown = true,
        )

        val quote = blocks.single() as Block.Quote
        assertEquals(2, quote.blocks.size)
        assertTrue(quote.blocks[0] is Block.Paragraph)
        assertNotNull(quote.blocks[1] as? Block.BulletList)
    }
}
