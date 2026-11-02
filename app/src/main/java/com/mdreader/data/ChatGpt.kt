package com.mdreader.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A Sign in with ChatGPT grant: a token for api.openai.com, and what renews it. */
data class ChatGptCredential(
    val access: String,
    val refresh: String,
    /** When to renew, in epoch milliseconds; a few minutes before the token really expires. */
    val expires: Long,
    /** OpenAI registers a client per sign-in; refreshing needs its id. */
    val clientId: String,
) {
    fun toJson(): String = JSONObject()
        .put("access", access).put("refresh", refresh).put("expires", expires).put("clientId", clientId)
        .toString()

    companion object {
        fun fromJson(text: String): ChatGptCredential? = runCatching {
            val json = JSONObject(text)
            ChatGptCredential(json.getString("access"), json.getString("refresh"), json.getLong("expires"), json.getString("clientId"))
        }.getOrNull()
    }
}

/**
 * Sign in with ChatGPT, the public-client flow OpenAI offers to agents (the same one pi uses):
 * PKCE with a dynamically registered client, a loopback callback on 127.0.0.1:1455, and a token
 * scoped to call the Responses API on api.openai.com directly. No client secret exists anywhere.
 */
object ChatGptAuth {
    private const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
    private const val AGENT_NAME = "novel.md.reader"
    private const val AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
    private const val TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
    const val RESOURCE = "https://api.openai.com/v1"
    const val PORT = 1455
    private const val PATH = "/auth/callback"
    const val REDIRECT_URI = "http://127.0.0.1:$PORT$PATH"
    private const val DIRECT_SCOPE = "chatgpt.tokens.use.direct"
    private const val SCOPE = "openid profile email offline_access resource.invoke $DIRECT_SCOPE"
    // Renew this long before the real expiry, so a request never starts with a dying token.
    private const val EXPIRY_MARGIN_MS = 3 * 60 * 1000L

    private val random = SecureRandom()

    data class Pkce(val verifier: String, val challenge: String)

    data class Callback(val code: String, val clientId: String)

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun randomValue(): String = ByteArray(32).also(random::nextBytes).let(::base64Url)

