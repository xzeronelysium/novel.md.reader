package com.mdreader

import com.mdreader.ui.OnlineOpenState
import com.mdreader.ui.OnlineSearchPhase
import com.mdreader.ui.OnlineSearchState
import com.mdreader.ui.onlineOpenErrorMessage
import com.mdreader.ui.onlineSearchErrorMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class OnlineSearchStateTest {

    @Test
    fun `a fresh state is idle and holds no results`() {
        val state = OnlineSearchState<String>()

        assertEquals(OnlineSearchPhase.Idle, state.phase)
        assertEquals("", state.query)
        assertEquals(emptyList<String>(), state.items)
        assertFalse(state.isSearching)
    }

    @Test
    fun `a blank query never starts a request`() {
        val state = OnlineSearchState<String>()

        assertNull(state.startSearch("   "))
        assertNull(state.startSearch(""))
        assertEquals(OnlineSearchPhase.Idle, state.phase)
    }

    @Test
    fun `starting a search trims the query and moves to searching`() {
        val state = OnlineSearchState<String>().startSearch("  cultivation  ")!!

        assertEquals("cultivation", state.query)
        assertEquals(OnlineSearchPhase.Searching("cultivation"), state.phase)
        assertTrue(state.isSearching)
        assertEquals(1L, state.requestId)
    }

    @Test
    fun `each new search gets a fresh request id`() {
        val first = OnlineSearchState<String>().startSearch("one")!!
        val second = first.startSearch("two")!!

        assertEquals(2L, second.requestId)
        assertEquals("two", second.query)
    }

    @Test
    fun `results of the current request are kept`() {
        val state = OnlineSearchState<String>().startSearch("cultivation")!!

        val loaded = state.onResults(state.requestId, listOf("a", "b"))

        assertEquals(OnlineSearchPhase.Loaded("cultivation", listOf("a", "b")), loaded.phase)
        assertEquals(listOf("a", "b"), loaded.items)
        assertFalse(loaded.isSearching)
    }

    @Test
    fun `an empty result set is a successful search with no novels`() {
        val state = OnlineSearchState<String>().startSearch("cultivation")!!

        val loaded = state.onResults(state.requestId, emptyList())

        assertEquals(OnlineSearchPhase.Loaded("cultivation", emptyList<String>()), loaded.phase)
        assertTrue(loaded.items.isEmpty())
    }

    @Test
    fun `a stale result is discarded`() {
        val first = OnlineSearchState<String>().startSearch("one")!!
        val second = first.startSearch("two")!!

        val stale = second.onResults(first.requestId, listOf("stale"))

        assertSame(second, stale)
        assertEquals(OnlineSearchPhase.Searching("two"), stale.phase)
        assertTrue(stale.items.isEmpty())
    }

    @Test
    fun `a stale failure is discarded`() {
        val first = OnlineSearchState<String>().startSearch("one")!!
        val second = first.startSearch("two")!!

        val stale = second.onFailure(first.requestId, "offline")

        assertSame(second, stale)
        assertEquals(OnlineSearchPhase.Searching("two"), stale.phase)
    }

    @Test
    fun `the current failure is reported with its message`() {
        val state = OnlineSearchState<String>().startSearch("one")!!

        val failed = state.onFailure(state.requestId, "offline")

        assertEquals(OnlineSearchPhase.Failed("one", "offline"), failed.phase)
        assertEquals("offline", failed.errorMessage)
    }

    @Test
    fun `a new search clears the previous failure`() {
        val failed = OnlineSearchState<String>()
            .startSearch("one")!!
            .onFailure(1L, "offline")

        val retried = failed.startSearch("two")!!

        assertEquals(OnlineSearchPhase.Searching("two"), retried.phase)
        assertNull(retried.errorMessage)
    }

    @Test
    fun `browse may start without a query`() {
        val state = OnlineSearchState<String>().startBrowse()

        assertEquals(OnlineSearchPhase.Searching(""), state.phase)
        assertTrue(state.isSearching)
        assertEquals(1L, state.requestId)
    }

    @Test
    fun `opening a novel marks it as opening`() {
        val state = OnlineOpenState<String>()

        val opening = state.begin("cultivation")

        assertEquals("cultivation", opening.opening)
        assertTrue(opening.isOpening)
        assertNull(opening.error)
    }

    @Test
    fun `a failed open keeps the novel so it can be retried`() {
        val state = OnlineOpenState<String>().begin("cultivation")

        val failed = state.failed("cultivation", "no chapters")

        assertFalse(failed.isOpening)
        assertEquals("no chapters", failed.error)
        assertEquals("cultivation", failed.failedItem)
    }

    @Test
    fun `a successful open clears the error`() {
        val state = OnlineOpenState<String>()
            .begin("cultivation")
            .failed("cultivation", "no chapters")

        val done = state.succeeded()

        assertFalse(done.isOpening)
        assertNull(done.error)
        assertNull(done.failedItem)
    }

    @Test
    fun `a new open clears an earlier error`() {
        val state = OnlineOpenState<String>()
            .begin("one")
            .failed("one", "no chapters")

        val next = state.begin("two")

        assertNull(next.error)
        assertNull(next.failedItem)
        assertEquals("two", next.opening)
    }

    @Test
    fun `an unknown host reads as a connection problem`() {
        val message = onlineSearchErrorMessage(UnknownHostException("freewebnovel.com"))

        assertTrue(message.contains("connection", ignoreCase = true))
    }

    @Test
    fun `a timeout reads as a slow site`() {
        val message = onlineSearchErrorMessage(SocketTimeoutException("timeout"))

        assertTrue(message.contains("took too long", ignoreCase = true))
    }

    @Test
    fun `a plain io failure reads as an unreachable site`() {
        val message = onlineSearchErrorMessage(IOException("broken pipe"))

        assertTrue(message.contains("couldn't reach", ignoreCase = true))
    }

    @Test
    fun `an unknown failure falls back to its own message`() {
        val message = onlineSearchErrorMessage(IllegalStateException("rate limited"))

        assertEquals("rate limited", message)
    }

    @Test
    fun `an unknown failure without a message still reads as a failure`() {
        val message = onlineSearchErrorMessage(IllegalStateException())

        assertTrue(message.isNotBlank())
    }

    @Test
    fun `an open failure names the novel that could not be added`() {
        val message = onlineOpenErrorMessage(IOException("broken pipe"), "Cultivation Online")

        assertTrue(message.contains("Cultivation Online"))
    }
}
