package com.mdreader.data.comics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An in-process memo for comic calls, the way the novel client keeps one: results live for
 * their TTL so reopening a comic or retyping a search is instant, and single-flight means two
 * concurrent callers of the same key — the detail page and a prefetch, say — share one fetch
 * instead of hitting the site twice. Cache, never data: nothing here is persisted.
 */
internal object ComicMemo {

    /** Enough for a session of browsing; beyond that the oldest half goes. */
    private const val MAX_ENTRIES = 64

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Pair<Any?, Long>>()
    private val flights = HashMap<String, CompletableDeferred<Any?>>()

    private sealed interface Decision {
        data class Hit(val value: Any?) : Decision
        data class Join(val flight: CompletableDeferred<Any?>) : Decision
        data class Produce(val flight: CompletableDeferred<Any?>) : Decision
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> remember(key: String, ttlMs: Long, producer: suspend () -> T): T {
        while (true) {
            val decision = mutex.withLock {
                val entry = entries[key]
                when {
                    entry != null && System.currentTimeMillis() - entry.second < ttlMs ->
                        Decision.Hit(entry.first)

                    flights[key] != null -> Decision.Join(flights.getValue(key))

                    else -> {
                        val flight = CompletableDeferred<Any?>()
                        flights[key] = flight
                        Decision.Produce(flight)
                    }
                }
            }
            when (decision) {
                is Decision.Hit -> return decision.value as T

                is Decision.Join -> return decision.flight.await() as T

                is Decision.Produce -> {
                    val key = key
                    try {
                        val value = producer()
                        mutex.withLock {
                            entries.remove(key)
                            entries[key] = value to System.currentTimeMillis()
                            if (entries.size > MAX_ENTRIES) {
                                repeat(entries.size - MAX_ENTRIES / 2) { entries.remove(entries.keys.first()) }
                            }
                            flights.remove(key)
                        }
                        decision.flight.complete(value)
                        return value
                    } catch (e: Throwable) {
                        mutex.withLock { flights.remove(key) }
                        decision.flight.completeExceptionally(e)
                        throw e
                    }
                }
            }
        }
    }

    /** Drops one key's result, for the places that must see fresh data (a pull-to-check). */
    suspend fun forget(key: String) {
        mutex.withLock {
            entries.remove(key)
        }
    }
}
