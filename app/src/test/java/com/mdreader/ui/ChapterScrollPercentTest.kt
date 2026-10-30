package com.mdreader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterScrollPercentTest {
    @Test fun tracksPositionWithinBlocks() {
        assertEquals(0, chapterScrollPercent(0, 20, 100, 4, false))
        assertEquals(37, chapterScrollPercent(1, -50, 100, 4, false))
        assertEquals(99, chapterScrollPercent(3, -100, 100, 4, false))
    }

    @Test fun completesAtEndIncludingShortChapters() {
        assertEquals(100, chapterScrollPercent(0, 0, 100, 1, true))
        assertEquals(100, chapterScrollPercent(3, -20, 100, 4, true))
    }

    @Test fun handlesEmptyAndZeroSizedItems() {
        assertEquals(0, chapterScrollPercent(0, 0, 0, 0, false))
        assertEquals(50, chapterScrollPercent(1, 0, 0, 2, false))
    }
}
