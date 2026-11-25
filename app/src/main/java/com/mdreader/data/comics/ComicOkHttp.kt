package com.mdreader.data.comics

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The comic HTTP stack that Cloudflare accepts: OkHttp with HTTP/2 and a browser-like request
 * shape, sharing the system cookie store with the WebView (read and write on every exchange).
 * This is the same shape Mihon uses; the novel source keeps its lighter HttpURLConnection client.
 */
internal object ComicOkHttp {

    /** Generous caps for an image or a chapter page; both are far below this in practice. */
    private const val MAX_BYTES = 12_000_000

    private val cookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val header = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
            return header.split(';').mapNotNull { part ->
                val name = part.substringBefore('=', "").trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isEmpty()) return@mapNotNull null
                runCatching {
                    Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain(url.host)
                        .path("/")
                        .build()
                }.getOrNull()
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val manager = CookieManager.getInstance()
            cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
            manager.flush()
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        // Images load as a burst: eight lanes per host, not OkHttp's default five.
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 8
            },
        )
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        execute("GET", url, headers, null, "text/html")

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        execute("POST", url, headers, body, "application/json")

    /**
     * One image, inline or remote. BatCave serves some covers as `data:image/...;base64,...`
     * URLs; those decode in place, and anything that is not https answers null instead of
     * throwing — a bad cover URL must never take the reader down with it.
     */
    suspend fun bytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? =
        withContext(Dispatchers.IO) {
            dataUriBytes(url)?.let { return@withContext it }
            if (!url.startsWith("https://")) return@withContext null
            val request = baseRequest(url, headers)
                .header("Sec-Fetch-Dest", "image")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", "same-origin")
                .get()
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    if (body.contentLength() > MAX_BYTES) return@use null
                    body.bytes()
                }
            }.getOrNull()
        }

    /** Decodes an inline base64 image URI, or null when it is anything else. */
    internal fun dataUriBytes(url: String): ByteArray? {
        if (!url.startsWith("data:")) return null
        return runCatching {
            val comma = url.indexOf(',')
            if (comma < 0) return@runCatching null
            val meta = url.substring(5, comma)
            val payload = url.substring(comma + 1)
            val mime = meta.substringBefore(';').lowercase()
            val base64 = meta.substringAfter(';', "").contains("base64", ignoreCase = true)
            if (mime.startsWith("image/") && base64) java.util.Base64.getDecoder().decode(payload) else null
        }.getOrNull()
    }

    private suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        mediaType: String,
    ): String = withContext(Dispatchers.IO) {
        val builder = baseRequest(url, headers)
        val request = if (method == "POST") {
            builder
                .header("Origin", ComicSources.byId("batcave")?.origin.orEmpty())
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .post(body.orEmpty().toRequestBody(mediaType.toMediaType()))
                .build()
        } else {
            builder
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Upgrade-Insecure-Requests", "1")
                .get()
                .build()
        }
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.header("cf-mitigated")?.equals("challenge", true) == true) {
                    throw ComicChallengeException("The site wants JavaScript before it shows content.")
                }
                if (!response.isSuccessful) {
                    throw ComicException(
                        when (response.code) {
                            403 -> "The site refused the request (403)."
                            404 -> "That page no longer exists on the site."
                            429 -> "The site is rate limiting. Wait a minute and try again."
                            else -> "The site returned an error (${response.code})."
                        },
                    )
                }
                if (text.length > MAX_BYTES) throw ComicException("The response was unexpectedly large.")
                text
            }
        } catch (e: ComicException) {
            throw e
        } catch (e: ComicChallengeException) {
            throw e
        } catch (e: IOException) {
            throw ComicException("Couldn't reach the site. Check your connection.", e)
        }
    }

    private fun baseRequest(url: String, headers: Map<String, String>): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", ComicHttp.BROWSER_UA)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            )
            .header("Accept-Language", "en-US,en;q=0.9")
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder
    }
}
