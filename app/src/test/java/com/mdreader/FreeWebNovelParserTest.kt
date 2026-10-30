package com.mdreader

import com.mdreader.data.online.ChapterPage
import com.mdreader.data.online.ChapterRef
import com.mdreader.data.online.FreeWebNovelParser
import com.mdreader.data.online.OnlineException
import com.mdreader.md.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Fixtures are trimmed copies of the markup the site served when this was written. */
class FreeWebNovelParserTest {

    private val searchHtml = """
        <div class="col-content"><div class="ul-list1"><div class="li-row"><div class="li"><div class="con">
          <div class="pic"><a href="/novel/cultivation-online-novel"><img src="/x.jpg"></a></div>
          <div class="txt">
            <h3 class="tit"><a href="/novel/cultivation-online-novel" title="Cultivation Online">Cultivation Online</a></h3>
            <div class="desc"><div class="item"><div class="right"><em><a href="/sort/x">Chinese Novel</a></em></div></div>
            <div class="item"><div class="right">2688 Chapters</div></div></div>
          </div></div></div>
          <div class="li"><div class="con"><div class="txt">
            <h3 class="tit"><a href="/novel/cultivation-online-novel" title="Cultivation Online">dup</a></h3>
          </div></div></div>
          <div class="li"><div class="con"><div class="txt">
            <h3 class="tit"><a href="https://evil.example/novel/x" title="Evil">Evil</a></h3>
          </div></div></div>
          <div class="li"><div class="con"><div class="txt">
            <h3 class="tit"><a href="//evil.example/novel/y" title="Evil2">Evil2</a></h3>
          </div></div></div>
          <div class="li"><div class="con"><div class="txt">
            <h3 class="tit"><a href="https://freewebnovel.com/novel/second-novel?x=1" title="Second">Second</a></h3>
          </div></div></div>
        </div></div></div>
    """.trimIndent()

    @Test
    fun `search keeps same-site novels once, in order`() {
        val hits = FreeWebNovelParser.parseSearch(searchHtml)
        assertEquals(
            listOf("/novel/cultivation-online-novel", "/novel/second-novel"),
            hits.map { it.path },
        )
        assertEquals("Cultivation Online", hits[0].title)
        assertEquals("Chinese Novel · 2688 Chapters", hits[0].meta)
    }

    @Test
    fun `novel info reads title and page count`() {
        val options = (1..3).joinToString("") { """<option value="/novel/a">C.$it</option>""" }
        val info = FreeWebNovelParser.parseNovelDetails(
            """<div class="m-desc"><h1 class="tit">Cultivation Online</h1></div><select id="indexselect">$options</select>"""
        )
        assertEquals("Cultivation Online", info.title)
        assertEquals(3, info.totalPages)
    }

    @Test
    fun `a page without a novel title is rejected`() {
        try {
            FreeWebNovelParser.parseNovelDetails("<html><body>Just a moment...</body></html>")
            fail("expected OnlineException")
        } catch (_: OnlineException) {
        }
    }

    private fun pageJson(page: Int, total: Int, count: Int, vararg numbers: Int): String {
        val html = numbers.joinToString("") {
            """<li><a href="/novel/n/chapter-$it" title="Chapter $it: T$it">Chapter $it: T$it</a></li>"""
        } + """<li><a href="https://evil.example/novel/n/chapter-99">bad</a></li>"""
        return org.json.JSONObject()
            .put("code", 200).put("html", html).put("page", page)
            .put("pageSize", 2).put("totalPage", total).put("totalChapters", count)
            .toString()
    }

    @Test
    fun `chapter page parses refs and drops foreign links`() {
        val page = FreeWebNovelParser.parseChapterPage(pageJson(2, 3, 5, 3, 4))
        assertEquals(2, page.page)
        assertEquals(3, page.totalPages)
        assertEquals(
            listOf(ChapterRef("Chapter 3: T3", "/novel/n/chapter-3"), ChapterRef("Chapter 4: T4", "/novel/n/chapter-4")),
            page.chapters,
        )
    }

    @Test
    fun `malformed list responses fail loudly`() {
        listOf("not json", """{"code":200}""", """{"html":"","page":1,"totalPage":1}""").forEach {
            try {
                FreeWebNovelParser.parseChapterPage(it)
                fail("expected OnlineException for $it")
            } catch (_: OnlineException) {
            }
        }
    }

    private fun parsed(page: Int, total: Int, count: Int, vararg n: Int): ChapterPage =
        FreeWebNovelParser.parseChapterPage(pageJson(page, total, count, *n))

