package com.mdreader.data.comics

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder

/**
 * BatCave is a DLE site behind a JavaScript challenge (Cloudflare's "Just a moment" today, the
 * older DLE Guard redirect at other times). Plain HTTP cannot pass either, so a single offscreen
 * WebView clears the challenge once per session, and the clearance cookies are then attached to
 * the normal client. If the challenge comes back, it is solved once more; if that fails, the
 * reader shows a plain "site protection" error instead of looping.
 */
object BatCaveSource : ComicSource {
    override val id = "batcave"
    override val name = "BatCave"
    override val origin = "https://batcave.biz"

    private val gate = RateGate(1_200)
    private val session = BatCaveSession()

    private fun url(path: String) = if (path.startsWith("http")) path else origin + path

    override suspend fun search(query: String, page: Int): ComicSearchPage {
        val path = "/search/" + URLEncoder.encode(query.trim(), "UTF-8") + if (page > 1) "/page/$page" else ""
        val html = session.get(url(path), gate)
        return ComicSearchPage(
            hits = BatCaveParser.parseSearch(html, origin),
            hasNext = BatCaveParser.hasNextPage(html, page),
            page = page,
        )
    }

    override suspend fun details(path: String): ComicDetails =
        BatCaveParser.parseDetails(session.get(url(path), gate), origin)

    override suspend fun chapters(seriesPath: String): List<ComicChapter> {
        val html = session.get(url(seriesPath), gate)
        return BatCaveParser.parseChapters(html)
    }

    override suspend fun pages(chapterPath: String): List<String> {
        val (newsId, chapterId) = BatCaveParser.chapterIds(chapterPath)
            ?: throw ComicException("That chapter link is not a BatCave reader link.")
        val body = JSONObject()
            .put("news_id", newsId)
            .put("chapter_id", chapterId)
            .toString()
        val json = session.post(
            "$origin/engine/ajax/controller.php?mod=api&action=reader/getChapterData",
            body,
            gate,
        )
        return BatCaveParser.parsePageImages(json)
    }

    override fun imageHeaders(url: String): Map<String, String> = mapOf("Referer" to "$origin/")

    override suspend fun imageBytes(url: String): ByteArray? =
        ComicOkHttp.bytes(url, imageHeaders(url))
}

/**
 * One WebView, one clearance. Loads the site, waits out the challenge, then keeps the cookies and
 * hands them to every later request. Created lazily on the main thread.
 */
internal class BatCaveSession {
    private val cookieManager = CookieManager.getInstance()
    private val solveMutex = Mutex()
    private var webView: WebView? = null

    @Volatile
    private var pageLoaded: CompletableDeferred<Unit>? = null

    @Volatile
    private var lastSolvedAt = 0L

    /** One deliberate WebView load per process, so the first request never races a cold jar. */
    @Volatile
    private var primed = false

    suspend fun get(url: String, gate: RateGate): String = request(url, null, gate)

    suspend fun post(url: String, body: String, gate: RateGate): String = request(url, body, gate)

    /** One request; on a Cloudflare challenge, solve once for the whole host and retry. */
    private suspend fun request(url: String, body: String?, gate: RateGate): String {
        // A cold jar gets a decoy 404 from Cloudflare on the first search. Prime it once with a
        // WebView load, the same way the first challenged request would.
        if (!primed) {
            primed = true
            // The homepage is what issues the first Cloudflare session cookies; a cold search
            // path answers with a decoy 404 instead.
            solveChallenge("${BatCaveSource.origin}/")
        }
        try {
            return call(url, body, gate)
        } catch (e: ComicChallengeException) {
            return solveAndRetry(url, body, gate, e)
        } catch (e: ComicException) {
            // A cold jar gets a decoy 404; one solve + retry is the same move as a challenge.
            if (e.message?.contains("404") == true) return solveAndRetry(url, body, gate, e)
            throw e
        }
    }

