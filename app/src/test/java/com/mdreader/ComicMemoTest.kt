package com.mdreader

import com.mdreader.data.comics.ComicMemo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The comic memo: results live for their TTL, and concurrent callers of one key share a single
 * flight. Pure in-process behaviour, no network.
 */
class ComicMemoTest {

    @Test
    fun `same key twice within the ttl fetches once`() = runBlocking {
        var fetches = 0
        val first = ComicMemo.remember("k1", 60_000) { fetch { fetches++ } }
        val second = ComicMemo.remember("k1", 60_000) { fetch { fetches++ } }

        assertEquals(first, second)
        assertEquals(1, fetches)
    }

    @Test
    fun `concurrent callers join one flight`() = runBlocking {
        var fetches = 0
        val results = coroutineScope {
            val calls = (1..5).map { _ ->
                async { ComicMemo.remember("k2", 60_000) { fetch { fetches++ } } }
            }
            calls.map { it.await() }
        }

        assertEquals(1, fetches)
        assertEquals(1, results.distinct().size)
    }

    @Test
    fun `forget drops the result so the next call refetches`() = runBlocking {
        var fetches = 0
        ComicMemo.remember("k3", 60_000) { fetch { fetches++ } }
        ComicMemo.forget("k3")
        ComicMemo.remember("k3", 60_000) { fetch { fetches++ } }

        assertEquals(2, fetches)
    }

    @Test
    fun `different keys never share a result`() = runBlocking {
        val a = ComicMemo.remember("k4", 60_000) { "a" }
        val b = ComicMemo.remember("k5", 60_000) { "b" }

        assertEquals("a", a)
        assertEquals("b", b)
    }

    /** A fetch slow enough for all five concurrent callers to reach the same flight. */
    private suspend fun fetch(record: () -> Unit): String {
        record()
        delay(50)
        return "value"
    }
}
