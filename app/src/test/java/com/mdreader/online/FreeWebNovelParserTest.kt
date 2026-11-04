package com.mdreader.online

import com.mdreader.md.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeWebNovelParserTest {

    @Test
    fun `search keeps the novel link and ignores chapter links`() {
        val html = """
            <h3 class="tit"><a href="/novel/cultivation-online-novel">Cultivation Online</a></h3>
            <a href="/novel/cultivation-online-novel/chapter-1">Chapter 1</a>
            <h3 class="tit"><a href="https://example.com/novel/other">Elsewhere</a></h3>
        """.trimIndent()

        val results = FreeWebNovelParser.search(html)

        assertEquals(listOf("Cultivation Online"), results.map { it.title })
        assertEquals("https://freewebnovel.com/novel/cultivation-online-novel", results.single().url)
    }

    @Test
    fun `chapter pages stay in the order supplied by the page`() {
        val body = """
            {"code":200,"page":2,"pageSize":40,"totalPage":68,"html":"<a href=\"/novel/book/chapter-41\" title=\"Chapter 41\"></a><a href=\"/novel/book/chapter-42\" title=\"Chapter 42\"></a>"}
        """.trimIndent()

        val page = FreeWebNovelParser.chapterPage(body)

        assertEquals(2, page.page)
        assertEquals(listOf("Chapter 41", "Chapter 42"), page.chapters.map { it.title })
        assertTrue(page.chapters[0].url.endsWith("/chapter-41"))
    }

    @Test
    fun `chapter text keeps paragraph order and drops scripts`() {
        val html = """
            <div class="m-read"><div class="txt"><div id="article">
              <p>First</p>
              <div class="reader-ad"><script>bad()</script><p>Ad</p></div>
              <p>Second <strong>Yuan</strong></p>
            </div></div></div>
        """.trimIndent()

        val blocks = FreeWebNovelParser.chapter(html)

        assertEquals(listOf("First", "Second Yuan"), blocks.filterIsInstance<Block.Paragraph>().map { paragraph ->
            paragraph.spans.joinToString("") { it.text }
        })
        assertTrue(blocks.filterIsInstance<Block.Paragraph>()[1].spans.any { it.bold && it.text == "Yuan" })
    }

    @Test
    fun `marked chapter fragments are read in their declared order`() {
        val html = """
            <div id="article">
              <p data-reader-order-index="2">Second</p>
              <p data-reader-order-index="1">First</p>
            </div>
        """.trimIndent()

        val text = FreeWebNovelParser.chapter(html)
            .filterIsInstance<Block.Paragraph>()
            .map { paragraph -> paragraph.spans.joinToString("") { it.text } }

        assertEquals(listOf("First", "Second"), text)
    }
}
