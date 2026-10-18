package com.mdreader

import com.mdreader.data.Chapter
import com.mdreader.ui.matching
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterSearchTest {

    private val chapters = listOf(
        Chapter("chapter_1.md", "uri-1"),
        Chapter("chapter_7.md", "uri-7"),
        Chapter("Snow Country Notes.txt", "uri-snow"),
    )

    @Test
    fun `blank query keeps every chapter and its original index`() {
        val result = chapters.matching("  ")

        assertEquals(listOf(0, 1, 2), result.map { it.index })
    }

    @Test
    fun `search matches the display title`() {
        assertEquals(listOf(2), chapters.matching("snow country").map { it.index })
    }

    @Test
    fun `search matches chapter number in title and filename`() {
        assertEquals(listOf(1), chapters.matching("7").map { it.index })
    }

    @Test
    fun `search matches the raw filename`() {
        assertEquals(listOf(1), chapters.matching("chapter_7").map { it.index })
    }

    @Test
    fun `search is case insensitive`() {
        assertEquals(listOf(2), chapters.matching("SNOW COUNTRY").map { it.index })
    }
}
