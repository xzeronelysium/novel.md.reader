package com.mdreader.data.comics

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * HTTP for the comic sources: HTTPS only, gzip, a size cap counted while reading, and a gate that
 * keeps requests to a site polite. No cookie handling here — BatCave keeps its own session.
 */
/**
 * A live cookie store shared with the WebView. Unlike a snapshot, this is read before every
 * request and written after every response, so the rotating `__cf_bm` cookie stays valid.
 */
internal interface ComicCookies {
    fun header(url: String): String?
    fun store(url: String, setCookies: List<String>)
}

internal object ComicHttp {

    /** A real browser string: Cloudflare binds its clearance cookie to the user agent. */
    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * The device's real WebView UA once MainActivity has had its say. Cloudflare binds
     * cf_clearance to the UA string, and a challenge solved by a WebView claiming to be one
     * Chrome while its engine is another is the challenge that never clears — so the HTTP
     * client and the solver WebView both use exactly this string, and it's the honest one.
     */
    @Volatile
    var userAgent: String = BROWSER_UA

    private const val TIMEOUT_MS = 20_000
    private const val MAX_BODY_BYTES = 6_000_000

    /**
     * A challenge is detected the way Mihon does it: Cloudflare's own `cf-mitigated: challenge`
     * response header. The older DLE Guard markers stay as a fallback for the same site.
     */
    internal fun isChallenge(status: Int, cfMitigated: String?, body: String, finalUrl: String): Boolean {
        if (cfMitigated?.equals("challenge", ignoreCase = true) == true) return true
        if (status == 403 && body.contains("Just a moment", ignoreCase = true)) return true
        if (status == 503 && body.contains("cf-chl", ignoreCase = true)) return true
        if (finalUrl.contains("/_c")) return true
        if (body.contains("__guard_trust", ignoreCase = false)) return true
        return false
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        gate: RateGate? = null,
        cookies: ComicCookies? = null,
    ): String = request("GET", url, null, headers, gate, cookies)

    suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        gate: RateGate? = null,
        cookies: ComicCookies? = null,
    ): String = request("POST", url, body, headers + ("Content-Type" to contentType), gate, cookies)

    suspend fun bytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        gate: RateGate? = null,
        maxBytes: Int = 8_000_000,
        cookies: ComicCookies? = null,
    ): ByteArray? = withContext(Dispatchers.IO) {
        gate?.pass()
        val connection = open(url, headers, cookies)
        try {
            connection.storeCookies(cookies)
            val code = connection.responseCode
            if (code !in 200..299) return@withContext null
            if (connection.contentLengthLong > maxBytes) return@withContext null
            connection.inputStream.use { raw ->
                val gzipped = connection.contentEncoding?.contains("gzip", ignoreCase = true) == true
                readCapped(if (gzipped) GZIPInputStream(raw) else raw, maxBytes)
            }
        } catch (e: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        gate: RateGate?,
        cookies: ComicCookies?,
    ): String = withContext(Dispatchers.IO) {
        gate?.pass()
        val connection = open(url, headers, cookies)
        try {
            if (method == "POST") {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.orEmpty().toByteArray()) }
            }
            val code = connection.responseCode
            connection.storeCookies(cookies)
            val text = connection.inputStreamOrNull()?.use { raw ->
                val gzipped = connection.contentEncoding?.contains("gzip", ignoreCase = true) == true
                String(readCapped(if (gzipped) GZIPInputStream(raw) else raw, MAX_BODY_BYTES), Charsets.UTF_8)
            }.orEmpty()
            if (isChallenge(code, connection.getHeaderField("cf-mitigated"), text, connection.url.toString())) {
                throw ComicChallengeException("The site wants JavaScript before it shows content.")
            }
            if (code !in 200..299) {
                throw ComicException(
                    when (code) {
                        403 -> "The site refused the request (403)."
                        404 -> "That page no longer exists on the site."
                        429 -> "The site is rate limiting. Wait a minute and try again."
                        else -> "The site returned an error ($code)."
                    },
                )
            }
            text
        } catch (e: ComicException) {
            throw e
        } catch (e: ComicChallengeException) {
            throw e
        } catch (e: IOException) {
            throw ComicException("Couldn't reach the site. Check your connection.", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(
        url: String,
        headers: Map<String, String>,
        cookies: ComicCookies?,
    ): HttpURLConnection {
        val parsed = URL(url)
        if (parsed.protocol != "https") throw ComicException("Only https is allowed.")
        val connection = parsed.openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        )
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        connection.setRequestProperty("Accept-Encoding", "gzip")
        cookies?.header(url)?.let { connection.setRequestProperty("Cookie", it) }
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return connection
    }

    /** Writes every Set-Cookie from the response back into the shared jar, like a browser would. */
    private fun HttpURLConnection.storeCookies(cookies: ComicCookies?) {
        if (cookies == null) return
        val setCookies = headerFields.entries
            .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
            .flatMap { it.value.orEmpty() }
        if (setCookies.isNotEmpty()) cookies.store(url.toString(), setCookies)
    }

    private fun HttpURLConnection.inputStreamOrNull(): InputStream? = try {
        inputStream
    } catch (e: IOException) {
        errorStream
    }

    private fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > maxBytes) throw ComicException("The response was unexpectedly large.")
        }
        return out.toByteArray()
    }
}

/**
 * Paces requests to one site. Weeb Central's extension uses one request per two seconds; the same
 * gate serialises them here so two concurrent chapter prefetches cannot hit it together.
 */
internal class RateGate(private val intervalMs: Long) {
    private val mutex = Mutex()
    private var lastAt = 0L

    suspend fun pass() {
        mutex.withLock {
            val now = System.nanoTime() / 1_000_000
            val wait = intervalMs - (now - lastAt)
            if (lastAt != 0L && wait > 0) delay(wait)
            lastAt = System.nanoTime() / 1_000_000
        }
    }
}
