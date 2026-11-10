package com.mdreader

import com.mdreader.data.Chapter
import com.mdreader.data.LibraryStore
import com.mdreader.data.Progress
import com.mdreader.data.Project
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The shelf's structure and its reading position live in different files, so scrolling stays cheap. */
class LibraryStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun project(id: String = "p1") = Project(
        id = id,
        name = "Novel",
        treeUri = "tree",
        chapters = listOf(
            Chapter("ch1.md", "content://1"),
            Chapter("ch2.md", "content://2"),
            Chapter("ch3.md", "content://3"),
        ),
        importedAt = 1L,
    )

    @Test
    fun progressGoesToItsOwnSmallFile() {
        val dir = tmp.newFolder()
        val store = LibraryStore(dir)
        store.addOrReplace(project())
        store.setProgress("p1", Progress(chapterIndex = 2, scrollIndex = 4, scrollOffset = 8))

        val library = File(dir, "library.json").readText()
        val progress = File(dir, "progress.json").readText()
        assertFalse("the library must not hold progress any more", library.contains("\"progress\""))
        assertTrue(progress.contains("chapterIndex"))
        assertTrue("progress must stay small", progress.length < 400)

        val again = LibraryStore(dir)
        assertEquals(2, again.progress("p1")!!.chapterIndex)
        assertEquals(4, again.progress("p1")!!.scrollIndex)
        assertEquals(1, again.load().size)
    }

    @Test
    fun textSizeLivesWithProgress() {
        val dir = tmp.newFolder()
        LibraryStore(dir).setTextSizeLevel(2)
        assertEquals(2, LibraryStore(dir).textSizeLevel())
        assertFalse(File(dir, "library.json").exists())
    }

    @Test
    fun legacyProgressIsMigratedOutOfTheLibraryFile() {
        val dir = tmp.newFolder()
        File(dir, "library.json").writeText(
            """{"projects":[{"id":"p1","name":"N","treeUri":"t","importedAt":5,""" +
                """"chapters":[{"name":"a.md","uri":"u1"}]}],""" +
                """"progress":{"p1":{"chapterIndex":1,"scrollIndex":2,"scrollOffset":3,"updatedAt":9}},"textSize":2}""",
        )
        val store = LibraryStore(dir)
        assertEquals(1, store.progress("p1")!!.chapterIndex)
        assertEquals(3, store.progress("p1")!!.scrollOffset)
        assertEquals(2, store.textSizeLevel())

        assertTrue(File(dir, "progress.json").isFile)
        assertFalse("the old block must be gone", File(dir, "library.json").readText().contains("\"progress\""))

        val again = LibraryStore(dir)
        assertEquals(1, again.progress("p1")!!.chapterIndex)
        assertEquals(2, again.textSizeLevel())
    }

    @Test
    fun summarySurvivesAReload() {
        val dir = tmp.newFolder()
        val novel = project().copy(
            onlinePath = "/novel/x",
            author = "Someone",
            authorPath = "/author/someone",
            status = "Ongoing",
            synopsis = "A synopsis.",
            genres = listOf("Fantasy", "Action"),
            rating = "4.6",
            votes = 1940,
            summaryAt = 123L,
        )
        LibraryStore(dir).addOrReplace(novel)

        val again = LibraryStore(dir).load().single()
        assertEquals("Someone", again.author)
        assertEquals("/author/someone", again.authorPath)
        assertEquals("Ongoing", again.status)
        assertEquals("A synopsis.", again.synopsis)
        assertEquals(listOf("Fantasy", "Action"), again.genres)
        assertEquals("4.6", again.rating)
        assertEquals(1940, again.votes)
        assertEquals(123L, again.summaryAt)
    }

    @Test
    fun removingAProjectForgetsItsProgress() {
        val dir = tmp.newFolder()
        val store = LibraryStore(dir)
        store.addOrReplace(project())
        store.setProgress("p1", Progress(chapterIndex = 1))
        store.remove("p1")
        assertEquals(null, LibraryStore(dir).progress("p1"))
        assertEquals(0, LibraryStore(dir).load().size)
    }

    @Test
    fun progressIsWrittenOnlyWhenItChanges() {
        val dir = tmp.newFolder()
        val store = LibraryStore(dir)
        store.addOrReplace(project())
        store.setProgress("p1", Progress(chapterIndex = 1, scrollIndex = 5))
        val stamp = File(dir, "progress.json").lastModified()
        Thread.sleep(10)
        store.setProgress("p1", Progress(chapterIndex = 1, scrollIndex = 5))
        assertEquals("an unchanged position must not touch the file", stamp, File(dir, "progress.json").lastModified())
    }
}
