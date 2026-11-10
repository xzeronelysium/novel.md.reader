package com.mdreader

import com.mdreader.data.Chapter
import com.mdreader.data.Project
import com.mdreader.data.summaryIsStale
import com.mdreader.data.online.asDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detail page's header comes from the stored record, and only old summaries are refetched. */
class NovelSummaryTest {

    private val novel = Project(
        id = "p1",
        name = "A Novel",
        treeUri = "",
        chapters = listOf(Chapter("Chapter 1", "/novel/x/chapter-1", remote = true)),
        importedAt = 1L,
        onlinePath = "/novel/x",
        totalChapters = 1,
        coverUrl = "https://example.test/c.jpg",
        author = "Someone",
        authorPath = "/author/someone",
        status = "Ongoing",
        synopsis = "A synopsis.",
        genres = listOf("Fantasy"),
        rating = "4.6",
        votes = 12,
        summaryAt = 1_000L,
    )

    @Test
    fun detailsComeFromWhatTheShelfAlreadyKnows() {
        val details = novel.asDetails()
        assertEquals("A Novel", details.title)
        assertEquals("Someone", details.author)
        assertEquals("/author/someone", details.authorPath)
        assertEquals("Ongoing", details.status)
        assertEquals("A synopsis.", details.synopsis)
        assertEquals(listOf("Fantasy"), details.genres)
        assertEquals("4.6", details.rating)
        assertEquals(12, details.votes)
        assertEquals("https://example.test/c.jpg", details.cover)
    }

    @Test
    fun aSummaryIsStaleWhenMissingOldOrAuthorless() {
        assertFalse(novel.summaryIsStale(now = 2_000L, ttl = 10_000L))
        assertTrue(novel.summaryIsStale(now = 20_000L, ttl = 10_000L))
        assertTrue(novel.copy(summaryAt = 0L).summaryIsStale(now = 2_000L))
        assertTrue(novel.copy(author = "").summaryIsStale(now = 2_000L))
    }
}
