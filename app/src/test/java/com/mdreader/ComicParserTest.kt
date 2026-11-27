package com.mdreader

import com.mdreader.data.comics.BatCaveParser
import com.mdreader.data.comics.ComicOkHttp
import com.mdreader.data.comics.MangakakalotParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two comic parsers against excerpts of the real pages, selectors copied from the live
 * HTML the sources fetched. Pure functions, no network.
 */
class ComicParserTest {

    @Test
    fun `kakalot search reads the story items and the next page`() {
        val html = """
            <div class="panel_story_list">
              <div class="story_item">
                <a href="https://www.mangakakalot.gg/manga/dimensional-mercenaryy">
                  <img src="https://img-r1.2xstorage.com/thumb/dimensional-mercenaryy.webp" alt="Dimensional Mercenary" class="lazy">
                </a>
                <div class="story_item_right">
                  <h3 class="story_name"><a href="https://www.mangakakalot.gg/manga/dimensional-mercenaryy">Dimensional Mercenary</a></h3>
                  <em class="story_chapter"><a rel="nofollow" href="https://www.mangagoku.com/manga/dimensional-mercenaryy/chapter-303"> Chapter 303 </a></em>
                </div>
              </div>
              <div class="story_item">
                <a href="https://www.mangakakalot.gg/manga/mercenary-alias"></a>
                <div class="story_item_right">
                  <h3 class="story_name"><a href="https://www.mangakakalot.gg/manga/mercenary-alias">Mercenary Alias</a></h3>
                </div>
              </div>
            </div>
            <div class="panel_page_number"><div class="group_page">
              <a class="page_blue page_disabled">Previous</a>
              <div class="page_select">1</div>
              <a href="https://www.mangakakalot.gg/search/story/mercenary?page=2" class="page_blue">Next</a>
            </div></div>
        """.trimIndent()
        val origin = "https://www.mangakakalot.gg"
        val hits = MangakakalotParser.parseSearch(html, origin)
        assertEquals(2, hits.size)
        assertEquals("Dimensional Mercenary", hits[0].title)
        assertEquals("/manga/dimensional-mercenaryy", hits[0].path)
        assertEquals("https://img-r1.2xstorage.com/thumb/dimensional-mercenaryy.webp", hits[0].cover)
        assertEquals("Mercenary Alias", hits[1].title)
        assertTrue(MangakakalotParser.hasNextPage(html, 1))
    }

    @Test
    fun `kakalot details read the info list and the summary box`() {
        val html = """
            <div class="manga-info-top">
              <div class="manga-info-pic">
                <img src="https://img-r1.2xstorage.com/thumb/the-young-lady.webp" alt="Cover">
              </div>
              <div class="manga-info-content">
                <ul class="manga-info-text">
                  <li><h1>The Young Lady Hates Living a Diligent Life!</h1></li>
                  <li>Author(s) : Se-U Kwon, Yu-Ra </li>
                  <li>Status : Ongoing</li>
                  <li class="genres">Genres : <a href="/genre/comedy"> Comedy </a>, <a href="/genre/fantasy"> Fantasy </a></li>
                </ul>
              </div>
            </div>
            <div id="contentBox">
              <h2><p>The Young Lady summary: </p></h2>
              To think that the end of the perfect life would be death from overwork!
            </div>
        """.trimIndent()
        val details = MangakakalotParser.parseDetails(html, "https://www.mangakakalot.gg")
        assertEquals("The Young Lady Hates Living a Diligent Life!", details.title)
        assertEquals("Se-U Kwon, Yu-Ra", details.author)
        assertEquals("Ongoing", details.status)
        assertEquals(listOf("Comedy", "Fantasy"), details.genres)
        assertTrue(details.description.startsWith("To think"))
        assertEquals("https://img-r1.2xstorage.com/thumb/the-young-lady.webp", details.cover)
    }

    @Test
    fun `kakalot chapters page read newest first with its pagination`() {
        val json = """
            {"success":true,"data":{
              "chapters":[
                {"chapter_name":"Chapter 8","chapter_slug":"chapter-8","chapter_num":8,"updated_at":"2026-11-26T04:12:55Z","view":533},
                {"chapter_name":"Chapter 7","chapter_slug":"chapter-7","chapter_num":7,"updated_at":"2026-11-25T04:12:30Z","view":114}
              ],
              "pagination":{"total":260,"limit":50,"offset":0,"has_more":true}
            }}
        """.trimIndent()
        val page = MangakakalotParser.parseChapterPage(json, "the-young-lady")
        assertEquals(listOf("Chapter 8", "Chapter 7"), page.map { it.title })
        assertEquals("https://www.mangagoku.com/manga/the-young-lady/chapter-8", page[0].path)
        assertEquals("2026-11-26T04:12:55Z", page[0].date)
        assertTrue(MangakakalotParser.hasMoreChapters(json))
        val last = json.replace("\"has_more\":true", "\"has_more\":false")
        assertTrue(!MangakakalotParser.hasMoreChapters(last))
    }

    @Test
    fun `kakalot pages read the reader images and nothing else`() {
        val html = """
            <html><body>
              <img src="https://img-r1.2xstorage.com/the-young-lady/1/0.webp" alt="The Young Lady Chapter 1 page 1 - MangaGoku" class="block">
              <img src="https://img-r1.2xstorage.com/the-young-lady/1/1.webp" alt="The Young Lady Chapter 1 page 2 - MangaGoku" class="block">
              <img src="https://img-r1.2xstorage.com/thumb/someone-else.webp" alt="Someone Else">
            </body></html>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://img-r1.2xstorage.com/the-young-lady/1/0.webp",
                "https://img-r1.2xstorage.com/the-young-lady/1/1.webp",
            ),
            MangakakalotParser.parsePages(html),
        )
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

    @Test
    fun `inline data covers decode to their bytes`() {
        val png = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val uri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)

        val decoded = ComicOkHttp.dataUriBytes(uri)

        assertTrue(decoded.contentEquals(png))
    }

    @Test
    fun `a bad image url answers null, never a crash`() {
        assertNull(ComicOkHttp.dataUriBytes("data:text/html;base64,SGk="))
        assertNull(ComicOkHttp.dataUriBytes("data:image/png,not-base64-at-all"))
        assertNull(ComicOkHttp.dataUriBytes("javascript:alert(1)"))
        assertNull(ComicOkHttp.dataUriBytes("http://not-even-https.example/cover.jpg"))
    }
}
