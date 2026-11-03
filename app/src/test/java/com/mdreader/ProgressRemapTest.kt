package com.mdreader

import com.mdreader.data.Chapter
import com.mdreader.data.Progress
import com.mdreader.data.remapTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressRemapTest {

    private fun list(vararg paths: String) = paths.map { Chapter(it, it, remote = true) }

    @Test
    fun `progress follows its chapter when new chapters are inserted before it`() {
        val saved = Progress(1, 4, 20, chapterKey = "b")
        val remapped = saved.remapTo(list("x", "a", "b", "c"))
        assertEquals(2, remapped.chapterIndex)
        assertEquals(4, remapped.scrollIndex)
        assertEquals(20, remapped.scrollOffset)
    }

    @Test
    fun `progress without a key keeps its index, clamped`() {
        assertEquals(1, Progress(1, 3, 3).remapTo(list("a", "b", "c")).chapterIndex)
        assertEquals(1, Progress(9).remapTo(list("a", "b")).chapterIndex)
    }

    @Test
    fun `each tab keeps its own position through a list refresh`() {
        val saved = Progress(1, 4, 20, chapterKey = "b", rewritten = true, rewriteIndex = 9, rewriteOffset = 5)
        val remapped = saved.remapTo(list("x", "a", "b"))
        assertEquals(true, remapped.rewritten)
        assertEquals(4, remapped.scrollIndex)
        assertEquals(9, remapped.rewriteIndex)
        assertEquals(5, remapped.rewriteOffset)
        // A vanished chapter forgets both positions and goes back to the original.
        val lost = saved.remapTo(list("a", "c"))
        assertEquals(false, lost.rewritten)
        assertEquals(0, lost.rewriteIndex)
    }

    @Test
    fun `a vanished chapter falls back to its index and forgets the scroll position`() {
        val remapped = Progress(2, 7, 9, chapterKey = "gone").remapTo(list("a", "b", "c"))
        assertEquals(2, remapped.chapterIndex)
        assertEquals(0, remapped.scrollIndex)
        assertEquals("c", remapped.chapterKey)
    }

    @Test
    fun `local chapters are never remote`() {
        assertNull(Progress(0).chapterKey)
        assertEquals("Chapter 7", Chapter("chapter_7.md", "u").title)
        assertEquals("Chapter 7 - The Gate", Chapter("Chapter 7 - The Gate", "/p", remote = true).title)
    }
}
