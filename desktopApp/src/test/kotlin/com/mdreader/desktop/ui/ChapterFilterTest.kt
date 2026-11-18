package com.mdreader.desktop.ui

import com.mdreader.data.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAppUiTest {
    private val chapters = listOf(
        Chapter("chapter_1.md", "1"),
        Chapter("chapter_2.txt", "2"),
        Chapter("Snow_Country.md", "3"),
    )

    @Test
    fun blankQueryPreservesAllOriginalIndices() {
        assertEquals(listOf(0, 1, 2), filterChapters(chapters, "  ").map { it.index })
    }

    @Test
    fun queryMatchesDisplayTitleCaseInsensitively() {
        assertEquals(listOf(2), filterChapters(chapters, "snow country").map { it.index })
    }

    @Test
    fun queryMatchesFilenameAndChapterNumber() {
        assertEquals(listOf(1), filterChapters(chapters, "chapter_2").map { it.index })
        assertEquals(listOf(1), filterChapters(chapters, "2").map { it.index })
    }

    @Test
    fun queryWithNoMatchesReturnsEmptyList() {
        assertEquals(emptyList(), filterChapters(chapters, "missing title"))
    }
}