    @Test
    fun `pages are joined in page order whatever order they arrived in`() {
        val chapters = FreeWebNovelParser.assembleChapters(
            listOf(parsed(3, 3, 5, 5), parsed(1, 3, 5, 1, 2), parsed(2, 3, 5, 3, 4)),
        )
        assertEquals((1..5).map { "/novel/n/chapter-$it" }, chapters.map { it.path })
    }

    @Test
    fun `missing duplicate or miscounted pages are not reported as success`() {
        val bad = listOf(
            listOf(parsed(1, 3, 5, 1, 2), parsed(3, 3, 5, 5)),
            listOf(parsed(1, 2, 4, 1, 2), parsed(2, 2, 4, 2, 3)),
            listOf(parsed(1, 2, 9, 1, 2), parsed(2, 2, 9, 3, 4)),
        )
        bad.forEach {
            try {
                FreeWebNovelParser.assembleChapters(it)
                fail("expected OnlineException")
            } catch (_: OnlineException) {
            }
        }
    }

    private fun chapterHtml(body: String) =
        """<div class="m-read"><div class="txt"><div id="article"> $body </div></div></div>"""

    @Test
    fun `chapter text keeps paragraph order and ignores title ads and promo`() {
        val blocks = FreeWebNovelParser.parseChapter(
            chapterHtml(
                """
                <h4>Chapter 2 - Mysterious Little Girl </h4>
                <p> "Character status." Yuan <em>thought</em> it.
                </p><p> Name: Yuan
                </p><p>   </p><p> Updates by Freewebnovel. com </p>
                <p> Second &amp; last.</p>
                <div class="reader-ad-skip"><p>BUY NOW</p><script>var x=1</script></div>
                """.trimIndent(),
            ),
        )
        val texts = blocks.map { b -> (b as Block.Paragraph).spans.joinToString("") { it.text } }
        assertEquals(listOf("\"Character status.\" Yuan thought it.", "Name: Yuan", "Second & last."), texts)

        val first = (blocks[0] as Block.Paragraph).spans
        assertTrue(first.single { it.text == "thought" }.italic)
        assertFalse(first.first().italic)
    }

    @Test
    fun `paragraphs with blank pieces at the edges are trimmed`() {
        val blocks = FreeWebNovelParser.parseChapter(
            chapterHtml("<p> <i> </i>Death, he found, was <i>something </i>. <i> </i> </p><p> <br> Next </p>"),
        )
        val texts = blocks.map { b -> (b as Block.Paragraph).spans.joinToString("") { it.text } }
        assertEquals(listOf("Death, he found, was something .", "Next"), texts)
    }

    @Test
    fun `an error page is not chapter content`() {
        listOf("<html><body>Error 1020 Access denied</body></html>", chapterHtml("<h4>Only a title</h4>")).forEach {
            try {
                FreeWebNovelParser.parseChapter(it)
                fail("expected OnlineException")
            } catch (_: OnlineException) {
            }
        }
    }

    @Test
    fun `paths are validated`() {
        assertTrue(FreeWebNovelParser.isNovelPath("/novel/cultivation-online-novel"))
        assertTrue(FreeWebNovelParser.isChapterPath("/novel/cultivation-online-novel/chapter-2688"))
        assertFalse(FreeWebNovelParser.isNovelPath("/novel/../etc"))
        assertFalse(FreeWebNovelParser.isNovelPath("//evil.example/novel/a"))
        assertFalse(FreeWebNovelParser.isChapterPath("/novel/a/chapter-1?x=1"))
        assertFalse(FreeWebNovelParser.isChapterPath("https://evil.example/novel/a/chapter-1"))
    }

    @Test
    fun `cover comes from og image and only when hosted on the site`() {
        val page = { img: String ->
            "<html><head><meta property=\"og:image\" content=\"$img\"></head><body>" +
                "<div class=\"m-desc\"><h1 class=\"tit\">T</h1></div></body></html>"
        }
        val good = "https://freewebnovel.com/files/article/image/1/1427/1427s.jpg"
        assertEquals(good, FreeWebNovelParser.parseNovelDetails(page(good)).cover)
        assertEquals(good, FreeWebNovelParser.parseNovelDetails(page("/files/article/image/1/1427/1427s.jpg")).cover)
        assertEquals("", FreeWebNovelParser.parseNovelDetails(page("https://evil.example/files/a.jpg")).cover)
        assertEquals("", FreeWebNovelParser.parseNovelDetails(page("/files/../etc/passwd")).cover)
        assertTrue(FreeWebNovelParser.isCoverUrl(good))
        assertFalse(FreeWebNovelParser.isCoverUrl("https://freewebnovel.com/novel/a"))
    }
}
