package com.mdreader

import com.mdreader.data.comics.BatCaveParser
import com.mdreader.data.comics.WeebCentralParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two comic parsers against excerpts of the real pages (selectors copied from the live HTML,
 * which is also what the Mihon extensions use). Pure functions, no network.
 */
class ComicParserTest {

    private val weebSearch = """
        <article class="bg-base-300 flex gap-4 p-4">
          <section class="w-full lg:w-[25%]">
            <a href="https://weebcentral.com/series/01J76XYCPSY3C4BNPBRY8JMCBE/Solo-Leveling">
              <article class="hidden lg:block w-full aspect-4/6 overflow-hidden">
                <picture>
                  <source srcset="https://temp.compsci88.com/cover/normal/01J76XYCPSY3C4BNPBRY8JMCBE.webp" width="200" height="300" type="image/webp">
                  <img src="https://temp.compsci88.com/cover/fallback/01J76XYCPSY3C4BNPBRY8JMCBE.jpg" alt="Solo Leveling cover">
                </picture>
              </article>
              <div class="text-ellipsis truncate text-white text-center text-lg z-20 w-[90%]">Solo Leveling</div>
            </a>
          </section>
        </article>
        <article class="bg-base-300 flex gap-4 p-4">
          <section>
            <a href="/series/01J76XYXYZ/Solo-Leveling-Ragnarok">
              <img src="https://temp.compsci88.com/cover/fallback/01J76XYXYZ.jpg" alt="Solo Leveling: Ragnarok cover">
              <div class="text-ellipsis truncate">Solo Leveling: Ragnarok</div>
            </a>
          </section>
        </article>
        <button class="btn" hx-get="/search/data?limit=32&amp;offset=32&amp;text=solo">Load more</button>
    """.trimIndent()

    @Test
    fun `weeb search reads title path cover and next page`() {
        val hits = WeebCentralParser.parseSearch(weebSearch)
        assertEquals(2, hits.size)
        assertEquals("Solo Leveling", hits[0].title)
        assertEquals("/series/01J76XYCPSY3C4BNPBRY8JMCBE/Solo-Leveling", hits[0].path)
        assertEquals(
            "https://temp.compsci88.com/cover/normal/01J76XYCPSY3C4BNPBRY8JMCBE.webp",
            hits[0].cover,
        )
        assertEquals("/series/01J76XYXYZ/Solo-Leveling-Ragnarok", hits[1].path)
        assertEquals("Solo Leveling: Ragnarok", hits[1].title)
        assertTrue(WeebCentralParser.hasNextPage(weebSearch))
    }

    @Test
    fun `weeb chapters keep the site order and read name and date`() {
        val html = """
            <div class="flex items-center" x-data="{ new_chapter: checkNewChapter('2024-09-07T17:04:15.717343Z') }">
              <a href="/chapters/01J76XZ666GREP4DQDKEP1YDZG" class="hover:bg-base-300 flex-1 flex items-center p-2">
                <span class="grow flex items-center gap-2"><span class="">Chapter 200</span></span>
                <time datetime="2024-09-07T17:04:15.717Z">Sep 7</time>
              </a>
            </div>
            <div><a href="/chapters/01J76XZ6614Z72ZP8CDK0PV0V3">
              <span class="grow"><span>Chapter 199</span></span>
            </a></div>
        """.trimIndent()
        val chapters = WeebCentralParser.parseChapters(html)
        assertEquals(listOf("Chapter 200", "Chapter 199"), chapters.map { it.title })
        assertEquals("/chapters/01J76XZ666GREP4DQDKEP1YDZG", chapters[0].path)
        assertEquals("2024-09-07T17:04:15.717Z", chapters[0].date)
    }

    @Test
    fun `weeb pages read the strip images in order`() {
        val html = """
            <section id="chapter-images">
              <img src="https://hot.planeptune.us/manga/Solo-Leveling/0200-001.png">
              <img src="https://hot.planeptune.us/manga/Solo-Leveling/0200-002.png">
            </section>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://hot.planeptune.us/manga/Solo-Leveling/0200-001.png",
                "https://hot.planeptune.us/manga/Solo-Leveling/0200-002.png",
            ),
            WeebCentralParser.parsePages(html),
        )
    }

    @Test
    fun `weeb details read the info list`() {
        val html = """
            <h1>Solo Leveling</h1>
            <meta property="og:image" content="https://temp.compsci88.com/cover/fallback/01J76.jpg">
            <ul>
              <li><strong>Author(s): </strong><span><a href="#">GEE So-Lyung</a></span></li>
              <li><strong>Tags(s): </strong><span><a href="#">Action</a>,</span><span><a href="#">Fantasy</a></span></li>
              <li><strong>Type: </strong><a href="#">Manhwa</a></li>
              <li><strong>Status: </strong><a href="#">Complete</a></li>
              <li><strong>Description</strong><p>E-class hunter Jinwoo Sung is the weakest of them all.</p></li>
            </ul>
        """.trimIndent()
        val details = WeebCentralParser.parseDetails(html)
        assertEquals("Solo Leveling", details.title)
        assertEquals("GEE So-Lyung", details.author)
        assertEquals("Complete", details.status)
        assertEquals(listOf("Action", "Fantasy"), details.genres)
        assertTrue(details.description.startsWith("E-class hunter"))
        assertEquals("https://temp.compsci88.com/cover/fallback/01J76.jpg", details.cover)
    }

    @Test
    fun `batcave chapters come from window data`() {
        val html = """
            <script>
              window.__DATA__ = {"news_id":"4211","chapters":[
                {"id":"98765abc","posi":2,"title":"Chapter 2","date":"2024-02-02"},
                {"id":"98764def","posi":1,"title":"Chapter 1","date":"2024-01-01"}
              ],"xhash":"-x9"};
            </script>
        """.trimIndent()
        val chapters = BatCaveParser.parseChapters(html)
        // Reader order: oldest first.
        assertEquals(listOf("Chapter 1", "Chapter 2"), chapters.map { it.title })
        assertEquals("/reader/4211/98764def-x9", chapters[0].path)
    }

    @Test
    fun `batcave page images read the ajax payload`() {
        val json = """{"success":true,"data":{"images":["https://img.batcave.biz/a/1.jpg","https://img.batcave.biz/a/2.jpg"]}}"""
        assertEquals(
            listOf("https://img.batcave.biz/a/1.jpg", "https://img.batcave.biz/a/2.jpg"),
            BatCaveParser.parsePageImages(json),
        )
    }

    @Test
    fun `batcave reader path splits into news and chapter ids`() {
        assertEquals("4211" to "98764def-x9", BatCaveParser.chapterIds("/reader/4211/98764def-x9"))
        assertEquals(null, BatCaveParser.chapterIds("/comix/some-comic"))
    }

    @Test
    fun `batcave search reads readed cards`() {
        val html = """
            <div id="dle-content">
              <div class="readed">
                <div class="readed__title"><a href="https://batcave.biz/comix/4211-some-comic.html">Some Comic</a></div>
                <div class="readed__img"><img src="https://batcave.biz/uploads/posts/4211.jpg"></div>
              </div>
            </div>
        """.trimIndent()
        val hits = BatCaveParser.parseSearch(html, "https://batcave.biz")
        assertEquals(1, hits.size)
        assertEquals("Some Comic", hits[0].title)
        assertEquals("/comix/4211-some-comic.html", hits[0].path)
    }
}
