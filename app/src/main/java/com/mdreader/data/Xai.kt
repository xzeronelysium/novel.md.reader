package com.mdreader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/** A Sign in with Grok grant: a token for xAI's CLI chat proxy, and what renews it. */
data class XaiCredential(
    val access: String,
    val refresh: String,
    /** When to renew, in epoch milliseconds; a few minutes before the token really expires. */
    val expires: Long,
) {
    fun toJson(): String = JSONObject().put("access", access).put("refresh", refresh).put("expires", expires).toString()

    companion object {
        fun fromJson(text: String): XaiCredential? = runCatching {
            val json = JSONObject(text)
            XaiCredential(json.getString("access"), json.getString("refresh"), json.getLong("expires"))
        }.getOrNull()
    }
}

/** What the sign-in shows the person: a code, and the page to type it on. */
data class XaiDeviceCode(
    internal val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val intervalSeconds: Int,
    val expiresInSeconds: Long,
) {
    /** The address to open, with the code already filled in when the service gives one. */
    val openUrl: String
        get() = verificationUriComplete
            ?: (verificationUri + (if ('?' in verificationUri) "&" else "?") + "user_code=" + URLEncoder.encode(userCode, "UTF-8"))
}

/**
 * Sign in with Grok: the device-code flow xAI's own Grok CLI (`grok login --device-auth`) uses, so a
 * SuperGrok subscription pays for rewrites. The phone shows a code, the person approves it on x.ai
 * in any browser, and the app polls until it is approved. No loopback port, no client secret.
 *
 * Unofficial: it signs in as the Grok CLI's public client and talks to the CLI's chat proxy, which
 * xAI does not document as a public API. Every constant is here so a change is one edit.
 */
object XaiAuth {
    const val ISSUER = "https://auth.x.ai"
    /** The Grok CLI's public OAuth client. */
    private const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    private const val SCOPE = "openid profile email offline_access grok-cli:access api:access"
    private const val GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    /** The chat proxy a subscription's requests go through (OpenAI's Responses API shape). */
    const val PROXY = "https://cli-chat-proxy.grok.com/v1"
    /** The Grok CLI's version: the proxy refuses clients that are too old. */
    const val CLIENT_VERSION = "1.0.70"
    // Renew this long before the real expiry, so a request never starts with a dying token.
    private const val EXPIRY_MARGIN_MS = 5 * 60 * 1000L
    private const val MIN_WAIT_SECONDS = 600L

    /** The headers the CLI chat proxy asks of every request, as the CLI sends them. */
    fun proxyHeaders(token: String, model: String? = null, conversation: String? = null): Map<String, String> = buildMap {
        put("Authorization", "Bearer $token")
        put("X-XAI-Token-Auth", "xai-grok-cli")
        put("x-authenticateresponse", "authenticate-response")
        put("x-grok-client-identifier", "grok-shell")
        put("x-grok-client-mode", "headless")
        put("x-grok-client-version", CLIENT_VERSION)
        put("User-Agent", "grok-shell/$CLIENT_VERSION (android; ${System.getProperty("os.arch") ?: "arm64"})")
        model?.let { put("x-grok-model-override", it) }
        conversation?.let { put("x-grok-conv-id", it) }
    }

    /** Step 1: asks for a code to show. */
    suspend fun requestDeviceCode(): XaiDeviceCode = withContext(Dispatchers.IO) {
        val (code, text) = post("$ISSUER/oauth2/device/code", mapOf("client_id" to CLIENT_ID, "scope" to SCOPE, "referrer" to "grok-build"))
        if (code == 404) throw IOException("Grok sign-in with a code is not available right now.")
        if (code !in 200..299) throw IOException("Grok did not start the sign-in ($code).")
        deviceCodeOf(text)
    }

