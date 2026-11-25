package com.mdreader.data.comics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Mihon's chapter cache, reduced to what the reader needs: page lists live for an hour (the site
 * rotates image URLs), images for a day, and the decoded bitmaps in memory are bounded by bytes.
 * Page lists stay out of the library files entirely; they are cache, never data.
 */
internal object ComicCache {
    private const val PAGE_TTL_MS = 60 * 60 * 1000L
    private const val IMAGE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val DISK_MAX_BYTES = 150L * 1024 * 1024

    private val memory = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 6).coerceIn(16L * 1024 * 1024, 96L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

    private fun root(context: Context, kind: String): File =
        File(File(context.cacheDir, "comic_cache"), kind).apply { mkdirs() }

    private fun key(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** One chapter-list fetch at a time: the reader and the prefetch share a flight, not a site hit. */
    private val pageFlights = ConcurrentHashMap<String, CompletableDeferred<List<String>>>()

    /** One image fetch at a time per URL+width; a restarted warm shares the flight. */
    private val bitmapFlights = ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()

    /** Page image URLs for a chapter, from disk when fresh, otherwise from the source. */
    suspend fun pages(context: Context, source: ComicSource, chapterPath: String): List<String> {
        val file = File(root(context, "pages"), key("${source.id}|$chapterPath") + ".txt")
        val cached = withContext(Dispatchers.IO) { readPages(file) }
        if (cached != null) return cached

        val flightKey = "${source.id}|$chapterPath"
        val mine = CompletableDeferred<List<String>>()
        val running = pageFlights.putIfAbsent(flightKey, mine)
        if (running != null) return running.await()

        try {
            // NonCancellable: a cancelled warmer must not strand whoever else joined this flight.
            val pages = withContext(NonCancellable) {
                val fetched = source.pages(chapterPath)
                withContext(Dispatchers.IO) {
                    val temp = File(file.parentFile, file.name + ".tmp")
                    temp.writeText(
                        buildString {
                            appendLine(System.currentTimeMillis() + PAGE_TTL_MS)
                            fetched.forEach { appendLine(it) }
                        },
                    )
                    if (!temp.renameTo(file)) {
                        file.writeText(temp.readText())
                        temp.delete()
                    }
                }
                fetched
            }
            pageFlights.remove(flightKey)
            mine.complete(pages)
            return pages
        } catch (e: Throwable) {
            pageFlights.remove(flightKey)
            mine.completeExceptionally(e)
            throw e
        }
    }

    private fun readPages(file: File): List<String>? {
        if (!file.isFile) return null
        return runCatching {
            val lines = file.readLines()
            val expiry = lines.firstOrNull()?.toLongOrNull() ?: return null
            if (System.currentTimeMillis() > expiry) return null
            lines.drop(1).filter { it.startsWith("https://") }.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /**
     * One page or cover, decoded at no more than [maxWidth] pixels wide. Disk first (a day), then
     * the source; concurrent callers share one flight, a failed decode or download returns null so
     * the reader can show a placeholder.
     */
    suspend fun bitmap(
        context: Context,
        source: ComicSource,
        url: String,
        maxWidth: Int,
    ): ImageBitmap? {
        val cacheKey = "$url@$maxWidth"
        memory.get(cacheKey)?.let { return it }
        val mine = CompletableDeferred<ImageBitmap?>()
        val running = bitmapFlights.putIfAbsent(cacheKey, mine)
        if (running != null) return running.await()

        try {
            // NonCancellable: a fling that restarts the warm window must not strand a visible
            // page waiting on this flight — the download finishes and lands in the caches.
            val bitmap = withContext(NonCancellable + Dispatchers.IO) {
                val file = File(root(context, "images"), key(url))
                val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() < IMAGE_TTL_MS
                // A fetch that cannot happen — a bad URL, a dead host, a decode the device
                // can't do — is a placeholder, not a crash.
                val bytes = when {
                    fresh -> runCatching { file.readBytes() }.getOrNull()
                    else -> runCatching { source.imageBytes(url) }.getOrNull()?.also { downloaded ->
                        runCatching {
                            file.writeBytes(downloaded)
                            prune(file.parentFile!!)
                        }
                    }
                }
                bytes?.let { decode(it, maxWidth) }?.asImageBitmap()?.also { memory.put(cacheKey, it) }
            }
            bitmapFlights.remove(cacheKey)
            mine.complete(bitmap)
            return bitmap
        } catch (e: Throwable) {
            bitmapFlights.remove(cacheKey)
            mine.completeExceptionally(e)
            throw e
        }
    }

    private fun decode(bytes: ByteArray, maxWidth: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun prune(dir: File) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_MAX_BYTES) return
        for (old in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_MAX_BYTES * 3 / 4) break
            total -= old.length()
            old.delete()
        }
    }
}
