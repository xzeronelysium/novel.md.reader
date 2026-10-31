package com.mdreader

import com.mdreader.data.Chapter
import com.mdreader.data.DownloadRequest
import com.mdreader.data.estimatedBytes
import com.mdreader.data.estimatedDuration
import com.mdreader.data.formatBytes
import com.mdreader.data.parseChapterCount
import com.mdreader.data.planDelete
import com.mdreader.data.DownloadProgress
import com.mdreader.data.Project
import com.mdreader.data.Vault
import com.mdreader.data.downloadChapters
import com.mdreader.data.planDownload
import com.mdreader.data.online.OnlineException
import com.mdreader.md.Block
import com.mdreader.md.InlineSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChapterDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val chapters = List(30) { Chapter("Chapter ${it + 1}", "/novel/x/chapter-${it + 1}", remote = true) }
    private val project = Project("p-1", "Novel", "", chapters, 0, onlinePath = "/novel/x")
    private fun vault() = Vault(File(tmp.root, "vault"))
    private fun body(text: String) = listOf<Block>(Block.Paragraph(listOf(InlineSpan(text))))

    // ----- planning

    @Test
    fun `next 10 starts at the chapter you would read next and skips saved ones`() {
        val saved = setOf(chapters[4].uri, chapters[6].uri)
        assertEquals(
            listOf(3, 5, 7, 8, 9, 10, 11, 12, 13, 14),
            planDownload(chapters, saved, resumeIndex = 3, DownloadRequest.Next(10)),
        )
    }

    @Test
    fun `unread means from the resume chapter to the end, all means everything missing`() {
        val saved = setOf(chapters[0].uri)
        assertEquals((10 until 30).toList(), planDownload(chapters, saved, 10, DownloadRequest.Unread))
        assertEquals((1 until 30).toList(), planDownload(chapters, saved, 10, DownloadRequest.All))
    }

    @Test
    fun `a novel not started plans from the first chapter`() {
        assertEquals((0 until 10).toList(), planDownload(chapters, emptySet(), null, DownloadRequest.Next(10)))
        assertEquals((0 until 30).toList(), planDownload(chapters, emptySet(), null, DownloadRequest.Next(50)))
    }

    @Test
    fun `everything saved plans nothing`() {
        assertEquals(emptyList<Int>(), planDownload(chapters, chapters.map { it.uri }.toSet(), 0, DownloadRequest.All))
    }

    // ----- running

    @Test
    fun `chapters are fetched written and counted`() = runBlocking {
        val v = vault()
        val seen = mutableListOf<DownloadProgress>()
        val result = downloadChapters(project, listOf(0, 1, 2), v, "Author", "2026-10-31", cooldownMs = 0,
            fetch = { body("text of $it") }, onProgress = { seen += it })
        assertEquals(3, v.count(project))
        assertTrue(v.read(project, chapters[1])!!.contains("text of /novel/x/chapter-2"))
        assertFalse(result.running)
        assertEquals("3 chapters saved", result.message)
        assertEquals(3, seen.last().done)
        assertTrue(File(v.folder(project), "_novel.md").isFile)
    }

    @Test
    fun `a second run fetches only what is missing`() = runBlocking {
        val v = vault()
        val fetched = mutableListOf<String>()
        val fetch: suspend (String) -> List<Block> = { fetched += it; body("x") }
        downloadChapters(project, listOf(0, 1, 2), v, null, "d", cooldownMs = 0, fetch = fetch, onProgress = {})
        fetched.clear()
        val result = downloadChapters(project, listOf(0, 1, 2, 3), v, null, "d", cooldownMs = 0, fetch = fetch, onProgress = {})
        assertEquals(listOf("/novel/x/chapter-4"), fetched)
        assertEquals(4, v.count(project))
        assertEquals("4 chapters saved", result.message)
    }

    @Test
    fun `one failed chapter does not stop the batch`() = runBlocking {
        val v = vault()
        val result = downloadChapters(project, listOf(0, 1, 2, 3), v, null, "d", cooldownMs = 0,
            fetch = { if (it.endsWith("-2")) throw OnlineException("This chapter came back empty.") else body("ok") },
            onProgress = {})
        assertEquals(3, v.count(project))
        assertEquals(1, result.failed)
        assertEquals("3 saved, 1 failed. Tap Download to retry those.", result.message)
        assertFalse(v.has(project, chapters[1]))
    }

    @Test
    fun `three failures in a row pause the run and keep what was saved`() = runBlocking {
        val v = vault()
        var calls = 0
        val result = downloadChapters(project, (0 until 10).toList(), v, null, "d", cooldownMs = 0,
            fetch = { calls++; if (calls <= 2) body("ok") else throw OnlineException("rate limited") },
            onProgress = {})
        assertEquals(2, v.count(project))
        assertEquals(5, calls)
        assertTrue(result.message!!.startsWith("Paused after 2 saved"))
        assertFalse(result.running)
    }

    @Test
    fun `a success resets the failure streak`() = runBlocking {
        val v = vault()
        var calls = 0
        val result = downloadChapters(project, (0 until 8).toList(), v, null, "d", cooldownMs = 0,
            maxConsecutiveFailures = 3,
            fetch = { calls++; if (calls % 3 == 0) throw OnlineException("blip") else body("ok") },
            onProgress = {})
        assertEquals(8, result.done)
        assertTrue(result.message!!.contains("failed"))
        assertFalse(result.message!!.startsWith("Paused"))
    }

    @Test
    fun `cancelling stops cleanly and leaves finished chapters`() = runBlocking {
        val v = vault()
        var calls = 0
        var cancelled = false
        try {
            downloadChapters(project, (0 until 10).toList(), v, null, "d", cooldownMs = 0,
                fetch = { calls++; if (calls == 4) throw CancellationException("stop") else body("ok") },
                onProgress = {})
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(3, v.count(project))
    }

    @Test
    fun `nothing to download finishes at once`() = runBlocking {
        val result = downloadChapters(project, emptyList(), vault(), null, "d", cooldownMs = 0, onProgress = {})
        assertFalse(result.running)
        assertEquals(0, result.total)
    }

    // ----- delete planning and estimates

    @Test
    fun `read chapters are the saved ones before the current chapter`() {
        val saved = setOf(0, 1, 2, 5, 9).map { chapters[it].uri }.toSet()
        val plan = planDelete(chapters, saved, resumeIndex = 5)
        assertEquals(listOf(0, 1, 2).map { chapters[it].uri }, plan.read)
        assertEquals(listOf(0, 1, 2, 5, 9).map { chapters[it].uri }, plan.all)
    }

    @Test
    fun `a novel not started has nothing read`() {
        val saved = setOf(chapters[0].uri, chapters[1].uri)
        val plan = planDelete(chapters, saved, resumeIndex = null)
        assertEquals(emptyList<String>(), plan.read)
        assertEquals(2, plan.all.size)
    }

    @Test
    fun `next with a count can be asked for more than is left`() {
        assertEquals((27 until 30).toList(), planDownload(chapters, emptySet(), 27, DownloadRequest.Next(500)))
        assertEquals(emptyList<Int>(), planDownload(chapters, emptySet(), 0, DownloadRequest.Next(0)))
    }

    @Test
    fun `sizes and times read well`() {
        assertEquals("250 KB", formatBytes(estimatedBytes(25)))
        assertEquals("3.3 MB", formatBytes(estimatedBytes(332)))
        assertEquals("27 MB", formatBytes(estimatedBytes(2689)))
        assertEquals("0 B", formatBytes(0))
        assertEquals("41 s", estimatedDuration(24))
        assertEquals("1.4 min", estimatedDuration(50))
        assertEquals("9.0 min", estimatedDuration(317))
        assertEquals("1.3 h", estimatedDuration(2689))
    }

    @Test
    fun `a typed count is cleaned and kept within what is left`() {
        assertEquals(25, parseChapterCount("25", remaining = 300))
        assertEquals(25, parseChapterCount(" 2 5 ", remaining = 300))
        assertEquals(1, parseChapterCount("0", remaining = 300))
        assertEquals(300, parseChapterCount("99999", remaining = 300))
        assertEquals(null, parseChapterCount("", remaining = 300))
        assertEquals(null, parseChapterCount("abc", remaining = 300))
        assertEquals(1, parseChapterCount("5", remaining = 0))
        assertEquals(300, parseChapterCount("12345678901234", remaining = 300))
    }
}