    private suspend fun solveAndRetry(
        url: String,
        body: String?,
        gate: RateGate,
        cause: Exception,
    ): String {
        forgetClearance()
        solveChallenge(if (body == null) url else "${BatCaveSource.origin}/")
        return try {
            call(url, body, gate)
        } catch (retry: ComicChallengeException) {
            throw ComicException(
                "BatCave is showing a browser check this device can't pass. Open batcave.biz " +
                    "in your browser once, then try again.",
                retry,
            )
        } catch (retry: ComicException) {
            throw ComicException(
                "BatCave's protection blocked the request. Try again in a moment.",
                retry,
            )
        }
    }

    private suspend fun call(url: String, body: String?, gate: RateGate): String {
        gate.pass()
        return if (body == null) {
            ComicOkHttp.get(url, headers())
        } else {
            ComicOkHttp.postJson(url, body, headers())
        }
    }

    private fun headers(): Map<String, String> = mapOf("Referer" to "${BatCaveSource.origin}/")

    /**
     * Mihon's single-flight solve: one WebView load per host, everyone waits for it. A WebView can
     * only GET, and Cloudflare's clearance covers the whole host, so the site root is loaded even
     * when the challenged request was a POST.
     */
    private suspend fun solveChallenge(target: String) {
        solveMutex.withLock {
            val alreadyFresh = System.currentTimeMillis() - lastSolvedAt < 60_000
            if (alreadyFresh && cookieManager.getCookie(BatCaveSource.origin).orEmpty().isNotBlank()) return
            withContext(Dispatchers.Main) {
                val view = webView ?: createWebView().also { webView = it }
                val done = CompletableDeferred<Unit>()
                pageLoaded = done
                // For a GET, load the very URL that was challenged: that is what makes Cloudflare
                // issue cf_clearance for this browser session (Mihon does the same). A WebView can
                // only GET, so POST challenges are solved on the site root instead.
                view.loadUrl(target)
                val cleared = withTimeoutOrNull(30_000) { done.await() } != null
                pageLoaded = null
                if (!cleared) {
                    forgetClearance()
                    throw ComicException(
                        "BatCave's browser check didn't clear. Try again in a moment.",
                    )
                }
                // The JS challenge sets its guard cookie a moment after the page finishes.
                var waited = 0
                while (waited < 12_000) {
                    val jar = cookieManager.getCookie(BatCaveSource.origin).orEmpty()
                    if (jar.contains("__guard_trust") || jar.contains("cf_clearance")) break
                    delay(400)
                    waited += 400
                }
                lastSolvedAt = System.currentTimeMillis()
                cookieManager.flush()
            }
        }
    }

    private fun forgetClearance() {
        cookieManager.setCookie(BatCaveSource.origin, "cf_clearance=; Max-Age=0; Path=/")
        cookieManager.flush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(AppContextHolder.requireContext)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.userAgentString = ComicHttp.BROWSER_UA
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // A challenge page finishes first, then the site loads itself; only the second
                // finish (no "Just a moment" title) means the session is usable.
                if (!view.title.orEmpty().contains("Just a moment", ignoreCase = true)) {
                    pageLoaded?.complete(Unit)
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        return view
    }

    fun cookieHeader(): String? = cookieManager.getCookie(BatCaveSource.origin)
}

/** The application context, set once from MainActivity so a data object can build a WebView. */
object AppContextHolder {
    @Volatile
    var context: Context? = null

    val requireContext: Context
        get() = context ?: throw ComicException("The comic session isn't initialised yet.")
}

/** Pure parsing for BatCave, testable against saved pages and JSON. */
object BatCaveParser {

    fun parseSearch(html: String, origin: String): List<ComicHit> {
        val doc = org.jsoup.Jsoup.parse(html, origin)
        val seen = LinkedHashSet<String>()
        return doc.select("#dle-content .readed, #dle-content article.readed").mapNotNull { item ->
            val link = item.selectFirst(".readed__title a[href], a[href*=/comix/]") ?: return@mapNotNull null
            val href = link.attr("href")
            val path = path(href, origin) ?: return@mapNotNull null
            if (!seen.add(path)) return@mapNotNull null
            val title = link.text().trim().ifEmpty { link.attr("title").trim() }
            if (title.isEmpty()) return@mapNotNull null
            val id = path.substringAfterLast('/').substringBefore('-')
            val cover = item.selectFirst(".readed__img img, img")?.absUrl("src").orEmpty()
                .ifEmpty { "$origin/uploads/posts/hd/$id.webp" }
            ComicHit(title = title, path = path, cover = cover)
        }
    }

