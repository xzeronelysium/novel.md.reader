package com.mdreader

import com.mdreader.data.Chapter
import com.mdreader.data.Project
import com.mdreader.data.Vault
import com.mdreader.md.Block
import com.mdreader.md.InlineSpan
import com.mdreader.md.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun chapter(n: Int, title: String = "Chapter $n") =
        Chapter(name = title, uri = "/novel/x/chapter-$n", remote = true)

    private fun project(vararg chapters: Chapter) = Project(
        id = "11111111-2222-3333-4444-555555555555",
        name = "Novel",
        treeUri = "",
        chapters = chapters.toList(),
        importedAt = 0,
        onlinePath = "/novel/x",
    )

    private fun vault() = Vault(File(tmp.root, "vault"))

    @Test
    fun `a written chapter reads back and counts as downloaded`() {
        val c = chapter(1, "The Start")
        val p = project(c)
        val v = vault()
        assertFalse(v.has(p, c))
        assertNull(v.read(p, c))

        v.write(p, c, 0, "# The Start\n\nHello.\n")
        assertTrue(v.has(p, c))
        assertEquals("# The Start\n\nHello.\n", v.read(p, c))
        assertEquals(setOf(c.uri), v.downloaded(p))
        assertEquals(1, v.count(p))
    }

    @Test
    fun `downloads are remembered by a fresh vault on the same folder`() {
        val a = chapter(1)
        val b = chapter(2)
        val p = project(a, b)
        vault().apply {
            write(p, a, 0, "one")
            write(p, b, 1, "two")
        }
        val reopened = vault()
        assertEquals(setOf(a.uri, b.uri), reopened.downloaded(p))
        assertEquals("two", reopened.read(p, b))
    }

    @Test
    fun `a chapter whose file was deleted no longer counts`() {
        val c = chapter(5)
        val p = project(c)
        val v = vault()
        v.write(p, c, 0, "text")
        v.folder(p).listFiles()!!.filter { it.name.endsWith(".md") }.forEach { it.delete() }
        assertFalse(vault().has(p, c))
    }

    @Test
    fun `a line torn by a crash is ignored and the rest still count`() {
        val a = chapter(1)
        val b = chapter(2)
        val p = project(a, b)
        val v = vault()
        v.write(p, a, 0, "one")
        v.write(p, b, 1, "two")
        File(v.folder(p), "_index.jsonl").appendText("{\"p\":\"/novel/x/chapter-3\",\"f\":\"00")
        val reopened = vault()
        assertEquals(setOf(a.uri, b.uri), reopened.downloaded(p))
    }

    @Test
    fun `an unfinished temp file is never a chapter`() {
        val c = chapter(7)
        val p = project(c)
        val v = vault()
        File(v.folder(p), "0007 - Chapter 7.md.tmp").apply { parentFile!!.mkdirs() }.writeText("half")
        assertFalse(v.has(p, c))
    }

    @Test
    fun `a changed title keeps one file for the chapter`() {
        val p = project(chapter(4))
        val v = vault()
        v.write(p, chapter(4, "Old title"), 3, "old")
        v.write(p, chapter(4, "New title"), 3, "new")
        val files = v.folder(p).listFiles()!!.map { it.name }.filter { it.endsWith(".md") }
        assertEquals(listOf("0004 - New title.md"), files)
        assertEquals("new", v.read(p, chapter(4)))
    }

    @Test
    fun `file names are sortable and safe`() {
        val v = vault()
        val p = project()
        v.write(p, chapter(12, "What? A/B: \"test\" <x>|y"), 11, "x")
        v.write(p, chapter(2, "Early"), 1, "x")
        val names = v.folder(p).listFiles()!!.map { it.name }.filter { it.endsWith(".md") }.sorted()
        assertEquals(listOf("0002 - Early.md", "0012 - What AB test xy.md"), names)
    }

    @Test
    fun `a chapter path without a number falls back to its position`() {
        val c = Chapter("Intro", "/novel/x/prologue", remote = true)
        val p = project(c)
        val v = vault()
        v.write(p, c, 0, "x")
        assertEquals("0001 - Intro.md", v.folder(p).listFiles()!!.first { it.name.endsWith(".md") }.name)
    }

    @Test
    fun `deleting removes the folder and the memory of it`() {
        val c = chapter(1)
        val p = project(c)
        val v = vault()
        v.write(p, c, 0, "x")
        v.deleteAll(p)
        assertFalse(v.folder(p).exists())
        assertEquals(0, v.count(p))
    }

    @Test
    fun `a folder id cannot leave the vault`() {
        val v = vault()
        val evil = project().copy(id = "../../etc")
        assertEquals("etc", v.folder(evil).name)
        assertTrue(v.folder(evil).parentFile!!.name == "vault")
    }

    @Test
    fun `rendered chapters parse back to the same text without the repeated title`() {
        val c = chapter(1, "Chapter 1 - Start")
        val blocks = listOf(
            Block.Paragraph(listOf(InlineSpan("\"Hi,\" she said "), InlineSpan("softly", italic = true), InlineSpan("."))),
            Block.Paragraph(listOf(InlineSpan("1. A line that looks like a list"))),
        )
        val parsed = MarkdownParser.parse(vault().render(c, blocks), markdown = true)
        assertEquals(Block.Heading(1, "Chapter 1 - Start"), parsed.first())
        assertEquals(blocks, parsed.drop(1))
    }

    @Test
    fun `info file records title author and source`() {
        val p = project(chapter(1))
        val v = vault()
        v.writeInfo(p, "Some \"Author\"", "2026-10-31")
        val text = File(v.folder(p), "_novel.md").readText()
        assertTrue(text.contains("title: \"Novel\""))
        assertTrue(text.contains("author: \"Some \\\"Author\\\"\""))
        assertTrue(text.contains("source: \"https://freewebnovel.com/novel/x\""))
        assertTrue(text.contains("updated: 2026-10-31"))
    }

    @Test
    fun `deleting some chapters keeps the others and survives a reopen`() {
        val a = chapter(1)
        val b = chapter(2)
        val c = chapter(3)
        val p = project(a, b, c)
        val v = vault()
        v.write(p, a, 0, "aaaa")
        v.write(p, b, 1, "bb")
        v.write(p, c, 2, "c")
        v.delete(p, listOf(a.uri, b.uri))
        assertEquals(setOf(c.uri), v.downloaded(p))
        assertEquals("c", v.read(p, c))
        assertEquals(setOf(c.uri), vault().downloaded(p))
        val files = v.folder(p).listFiles()!!.map { it.name }.filter { it.endsWith(".md") }
        assertEquals(listOf("0003 - Chapter 3.md"), files)
    }

    @Test
    fun `sizes add up and unsaved paths count as nothing`() {
        val a = chapter(1)
        val b = chapter(2)
        val p = project(a, b)
        val v = vault()
        v.write(p, a, 0, "1234567890")
        v.write(p, b, 1, "12345")
        assertEquals(15L, v.bytesOf(p, listOf(a.uri, b.uri)))
        assertEquals(10L, v.bytesOf(p, listOf(a.uri, "/novel/x/chapter-99")))
        assertEquals(0L, v.bytesOf(p, emptyList()))
    }

    @Test
    fun `deleting nothing changes nothing and a deleted chapter can be saved again`() {
        val a = chapter(1)
        val p = project(a)
        val v = vault()
        v.write(p, a, 0, "x")
        v.delete(p, emptyList())
        assertEquals(1, v.count(p))
        v.delete(p, listOf(a.uri))
        assertEquals(0, v.count(p))
        v.write(p, a, 0, "again")
        assertEquals("again", v.read(p, a))
    }
}