    /** Reads the answer to the code request; refuses a code or address that is not safe to show or open. */
    internal fun deviceCodeOf(text: String): XaiDeviceCode {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: throw IOException("Grok sent an unreadable sign-in answer.")
        val userCode = json.optString("user_code")
        if (userCode.isBlank() || !userCode.all { it.isLetterOrDigit() || it == '-' }) throw IOException("Grok sent a sign-in code that cannot be shown.")
        val uri = json.optString("verification_uri").also(::requireSafeAddress)
        val complete = json.optString("verification_uri_complete").takeIf { it.isNotBlank() }?.also(::requireSafeAddress)
        val device = json.optString("device_code").ifBlank { throw IOException("Grok sent no device code.") }
        return XaiDeviceCode(
            device, userCode, uri, complete,
            intervalSeconds = json.optInt("interval", 5).coerceIn(1, 60),
            expiresInSeconds = json.optLong("expires_in", MIN_WAIT_SECONDS).coerceAtLeast(MIN_WAIT_SECONDS),
        )
    }

    /** Only https pages on x.ai or grok.com are ever opened for a sign-in. */
    internal fun requireSafeAddress(address: String) {
        val host = runCatching { URI(address) }.getOrNull()?.takeIf { it.scheme == "https" }?.host?.lowercase()
        val ok = host != null && address.none { it.isISOControl() } &&
            (host == "x.ai" || host.endsWith(".x.ai") || host == "grok.com" || host.endsWith(".grok.com"))
        if (!ok) throw IOException("Grok sent a sign-in address that is not safe to open.")
    }

    /** What one poll of the token endpoint means. */
    internal sealed class Poll {
        object Waiting : Poll()
        object SlowDown : Poll()
        data class Done(val credential: XaiCredential) : Poll()
        data class Failed(val message: String) : Poll()
    }

    internal fun pollResult(status: Int, text: String, now: Long = System.currentTimeMillis()): Poll {
        if (status in 200..299) return runCatching { Poll.Done(credentialOf(text, now)) }.getOrElse { Poll.Failed(it.message.orEmpty()) }
        val error = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
        return when (error) {
            "authorization_pending" -> Poll.Waiting
            "slow_down" -> Poll.SlowDown
            "access_denied" -> Poll.Failed("The sign-in was refused on x.ai.")
            "expired_token" -> Poll.Failed("The sign-in code ran out. Start again.")
            else -> Poll.Failed("Grok sign-in failed ($status).")
        }
    }

