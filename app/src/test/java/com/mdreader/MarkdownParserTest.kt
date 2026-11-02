package com.mdreader

import com.mdreader.md.Block
import com.mdreader.md.InlineSpan
import com.mdreader.md.MarkdownParser
import com.mdreader.md.chatMessages
import com.mdreader.md.isSystemDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `atx headings keep their level and text`() {
        val blocks = MarkdownParser.parse("# Chapter One\n## Scene Two", markdown = true)

        assertEquals(Block.Heading(1, "Chapter One"), blocks[0])
        assertEquals(Block.Heading(2, "Scene Two"), blocks[1])
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

        assertTrue(blocks[0] is Block.Bullet)
        assertTrue(blocks[1] is Block.Bullet)
        assertEquals("1", (blocks[2] as Block.Numbered).marker)
        assertEquals("2", (blocks[3] as Block.Numbered).marker)
        assertEquals(listOf(InlineSpan("quoted")), (blocks[4] as Block.Quote).spans)
        assertEquals(Block.Rule, blocks[5])
    }

    @Test
    fun `fenced code blocks are kept verbatim`() {
        val blocks = MarkdownParser.parse(
            "Before.\n\n```\nval x = 1\nval y = 2\n```\n\nAfter.",
            markdown = true,
        )

        assertEquals(3, blocks.size)
        assertEquals(Block.Code("val x = 1\nval y = 2"), blocks[1])
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
    fun `consecutive quote lines are one block and a blank line ends it`() {
        val blocks = MarkdownParser.parse("> **[Name: Sunless]**\n>\n> **[Rank: Dormant]**\n\n> Another note", markdown = true)
        assertEquals(2, blocks.size)
        val panel = blocks[0] as Block.Quote
        assertEquals("[Name: Sunless]\n\n[Rank: Dormant]", panel.spans.joinToString("") { it.text })
    }

    @Test
    fun `system displays are recognised`() {
        fun first(md: String) = MarkdownParser.parse(md, markdown = true).first()
        assertEquals(true, first("> **[You have slain a beast.]**").isSystemDisplay())
        assertEquals(true, first("\\[You have slain a beast.\\]").isSystemDisplay())
        assertEquals(true, first("> **Name: Sunless**").isSystemDisplay())
        assertEquals(false, first("> A letter from home.").isSystemDisplay())
        assertEquals(false, first("He said [quietly] nothing.").isSystemDisplay())
    }

    @Test
    fun `chat logs are read as messages`() {
        val log = MarkdownParser.parse("> **Fang Yun:** “Heh.”\n> **Wang Ping:** “Why?”\n> Group members: 7/67.", markdown = true).first()
        val messages = log.chatMessages()!!
        assertEquals(listOf("Fang Yun", "Wang Ping", null), messages.map { it.sender })
        assertEquals("“Heh.”", messages[0].spans.joinToString("") { it.text })

        val scraped = MarkdownParser.parse("Liu Mei: \"Hehe, of course not.\"", markdown = true).first()
        assertEquals("Liu Mei", scraped.chatMessages()!!.single().sender)

        assertEquals(null, MarkdownParser.parse("> A letter from home.", markdown = true).first().chatMessages())
        assertEquals(null, MarkdownParser.parse("He said: \"no\".", markdown = true).first().chatMessages())
        assertEquals(null, MarkdownParser.parse("> **[Name: Sunless]**", markdown = true).first().chatMessages())
    }
}
