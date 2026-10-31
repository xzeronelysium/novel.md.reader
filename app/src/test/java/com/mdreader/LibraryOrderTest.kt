package com.mdreader

import com.mdreader.data.Chapter
import com.mdreader.data.LibrarySort
import com.mdreader.data.Project
import com.mdreader.data.ageLabel
import com.mdreader.data.matching
import com.mdreader.data.ordered
import com.mdreader.data.withChapters
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrderTest {

    private fun project(id: String, name: String, imported: Long = 0, updated: Long = 0, chapters: Int = 3) = Project(
        id = id,
        name = name,
        treeUri = "",
        chapters = List(chapters) { Chapter("c$it", "/novel/$id/chapter-$it", remote = true) },
        importedAt = imported,
        updatedAt = updated,
    )

    private val shelf = listOf(
        project("a", "Beta", imported = 100),
        project("b", "alpha", imported = 50, updated = 300),
        project("c", "Gamma", imported = 200),
    )

    @Test
    fun `updated order puts the latest change first, falling back to import time`() {
        assertEquals(listOf("b", "c", "a"), shelf.ordered(LibrarySort.Updated) { 0 }.map { it.id })
    }

    @Test
    fun `read order follows last reading, unread novels trail by update time`() {
        val read = mapOf("a" to 900L, "c" to 400L)
        assertEquals(
            listOf("a", "c", "b"),
            shelf.ordered(LibrarySort.Read) { read[it.id] ?: 0L }.map { it.id },
        )
    }

    @Test
    fun `title order ignores case`() {
        assertEquals(listOf("b", "a", "c"), shelf.ordered(LibrarySort.Title) { 0 }.map { it.id })
    }

    @Test
    fun `filter matches names anywhere ignoring case and a blank query keeps all`() {
        assertEquals(listOf("b"), shelf.matching("  ALP ").map { it.id })
        assertEquals(3, shelf.matching("   ").size)
        assertEquals(0, shelf.matching("zzz").size)
    }

    @Test
    fun `ages read as short labels`() {
        val hour = 3_600_000L
        assertEquals("NOW", ageLabel(10 * hour, 10 * hour - 30 * 60_000L))
        assertEquals("5H", ageLabel(10 * hour, 5 * hour))
        assertEquals("3D", ageLabel(100 * 24 * hour, 97 * 24 * hour))
        assertEquals("2W", ageLabel(100 * 24 * hour, 86 * 24 * hour))
        assertEquals("4M", ageLabel(400 * 24 * hour, 280 * 24 * hour))
        assertEquals("NOW", ageLabel(5, 10))
    }

    @Test
    fun `a refresh that finds chapters bumps update time and the plus count`() {
        val before = project("n", "Novel", chapters = 3)
        val chapters = List(5) { Chapter("c$it", "/novel/n/chapter-$it", remote = true) }
        val after = before.withChapters("Novel", chapters, "", now = 1_000L)
        assertEquals(1_000L, after.updatedAt)
        assertEquals(2, after.newChapters)
        assertEquals(5, after.totalChapters)
        // A second find adds to the count; a check that finds nothing changes neither.
        val again = after.withChapters("Novel", chapters + Chapter("c5", "/novel/n/chapter-5", remote = true), "", now = 2_000L)
        assertEquals(3, again.newChapters)
        val same = again.withChapters("Novel", again.chapters, "", now = 3_000L)
        assertEquals(2_000L, same.updatedAt)
        assertEquals(3, same.newChapters)
    }
}
