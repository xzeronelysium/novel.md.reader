package com.mdreader.data.comics

import android.annotation.SuppressLint
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The challenge machinery, as BatCave and MangaKakalot both need it: plain OkHttp requests
 * through the shared cookie jar, and when the site raises its browser check, one offscreen
 * WebView — with the exact user agent the client speaks — clears it once for the whole host.
 * A challenge that stays stuck for ten seconds wants a human tap and is said so, the way
 * Mihon aborts on the same signal.
 */
internal class ChallengeSession(
    /** The site root, whose host the clearance covers. */
    private val origin: String,
    /** The site's own gate cookie, beside Cloudflare's clearance, when it has one. */
    private val extraGuardCookie: String? = null,
    /** Some sites answer a cold jar with a decoy 404; that is treated as a solvable challenge. */
    private val decoyNotFound: Boolean = false,
    /** Loads the homepage once per process, for sites whose first request must not be cold. */
    private val primeHomepage: Boolean = false,
    /** The site's name, for the errors this session raises. */
    private val label: String,
) {
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

    /** One request; on a challenge, solve once for the whole host and retry. */
    private suspend fun request(url: String, body: String?, gate: RateGate): String {
        if (primeHomepage && !primed) {
            primed = true
            solveChallenge("$origin/")
        }
        try {
            return call(url, body, gate)
        } catch (e: ComicChallengeException) {
            return solveAndRetry(url, body, gate, e)
        } catch (e: ComicException) {
            // A cold jar gets a decoy 404; one solve + retry is the same move as a challenge.
            if (decoyNotFound && e.message?.contains("404") == true) return solveAndRetry(url, body, gate, e)
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
        solveChallenge(if (body == null) url else "$origin/")
        return try {
            call(url, body, gate)
        } catch (retry: ComicChallengeException) {
            throw ComicException(
                "$label is showing a browser check this device can't pass. Open " +
                    "${java.net.URI(origin).host} in your browser once, then try again.",
                retry,
            )
        } catch (retry: ComicException) {
            throw ComicException(
                "$label's protection blocked the request. Try again in a moment.",
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

    private fun headers(): Map<String, String> = mapOf("Referer" to "$origin/")

    /**
     * The single-flight solve: one WebView load per host, everyone waits for it. A WebView can
     * only GET, and Cloudflare's clearance covers the whole host, so the site root is loaded even
     * when the challenged request was a POST.
     */
    private suspend fun solveChallenge(target: String) {
        solveMutex.withLock {
            val alreadyFresh = System.currentTimeMillis() - lastSolvedAt < 60_000
            if (alreadyFresh && cookieManager.getCookie(origin).orEmpty().isNotBlank()) return
            withContext(Dispatchers.Main) {
                val view = webView ?: createWebView().also { webView = it }
                val done = CompletableDeferred<Unit>()
                pageLoaded = done
                val startedAt = SystemClock.elapsedRealtime()
                // The Referer rides along, the way the blocked request carried it.
                view.loadUrl(target, mapOf("Referer" to "$origin/"))
                // A managed challenge clears itself in a few seconds. One that still says
                // "Just a moment" after ten wants a human tap — Mihon aborts on that signal
                // instead of waiting it out, and so do we.
                val cleared = withTimeoutOrNull(30_000) {
                    while (!done.isCompleted) {
                        val interactive = SystemClock.elapsedRealtime() - startedAt > 10_000 &&
                            view.title.orEmpty().contains("Just a moment", ignoreCase = true)
                        if (interactive) return@withTimeoutOrNull null
                        delay(300)
                    }
                    Unit
                } != null
                pageLoaded = null
                if (!cleared) {
                    forgetClearance()
                    val interactive = view.title.orEmpty().contains("Just a moment", ignoreCase = true)
                    throw ComicException(
                        if (interactive) {
                            "This check needs a tap in a real browser. Open " +
                                "${java.net.URI(origin).host} in your browser once, then try again."
                        } else {
                            "$label's browser check didn't clear. Try again in a moment."
                        },
                    )
                }
                // The JS challenge sets its guard cookie a moment after the page finishes.
                var waited = 0
                while (waited < 12_000) {
                    val jar = cookieManager.getCookie(origin).orEmpty()
                    if (jar.contains("cf_clearance") || extraGuardCookie?.let { jar.contains(it) } == true) break
                    delay(400)
                    waited += 400
                }
                lastSolvedAt = System.currentTimeMillis()
                cookieManager.flush()
            }
        }
    }

    private fun forgetClearance() {
        cookieManager.setCookie(origin, "cf_clearance=; Max-Age=0; Path=/")
        cookieManager.flush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(AppContextHolder.requireContext)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        // Exactly the string the HTTP client sends: Cloudflare signs cf_clearance against the
        // user agent, so the solver must clear as the same browser the requests claim to be.
        view.settings.userAgentString = ComicHttp.userAgent
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
}
