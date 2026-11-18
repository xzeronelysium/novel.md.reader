package com.mdreader

import com.mdreader.data.NaturalSort
import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalSortTest {

    @Test
    fun `chapter numbers sort numerically`() {
        val input = listOf("chapter_10.md", "chapter_2.md", "chapter_1.md", "chapter_20.md")
        val sorted = input.sortedWith(NaturalSort)

        assertEquals(
            listOf("chapter_1.md", "chapter_2.md", "chapter_10.md", "chapter_20.md"),
            sorted,
        )
    }

    @Test
    fun `numbers of different widths compare by value`() {
        val input = listOf("ch100.txt", "ch9.txt", "ch10.txt", "ch2.txt")
        assertEquals(
            listOf("ch2.txt", "ch9.txt", "ch10.txt", "ch100.txt"),
            input.sortedWith(NaturalSort),
        )
    }

    @Test
    fun `ordering ignores case without losing stability`() {
        val input = listOf("Chapter 3.md", "chapter 1.md", "Chapter 2.md")
        assertEquals(
            listOf("chapter 1.md", "Chapter 2.md", "Chapter 3.md"),
            input.sortedWith(NaturalSort),
        )
    }

    @Test
    fun `leading zeros do not change numeric value`() {
        val input = listOf("ch010.md", "ch002.md", "ch1.md")
        assertEquals(
            listOf("ch1.md", "ch002.md", "ch010.md"),
            input.sortedWith(NaturalSort),
        )
    }

    @Test
    fun `plain names fall back to alphabetical`() {
        val input = listOf("epilogue.md", "afterword.md", "prologue.md")
        assertEquals(
            listOf("afterword.md", "epilogue.md", "prologue.md"),
            input.sortedWith(NaturalSort),
        )
    }

    @Test
    fun `prefix before the number still matters`() {
        val input = listOf("bonus2.md", "chapter1.md", "bonus10.md", "chapter2.md")
        assertEquals(
            listOf("bonus2.md", "bonus10.md", "chapter1.md", "chapter2.md"),
            input.sortedWith(NaturalSort),
        )
    }
}