    internal fun challengeOf(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun pkce(): Pkce = randomValue().let { Pkce(it, challengeOf(it)) }

    /** The page the browser opens. [deviceId] is this installation's stable UUID. */
    fun authorizeUrl(deviceId: String, challenge: String, state: String, nonce: String): String {
        val query = linkedMapOf(
            "client_id" to DYNAMIC_CLIENT_ID,
            "agent_name_hint" to AGENT_NAME,
            "ext_agent_host_id" to "urn:uuid:${deviceId.lowercase()}",
            "response_type" to "code",
            "redirect_uri" to REDIRECT_URI,
            "resource" to RESOURCE,
            "scope" to SCOPE,
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "nonce" to nonce,
        )
        return AUTHORIZE_URL + "?" + query.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
    }

    /**
     * Reads the redirect: a request target (`/auth/callback?code=...`) or a full pasted URL.
     * Throws with a message fit to show when sign-in was refused or the URL is not this login's.
     */
    fun parseCallback(target: String, expectedState: String): Callback {
        val trimmed = target.trim()
        val path = trimmed.substringAfter("127.0.0.1:$PORT", trimmed).substringBefore('?')
        if (path != PATH) throw IOException("That is not the address the sign-in ended on.")
        val query = query(trimmed.substringAfter('?', ""))
        query["error"]?.let { throw IOException("ChatGPT sign-in was not completed ($it).") }
        val code = query["code"]?.takeIf { it.isNotBlank() } ?: throw IOException("The sign-in returned no code.")
        if (query["state"] != expectedState) throw IOException("This sign-in belongs to another attempt. Try again.")
        val clientId = query["client_id"]?.takeIf { it.isNotBlank() } ?: throw IOException("The sign-in returned no client.")
        return Callback(code, clientId)
    }

    suspend fun exchange(callback: Callback, verifier: String): ChatGptCredential = token(
        mapOf(
            "grant_type" to "authorization_code",
            "client_id" to callback.clientId,
            "code" to callback.code,
            "code_verifier" to verifier,
            "redirect_uri" to REDIRECT_URI,
            "resource" to RESOURCE,
        ),
        callback.clientId,
    )

    suspend fun refresh(credential: ChatGptCredential): ChatGptCredential = token(
        mapOf(
            "grant_type" to "refresh_token",
            "client_id" to credential.clientId,
            "refresh_token" to credential.refresh,
            "resource" to RESOURCE,
        ),
        credential.clientId,
    )

    private suspend fun token(form: Map<String, String>, clientId: String): ChatGptCredential = withContext(Dispatchers.IO) {
        val connection = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            connection.outputStream.use { out ->
                out.write(form.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }.toByteArray())
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val reason = runCatching { JSONObject(text).optString("error_description").ifBlank { JSONObject(text).optString("error") } }
                    .getOrNull().orEmpty()
                throw IOException(if (code == 400 || code == 401) "ChatGPT refused the sign-in. Sign in again. $reason".trim() else "ChatGPT sign-in failed ($code).")
            }
            credentialOf(text, clientId)
        } finally {
            connection.disconnect()
        }
    }

    /** A token response, checked: it must carry a refresh token and the direct-use scope. */
    internal fun credentialOf(text: String, clientId: String, now: Long = System.currentTimeMillis()): ChatGptCredential {
        val json = JSONObject(text)
        val access = json.optString("access_token").ifBlank { throw IOException("ChatGPT sent no access token.") }
        val refresh = json.optString("refresh_token").ifBlank { throw IOException("ChatGPT sent no refresh token.") }
        val expiresIn = json.optLong("expires_in", 0)
        if (expiresIn <= 0) throw IOException("ChatGPT sent a token without an expiry.")
        val scopes = json.optString("scope").split(Regex("\\s+"))
        if (DIRECT_SCOPE !in scopes) throw IOException("This ChatGPT account did not grant the access rewrites need.")
        return ChatGptCredential(access, refresh, now + expiresIn * 1000 - EXPIRY_MARGIN_MS, clientId)
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun query(text: String): Map<String, String> =
        text.split('&').filter { it.isNotEmpty() }.associate { pair ->
            URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
}

/**
 * One sign-in attempt: the loopback server the browser returns to, and a way to finish by pasting
 * the final address instead. [await] gives the credential, or throws with a message to show.
 */
class ChatGptSignIn(private val deviceId: String) : Closeable {
    private val pkce = ChatGptAuth.pkce()
    private val state = ChatGptAuth.randomValue()
    private val result = CompletableDeferred<ChatGptAuth.Callback>()
    private val server: ServerSocket = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), ChatGptAuth.PORT))
        }
    } catch (e: IOException) {
        throw IOException("Port ${ChatGptAuth.PORT} on this phone is busy, probably with another sign-in. Try again in a moment.")
    }

    val url: String = ChatGptAuth.authorizeUrl(deviceId, pkce.challenge, state, ChatGptAuth.randomValue())

    /** Finishes with an address pasted from the browser, when its redirect never reached the app. */
    fun submit(pasted: String) {
        runCatching { ChatGptAuth.parseCallback(pasted, state) }.fold(result::complete, result::completeExceptionally)
    }

    suspend fun await(): ChatGptCredential = coroutineScope {
        val job = currentCoroutineContext()[Job]
        job?.invokeOnCompletion { close() }
        val serving = launch(Dispatchers.IO) { serve() }
        try {
            ChatGptAuth.exchange(result.await(), pkce.verifier)
        } finally {
            serving.cancel()
            close()
        }
    }

    private fun serve() {
        while (!result.isCompleted) {
            val socket = try {
                server.accept()
            } catch (e: SocketException) {
                return
            }
            socket.use { s ->
                s.soTimeout = 10_000
                val line = runCatching { s.getInputStream().bufferedReader().readLine() }.getOrNull().orEmpty()
                val target = line.split(' ').getOrNull(1).orEmpty()
                val (status, body) = if (!target.startsWith("/auth/callback")) {
                    404 to page("Not here", "This address only finishes a ChatGPT sign-in.")
                } else {
                    runCatching { ChatGptAuth.parseCallback(target, state) }.fold(
                        onSuccess = {
                            result.complete(it)
                            200 to page("ChatGPT is connected", "You can go back to novel.md.reader.")
                        },
                        onFailure = {
                            if (target.contains("error=")) result.completeExceptionally(it)
                            400 to page("ChatGPT was not connected", it.message.orEmpty())
                        },
                    )
                }
                val bytes = body.toByteArray()
                s.getOutputStream().apply {
                    write(("HTTP/1.1 $status OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }
    }

    /** The page the browser shows at the end, in the app's look, with a way back into the app. */
    private fun page(title: String, text: String) = """
        <!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>$title</title>
        <body style="background:#111110;color:#F0EDE6;font:16px system-ui,sans-serif;padding:48px 28px">
        <div style="color:#E05A44;font-size:11px;letter-spacing:.14em">NOVEL.MD.READER</div>
        <h1 style="font-size:26px;font-weight:600;margin:10px 0">$title</h1>
        <p style="color:#9A958B">$text</p>
        <a href="intent://#Intent;package=com.mdreader;end" style="display:inline-block;margin-top:20px;border:1px solid #F0EDE6;color:#F0EDE6;padding:12px 18px;text-decoration:none;font-size:12px;letter-spacing:.14em">BACK TO THE APP</a>
        </body>
    """.trimIndent()

    override fun close() {
        runCatching { server.close() }
    }
}

/**
 * The live ChatGPT token for requests: renewed shortly before it expires, and the renewed grant
 * written back (refresh tokens rotate, so an old one may not work twice).
 */
object ChatGptTokens {
    /** Writes a renewed grant to disk; set once by [RewriteStore.of]. */
    @Volatile var save: ((ChatGptCredential) -> Unit)? = null
    private var latest: ChatGptCredential? = null

    @Synchronized
    fun forget() {
        latest = null
    }

    suspend fun access(given: ChatGptCredential): String {
        val current = synchronized(this) {
            latest?.takeIf { it.clientId == given.clientId && it.expires >= given.expires } ?: given
        }
        if (current.expires > System.currentTimeMillis()) return current.access.also { synchronized(this) { latest = current } }
        val fresh = ChatGptAuth.refresh(current)
        synchronized(this) { latest = fresh }
        save?.invoke(fresh)
        return fresh.access
    }
}

/** Which of the models OpenAI lists can write a chapter. */
object ChatGptModels {
    /** Everything but the models that don't write text: audio, speech, images, embeddings, moderation. */
    fun isWritingModel(id: String): Boolean =
        !Regex("audio|realtime|tts|transcribe|whisper|image|dall-e|sora|embedding|moderation|search|computer-use|babbage|davinci")
            .containsMatchIn(id)
}