    fun hasNextPage(html: String, page: Int): Boolean {
        val doc = org.jsoup.Jsoup.parse(html, "https://batcave.biz")
        return doc.select("a[href*=/search/][href*=/page/]").any {
            it.attr("href").contains("/page/${page + 1}")
        } || doc.select(".pagination a, .navigation a").any {
            it.attr("href").contains("/page/${page + 1}")
        }
    }

    fun parseDetails(html: String, origin: String): ComicDetails {
        val doc = org.jsoup.Jsoup.parse(html, origin)
        val title = doc.selectFirst("header.page__header h1")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isEmpty()) throw ComicException("That page doesn't look like a comic page.")
        val info = doc.select("div.page__info li, ul.page__info li").map { it.text().trim() }
        val status = info.firstOrNull { it.startsWith("Release type", ignoreCase = true) }
            ?.substringAfter(':', "")?.trim().orEmpty()
            .ifEmpty { info.firstOrNull { it.startsWith("Status", ignoreCase = true) }?.substringAfter(':', "")?.trim().orEmpty() }
        val text = doc.selectFirst("div.page__text")?.text()?.trim().orEmpty()
        val extra = info.filterNot { it.startsWith("Release type", true) || it.startsWith("Status", true) }
            .joinToString(" · ")
        return ComicDetails(
            title = title,
            author = "",
            status = status,
            description = text.ifEmpty { extra },
            genres = doc.select("div.page__tags a").map { it.text().trim() }.filter { it.isNotEmpty() },
            cover = doc.selectFirst("div.page__poster img")?.absUrl("src").orEmpty(),
        )
    }

    /** Chapters come from `window.__DATA__`, newest first there; return reading order. */
    fun parseChapters(html: String): List<ComicChapter> {
        val json = dataObject(html) ?: throw ComicException("The chapter list wasn't on that page.")
        val newsId = json.optString("news_id")
        val chapters = json.optJSONArray("chapters") ?: return emptyList()
        val xhash = json.optString("xhash")
        val list = (0 until chapters.length()).mapNotNull { i ->
            val chapter = chapters.optJSONObject(i) ?: return@mapNotNull null
            val id = chapter.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            ComicChapter(
                title = chapter.optString("title").ifEmpty { "Chapter ${chapter.optString("posi")}" },
                path = "/reader/$newsId/$id$xhash",
                date = chapter.optString("date"),
            )
        }
        return list.asReversed()
    }

    /** `{data:{images:[...]}}` from the reader AJAX endpoint. */
    fun parsePageImages(json: String): List<String> {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: throw ComicException("The page list came back in an unexpected format.")
        val data = root.optJSONObject("data") ?: root
        val images = data.optJSONArray("images") ?: throw ComicException("That chapter has no pages.")
        val list = (0 until images.length()).mapNotNull { i ->
            images.optString(i).takeIf { it.startsWith("http") }
        }
        if (list.isEmpty()) throw ComicException("That chapter has no pages.")
        return list
    }

    /** `/reader/123/456abc` → 123, 456abc. */
    fun chapterIds(path: String): Pair<String, String>? {
        val parts = path.trimEnd('/').split('/')
        if (parts.size < 4 || parts[1] != "reader") return null
        val chapter = parts.last()
        val news = parts[parts.size - 2]
        if (news.isEmpty() || chapter.isEmpty()) return null
        return news to chapter
    }

    private fun dataObject(html: String): JSONObject? {
        val start = html.indexOf("window.__DATA__")
        if (start < 0) return null
        val brace = html.indexOf('{', start)
        if (brace < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (i in brace until html.length) {
            val c = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            end = i + 1
                            break
                        }
                    }
                }
            }
        }
        if (end < 0) return null
        return runCatching { JSONObject(html.substring(brace, end)) }.getOrNull()
    }

    private fun path(href: String, origin: String): String? = when {
        href.startsWith("$origin/") -> href.removePrefix(origin)
        href.startsWith("/") && !href.startsWith("//") -> href
        else -> null
    }?.substringBefore('?')?.substringBefore('#')?.trimEnd('/')
}
