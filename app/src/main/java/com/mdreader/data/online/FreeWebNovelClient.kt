package com.mdreader.data.online

import com.mdreader.md.Block
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

data class OnlineNovel(val details: NovelDetails, val chapters: List<ChapterRef>)

/**
 * The single online source. Requests are plain HTTPS with an honest user agent,
 * modest concurrency and a short pause between requests; if the site refuses us
 * (403/429/challenge page) the error is shown rather than worked around.
 */
object FreeWebNovelClient {

    private const val USER_AGENT = "novel.md.reader/1.1 (Android)"
    private const val TIMEOUT_MS = 15_000
    private const val MAX_BODY_CHARS = 4_000_000
    private const val LIST_PACE_MS = 300L
    private val RATE_LIMIT_BACKOFF_MS = listOf(2_000L, 5_000L, 12_000L)

    suspend fun search(query: String, page: Int = 1): List<NovelHit> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(term, "UTF-8")
        return FreeWebNovelParser.parseSearch(get("/search?keyword=$encoded&page=$page"))
    }

    /** Just the novel page (summary, author, status): one request. */
    suspend fun loadNovelPage(path: String): NovelDetails {
        require(FreeWebNovelParser.isNovelPath(path)) { "Not a novel path: $path" }
        return FreeWebNovelParser.parseNovelDetails(get(path))
    }

    /** Novel details plus its complete chapter list (about one request per 200 chapters). */
    suspend fun loadDetails(
        path: String,
        /** Called as soon as the summary is in. */
        onDetails: (NovelDetails) -> Unit = {},
        /** Called after each list page with that page and its 1-based number. */
        onPage: (ChapterPage, Int) -> Unit = { _, _ -> },
    ): OnlineNovel {
        require(FreeWebNovelParser.isNovelPath(path)) { "Not a novel path: $path" }
        val details = FreeWebNovelParser.parseNovelDetails(get(path))
        onDetails(details)
        val first = loadChapterPage(path, 1)
        onPage(first, 1)
        val pages = mutableListOf(first)
        for (page in 2..first.totalPages) {
            delay(LIST_PACE_MS)
            val loaded = loadChapterPage(path, page)
            pages += loaded
            onPage(loaded, page)
        }
        return OnlineNovel(details, FreeWebNovelParser.assembleChapters(pages))
    }

    /** One server page (40 chapters) of the list; the site's page number is verified. */
    suspend fun loadChapterPage(path: String, page: Int): ChapterPage {
        require(FreeWebNovelParser.isNovelPath(path)) { "Not a novel path: $path" }
        val json = get(
            "$path?ajax=chapters&page=$page&pageSize=${FreeWebNovelParser.PAGE_SIZE}",
            ajax = true,
        )
        val parsed = FreeWebNovelParser.parseChapterPage(json)
        // The site answers an out-of-range page with its last page.
        if (parsed.page != page) throw OnlineException("The site returned page ${parsed.page} instead of $page.")
        return parsed
    }

    suspend fun loadChapter(path: String): List<Block> {
        require(FreeWebNovelParser.isChapterPath(path)) { "Not a chapter path: $path" }
        return FreeWebNovelParser.parseChapter(get(path))
    }

    /** Retries a rate-limited request a few times with growing pauses, then gives up visibly. */
    private suspend fun get(path: String, ajax: Boolean = false): String {
        var attempt = 0
        while (true) {
            try {
                return fetch(path, ajax)
            } catch (e: RateLimitedException) {
                val pause = RATE_LIMIT_BACKOFF_MS.getOrNull(attempt++) ?: throw OnlineException(e.message!!)
                delay(pause)
            }
        }
    }

    private class RateLimitedException : Exception("The site is rate limiting requests. Wait a minute and try again.")

    private suspend fun fetch(path: String, ajax: Boolean): String =
        withContext(Dispatchers.IO) {
            val connection = (URL(FreeWebNovelParser.ORIGIN + path).openConnection() as HttpURLConnection)
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept-Language", "en")
                if (ajax) connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")

                val code = connection.responseCode
                if (connection.url.host != URL(FreeWebNovelParser.ORIGIN).host) {
                    throw OnlineException("The site redirected somewhere unexpected.")
                }
                when {
                    code == 429 -> throw RateLimitedException()
                    code == 403 -> throw OnlineException("The site refused the request (403). It may be blocking apps right now.")
                    code == 404 -> throw OnlineException("That page no longer exists on the site.")
                    code !in 200..299 -> throw OnlineException("The site returned an error ($code).")
                }
                connection.inputStream.bufferedReader().use { reader ->
                    val text = reader.readText()
                    if (text.length > MAX_BODY_CHARS) throw OnlineException("The response was unexpectedly large.")
                    text
                }
            } catch (e: OnlineException) {
                throw e
            } catch (e: UnknownHostException) {
                throw OnlineException("No internet connection.", e)
            } catch (e: SocketTimeoutException) {
                throw OnlineException("The site took too long to respond.", e)
            } catch (e: IOException) {
                throw OnlineException("Couldn't reach the site. Check your connection.", e)
            } finally {
                connection.disconnect()
            }
        }
}
