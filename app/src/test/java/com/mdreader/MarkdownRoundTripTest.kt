package com.mdreader

import com.mdreader.md.Block
import com.mdreader.md.InlineSpan
import com.mdreader.md.MarkdownParser
import com.mdreader.md.MarkdownWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownRoundTripTest {

    private fun para(vararg spans: InlineSpan) = Block.Paragraph(spans.toList())
    private fun plain(text: String) = para(InlineSpan(text))

    private fun roundTrip(blocks: List<Block>): List<Block> =
        MarkdownParser.parse(MarkdownWriter.write(blocks), markdown = true)

    @Test
    fun `plain paragraphs survive`() {
        val blocks = listOf(plain("\"Brother! I got the game you wanted!\" The teenager said cheerfully."), plain("Un..."))
        assertEquals(blocks, roundTrip(blocks))
    }

    @Test
    fun `emphasis survives in every combination`() {
        val blocks = listOf(
            para(
                InlineSpan("He said "),
                InlineSpan("never", bold = true),
                InlineSpan(" and "),
                InlineSpan("really", italic = true),
                InlineSpan(" and "),
                InlineSpan("always", bold = true, italic = true),
                InlineSpan(" and "),
                InlineSpan("gone", strike = true),
                InlineSpan("."),
            ),
        )
        assertEquals(blocks, roundTrip(blocks))
    }

    @Test
    fun `lines that look like markdown stay paragraphs`() {
        val lines = listOf(
            "1. The first rule of the sect",
            "12) Another numbered looking line",
            "- not a bullet",
            "+ not a bullet either",
            "> not a quote",
            "# not a heading",
            "---",
            "***",
            "___",
            "```",
            "* * *",
        )
        lines.forEach { line ->
            val blocks = listOf(plain("before"), plain(line), plain("after"))
            assertEquals("line: $line", blocks, roundTrip(blocks))
        }
    }

    @Test
    fun `stray markdown characters inside text survive`() {
        val texts = listOf(
            "snake_case and 2 * 3 = 6",
            "a*b*c",
            "tilde ~~ pair ~~ here",
            "a `tick` here",
            "back\\slash and \\* escaped",
            "[link](not) and <b>tag</b>",
            "ends with a star*",
        )
        texts.forEach { text ->
            val blocks = listOf(plain(text))
            assertEquals("text: $text", blocks, roundTrip(blocks))
        }
    }

    @Test
    fun `emphasis around awkward text survives`() {
        val blocks = listOf(
            para(InlineSpan("a*", italic = true), InlineSpan(" then "), InlineSpan("b_c", bold = true)),
            para(InlineSpan("1. title", bold = true)),
        )
        assertEquals(blocks, roundTrip(blocks))
    }

    @Test
    fun `spaces next to emphasis stay outside the markers`() {
        val written = MarkdownWriter.write(
            listOf(para(InlineSpan("x"), InlineSpan(" spaced ", bold = true), InlineSpan("y"))),
        )
        assertEquals("x **spaced** y\n", written)
    }

    @Test
    fun `other block kinds survive`() {
        val blocks = listOf(
            Block.Heading(1, "Chapter 1 - Cultivation Online"),
            plain("Text."),
            Block.Quote(listOf(InlineSpan("A quote"))),
            Block.Bullet(listOf(InlineSpan("An item"))),
            Block.Numbered("3", listOf(InlineSpan("Third"))),
            Block.Rule,
            Block.Code("val x = 1"),
        )
        assertEquals(blocks, roundTrip(blocks))
    }

    @Test
    fun `existing files without escapes read as before`() {
        val blocks = MarkdownParser.parse("Hello *world* and **you**.\n\nBye.", markdown = true)
        assertEquals(
            listOf(
                para(InlineSpan("Hello "), InlineSpan("world", italic = true), InlineSpan(" and "), InlineSpan("you", bold = true), InlineSpan(".")),
                plain("Bye."),
            ),
            blocks,
        )
    }
}
