package com.mdreader

import com.mdreader.data.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterTitleTest {

    @Test
    fun `underscored lowercase filenames become readable headings`() {
        assertEquals("Chapter 7", Chapter("chapter_7.md", "uri").title)
        assertEquals("The Road Out", Chapter("the_road_out.txt", "uri").title)
    }

    @Test
    fun `existing capitalization is preserved`() {
        assertEquals("Snow Country Notes", Chapter("Snow Country Notes.md", "uri").title)
        assertEquals("PART ONE", Chapter("PART_ONE.md", "uri").title)
    }

    @Test
    fun `a file without an extension still has a title`() {
        assertEquals("Prologue", Chapter("prologue", "uri").title)
    }
}
