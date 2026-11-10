package com.mdreader

import com.mdreader.data.Project
import com.mdreader.data.RewriteSettings
import com.mdreader.data.RewriteStore
import com.mdreader.data.SecretBox
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Reading a chapter must not re-read the bible sheets or reopen the sealed settings. */
class StoreCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private val project = Project("p", "P", "t", emptyList(), 0)

    private object PlainBox : SecretBox {
        override fun seal(plain: String) = plain.reversed()
        override fun open(sealed: String) = sealed.reversed()
    }

    @Test
    fun sheetsAreReadOnceUntilTheyChange() {
        val bible = RewriteStore(tmp.newFolder(), PlainBox).bible(project)
        bible.merge("## people/A\n- tall (Ch 1)")
        bible.index(1)
        bible.index(2)
        bible.roster()
        bible.context("A stands there.")
        assertEquals("one read for many chapter turns", 1, bible.sheetCacheBuilds())

        bible.merge("## people/B\n- short (Ch 2)")
        bible.index(2)
        assertEquals("a merge invalidates the cache", 2, bible.sheetCacheBuilds())
    }

    @Test
    fun clearingTheSheetsInvalidatesTheCache() {
        val bible = RewriteStore(tmp.newFolder(), PlainBox).bible(project)
        bible.merge("## people/A\n- tall (Ch 1)")
        assertEquals(1, bible.index(1).entries.size)
        bible.clear()
        assertEquals(0, bible.index(1).entries.size)
        assertEquals("the clear forces one fresh read", 2, bible.sheetCacheBuilds())
    }

    @Test
    fun settingsAreReadAndUnsealedOnce() {
        val dir = tmp.newFolder()
        RewriteStore(dir, PlainBox).saveSettings(RewriteSettings(apiKey = "k", model = "m"))

        val store = RewriteStore(dir, PlainBox)
        assertEquals("m", store.settings().model)
        assertEquals("k", store.settings().apiKey)
        assertEquals("two calls, one read", 1, store.settingsLoads())

        // A save updates the cache instead of leaving it stale.
        store.saveSettings(store.settings().copy(model = "m2"))
        assertEquals("m2", store.settings().model)
        assertEquals("a save must not force a reload", 1, store.settingsLoads())
    }

    @Test
    fun aNewStoreStillSeesWhatWasSaved() {
        val dir = tmp.newFolder()
        RewriteStore(dir, PlainBox).saveSettings(RewriteSettings(apiKey = "k", model = "m"))
        assertEquals("m", RewriteStore(dir, PlainBox).settings().model)
    }
}