    /** Step 2: waits for the code to be approved, then gives the grant. Cancel the coroutine to stop. */
    suspend fun await(code: XaiDeviceCode): XaiCredential {
        var wait = code.intervalSeconds * 1000L
        val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000
        while (true) {
            // Asking at once only gets "pending" (and risks "slow down").
            delay(wait)
            currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() > deadline) throw IOException("The sign-in code ran out. Start again.")
            val (status, text) = withContext(Dispatchers.IO) {
                post("$ISSUER/oauth2/token", mapOf("grant_type" to GRANT, "device_code" to code.deviceCode, "client_id" to CLIENT_ID))
            }
            when (val poll = pollResult(status, text)) {
                Poll.Waiting -> Unit
                Poll.SlowDown -> wait += 5000
                is Poll.Done -> return poll.credential
                is Poll.Failed -> throw IOException(poll.message)
            }
        }
    }

    suspend fun refresh(credential: XaiCredential): XaiCredential = withContext(Dispatchers.IO) {
        val (status, text) = post(
            "$ISSUER/oauth2/token",
            mapOf("grant_type" to "refresh_token", "refresh_token" to credential.refresh, "client_id" to CLIENT_ID),
        )
        if (status == 400 || status == 401) throw IOException("Grok no longer accepts this sign-in. Sign in again in the settings.")
        if (status !in 200..299) throw IOException("Grok could not renew the sign-in ($status).")
        credentialOf(text, previousRefresh = credential.refresh)
    }

    /** A token answer, checked. A renewal may leave the refresh token as it was. */
    internal fun credentialOf(text: String, now: Long = System.currentTimeMillis(), previousRefresh: String? = null): XaiCredential {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: throw IOException("Grok sent an unreadable token.")
        val access = json.optString("access_token").ifBlank { throw IOException("Grok sent no access token.") }
        val refresh = json.optString("refresh_token").ifBlank { previousRefresh.orEmpty() }
            .ifBlank { throw IOException("Grok sent no refresh token.") }
        val expiresIn = json.optLong("expires_in", 0).takeIf { it > 0 } ?: throw IOException("Grok sent a token without an expiry.")
        return XaiCredential(access, refresh, now + expiresIn * 1000 - EXPIRY_MARGIN_MS)
    }

    private fun post(url: String, form: Map<String, String>): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("x-grok-client-version", CLIENT_VERSION)
            setRequestProperty("x-grok-client-surface", "ui")
            setRequestProperty("User-Agent", "grok-shell/$CLIENT_VERSION (android)")
        }
        try {
            connection.outputStream.use { out ->
                out.write(form.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }.toByteArray())
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * The live Grok token for requests: renewed shortly before it expires, and the renewed grant
 * written back (refresh tokens may rotate, so an old one may not work twice).
 */
object XaiTokens {
    /** Writes a renewed grant to disk; set once by [RewriteStore.of]. */
    @Volatile var save: ((XaiCredential) -> Unit)? = null
    private var latest: XaiCredential? = null

    @Synchronized
    fun forget() {
        latest = null
    }

    suspend fun access(given: XaiCredential): String {
        val current = synchronized(this) { latest?.takeIf { it.expires >= given.expires } ?: given }
        if (current.expires > System.currentTimeMillis()) return current.access.also { synchronized(this) { latest = current } }
        val fresh = XaiAuth.refresh(current)
        synchronized(this) { latest = fresh }
        save?.invoke(fresh)
        return fresh.access
    }
}

/** How to read the proxy's model list. */
object XaiModels {
    /** The effort levels a model that "supports reasoning effort" takes when the list names none. */
    private val DEFAULT_LEVELS = listOf("low", "medium", "high")

    /**
     * Models as (id, effort levels). Reads `{"data": [...]}` or `{"models": [...]}`; each entry may
     * be a bare id or an object with `id` (or `slug`, `name`) and a `supports_reasoning_effort` that
     * is a list of levels, a list of `{"effort"}`, or a flag. Models for images, video, voice and
     * embeddings are left out. `null` levels mean the list did not say.
     */
    fun parse(text: String): List<Pair<String, List<String>?>> {
        val root = runCatching { JSONObject(text) }.getOrNull()
        val array = root?.optJSONArray("data") ?: root?.optJSONArray("models")
            ?: runCatching { org.json.JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val entry = array.opt(i)
            val obj = entry as? JSONObject
            val id = (obj?.optString("id")?.ifBlank { null } ?: obj?.optString("slug")?.ifBlank { null }
                ?: obj?.optString("name")?.ifBlank { null } ?: (entry as? String))?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!isWritingModel(id)) return@mapNotNull null
            if (obj != null && obj.optString("visibility") == "hide") return@mapNotNull null
            id to levelsOf(obj)
        }.distinctBy { it.first }
    }

    private fun levelsOf(model: JSONObject?): List<String>? {
        val raw = model?.opt("supports_reasoning_effort") ?: model?.opt("reasoning_efforts") ?: return null
        return when (raw) {
            is Boolean -> if (raw) DEFAULT_LEVELS else emptyList()
            is org.json.JSONArray -> (0 until raw.length()).mapNotNull { i ->
                (raw.optJSONObject(i)?.optString("effort") ?: raw.optString(i)).takeIf { !it.isNullOrBlank() && it != "null" }
            }
            else -> null
        }
    }

    /** Everything but images, video, voice, embeddings and moderation. */
    fun isWritingModel(id: String): Boolean =
        !Regex("image|imagine|video|voice|tts|stt|transcri|embedding|moderation|vision-only", RegexOption.IGNORE_CASE).containsMatchIn(id)
}
