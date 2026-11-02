package com.mdreader.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where a rewrite runs: Ollama Cloud (an OpenAI-compatible `/chat/completions` endpoint with an API
 * key) or ChatGPT (Sign in with ChatGPT, through OpenAI's Responses API).
 */
data class RewriteSettings(
    /** "ollama" or "chatgpt". */
    val provider: String = OLLAMA,
    val baseUrl: String = "https://ollama.com/v1",
    val apiKey: String = "",
    val model: String = "",
    /** One of the levels the model reports (low, high, max...), or empty for its default. */
    val effort: String = "",
    /** light / full / max: how far the rewrite may go. */
    val strength: String = "full",
    /** How many earlier chapters the memory reads before a rewrite; 0 reads from chapter 1. */
    val memoryDepth: Int = 20,
    /** The ChatGPT sign-in, when there is one. */
    val chatgpt: ChatGptCredential? = null,
    /** This installation's stable id, which ChatGPT sign-in asks for. */
    val deviceId: String = "",
) {
    val isChatGpt: Boolean get() = provider == CHATGPT

    val signedIn: Boolean get() = if (isChatGpt) chatgpt != null else baseUrl.isNotBlank() && apiKey.isNotBlank()

    val ready: Boolean get() = signedIn && model.isNotBlank()

    companion object {
        const val OLLAMA = "ollama"
        const val CHATGPT = "chatgpt"
    }
}

/** Seals the API key before it touches disk. */
interface SecretBox {
    fun seal(plain: String): String
    fun open(sealed: String): String?
}

/** AES-GCM under a non-exportable Android Keystore key: the key file alone reveals nothing. */
object KeystoreSecretBox : SecretBox {
    private const val ALIAS = "mdreader-rewrite"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    override fun open(sealed: String): String? = runCatching {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()
}

/** A finished rewrite: the chapter in Markdown, and the continuity note that goes with it. */
data class Rewrite(val chapter: String, val note: String, val sheets: String = "")

/**
 * Rewrites live in app-private storage, beside (never over) the downloaded originals:
 *
 * ```
 * rewrites/<project id>/<hash of chapter path>.md        the revised chapter
 * rewrites/<project id>/<hash of chapter path>.note.md   the continuity note
 * rewrites/<project id>/bible/                            the novel's memory (see [BibleStore])
 * rewrite-settings.json                                  endpoint, sealed key, model, effort, strength
 * ```
 */
class RewriteStore(private val filesDir: File, private val box: SecretBox = KeystoreSecretBox) {

    private val settingsFile = File(filesDir, "rewrite-settings.json")

    @Synchronized
    fun settings(): RewriteSettings = runCatching {
        val json = JSONObject(settingsFile.readText())
        RewriteSettings(
            provider = json.optString("provider", RewriteSettings.OLLAMA),
            baseUrl = json.optString("baseUrl", RewriteSettings().baseUrl),
            // `apiKey` is the plaintext of an earlier version; it is re-sealed on the next save.
            apiKey = json.optString("apiKeyEnc").takeIf { it.isNotEmpty() }?.let(box::open)
                ?: json.optString("apiKey"),
            model = json.optString("model"),
            effort = json.optString("effort"),
            strength = json.optString("strength", "full"),
            memoryDepth = json.optInt("memoryDepth", 20).coerceAtLeast(0),
            chatgpt = json.optString("chatgptEnc").takeIf { it.isNotEmpty() }?.let(box::open)?.let(ChatGptCredential::fromJson),
            deviceId = json.optString("deviceId"),
        )
    }.getOrDefault(RewriteSettings()).let { loaded ->
        // ChatGPT sign-in identifies the installation by a UUID that never changes.
        if (loaded.deviceId.isNotBlank()) loaded else loaded.copy(deviceId = java.util.UUID.randomUUID().toString()).also(::saveSettings)
    }

    @Synchronized
    fun saveSettings(settings: RewriteSettings) {
        // A screen holding an older copy of the ChatGPT grant must not undo a renewal made since:
        // the refresh token rotates, so the older one may no longer work.
        val stored = runCatching {
            JSONObject(settingsFile.readText()).optString("chatgptEnc").takeIf { it.isNotEmpty() }
                ?.let(box::open)?.let(ChatGptCredential::fromJson)
        }.getOrNull()
        val grant = settings.chatgpt?.let { given ->
            if (stored != null && stored.clientId == given.clientId && stored.expires > given.expires) stored else given
        }
        val json = JSONObject()
            .put("provider", settings.provider)
            .put("deviceId", settings.deviceId)
            .apply { grant?.let { put("chatgptEnc", box.seal(it.toJson())) } }
            .put("baseUrl", settings.baseUrl.trim())
            .put("apiKeyEnc", box.seal(settings.apiKey.trim()))
            .put("model", settings.model.trim())
            .put("effort", settings.effort.trim())
            .put("strength", settings.strength)
            .put("memoryDepth", settings.memoryDepth)
        val temp = File(filesDir, "${settingsFile.name}.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(settingsFile)) {
            settingsFile.writeText(json.toString())
            temp.delete()
        }
    }

    @Synchronized
    fun read(project: Project, chapter: Chapter): Rewrite? {
        val file = chapterFile(project, chapter)
        if (!file.isFile) return null
        val note = noteFile(project, chapter).takeIf { it.isFile }?.readText().orEmpty()
        return Rewrite(file.readText(), note)
    }

    @Synchronized
    fun write(project: Project, chapter: Chapter, rewrite: Rewrite) {
        val file = chapterFile(project, chapter)
        file.parentFile?.mkdirs()
        // Note first: a chapter file without its note is still valid, the reverse never is.
        atomicWrite(noteFile(project, chapter), rewrite.note)
        atomicWrite(file, rewrite.chapter)
    }

    @Synchronized
    fun delete(project: Project, chapter: Chapter) {
        chapterFile(project, chapter).delete()
        noteFile(project, chapter).delete()
    }

    /** Removes every rewrite of [project], e.g. when the novel leaves the shelf. */
    @Synchronized
    fun deleteAll(project: Project) {
        File(File(filesDir, "rewrites"), Vault.safeFolder(project.id)).deleteRecursively()
    }

    private fun chapterFile(project: Project, chapter: Chapter) = File(dir(project), key(chapter) + ".md")

    private fun noteFile(project: Project, chapter: Chapter) = File(dir(project), key(chapter) + ".note.md")

    fun bible(project: Project) = BibleStore(File(dir(project), "bible"))

    private fun dir(project: Project) = File(File(filesDir, "rewrites"), Vault.safeFolder(project.id))

    private fun key(chapter: Chapter): String =
        MessageDigest.getInstance("SHA-1").digest(chapter.uri.toByteArray()).take(8)
            .joinToString("") { "%02x".format(it) }

    private fun atomicWrite(target: File, text: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    companion object {
        fun of(context: android.content.Context) = RewriteStore(context.filesDir, KeystoreSecretBox).also { store ->
            ChatGptTokens.save = { fresh -> store.saveSettings(store.settings().copy(chatgpt = fresh)) }
        }
    }
}

/** The transport to an OpenAI-compatible chat endpoint, and the small helpers around it. */
object RewriteClient {

    private const val CONNECT_TIMEOUT_MS = 20_000
    // How long the stream may go quiet: a thinking model can pause for minutes before its first word.
    private const val READ_TIMEOUT_MS = 300_000
    // The default Android agent is refused (403) by the host's firewall.
    private const val USER_AGENT = "novel.md.reader/1.1 (Android)"
    /** Bumped whenever a prompt changes, and stamped into every note, so outputs can be compared. */
    const val PROMPT_VERSION = "2026-11-03.4"
    /** Minimum and maximum words for a chapter of [sourceWords], by strength. */
    internal fun lengthTarget(strength: String, sourceWords: Int): Pair<Int, Int> {
        val (low, high) = when (strength) {
            "light" -> 1.0 to 1.2
            "max" -> 1.3 to 1.6
            else -> 1.15 to 1.45
        }
        return Math.ceil(sourceWords * low).toInt() to Math.ceil(sourceWords * high).toInt()
    }

    /** How far this run may go; the rest of the prompt is the same for every chapter. */
    internal fun strengthRule(strength: String): String = when (strength) {
        "light" -> "STRENGTH: light. Mostly a clean-up. Keep the source's own sentences wherever they work; add only small texture and fix unclear staging, wording and flat dialogue."
        "max" -> "STRENGTH: max. Rewrite scenes as deeply as the laws allow. Give crowds, places and violence full, vivid detail, and sharpen every exchange, while keeping every canon rule above."
        else -> "STRENGTH: full. Rewrite each scene so it reads as its best version, keeping every canon rule above."
    }

    /** The last [n] words of [text], for joining the next chapter to it. */
    fun tailWords(text: String, n: Int = 300): String =
        text.trim().split(Regex("\\s+")).takeLast(n).joinToString(" ")

    /** The part of a saved note worth carrying to the next chapter: facts, not the bookkeeping. */
    fun carryNote(note: String): String =
        note.substringBefore("## Enhancement plan").substringBefore("## Beat ledger").substringBefore("## Fidelity warnings").substringBefore("## Run").trim()

    /** Planning and bookkeeping want the model exact; the prose wants it free. Measured with the lab. */
    internal const val EXACT = 0.2
    internal const val CREATIVE = 0.7

    /** Reads one earlier chapter into sheet updates. */
    suspend fun buildSheets(
        settings: RewriteSettings,
        number: Int,
        title: String,
        markdown: String,
        onProgress: (Int) -> Unit = {},
    ): String = stream(
        settings, RewritePrompts.SHEETS,
        "Chapter citation: (Ch $number)\n\n# $title\n\n$markdown", onProgress, EXACT,
    )

    internal suspend fun stream(
        settings: RewriteSettings,
        system: String,
        user: String,
        onProgress: (Int) -> Unit,
        temperature: Double = EXACT,
    ): String = withContext(Dispatchers.IO) {
        require(settings.ready) { if (settings.isChatGpt) "Sign in with ChatGPT and choose a model first." else "Set the endpoint, key and model first." }
        try {
            send(settings, system, user, onProgress, temperature)
        } catch (e: TemperatureRefused) {
            // Learned from the answer, not from the model's name: next time it goes without.
            synchronized(noTemperature) { noTemperature += settings.model }
            send(settings, system, user, onProgress, null)
        }
    }

    /** Models that answered "temperature is not supported"; asked again without one. */
    private val noTemperature = mutableSetOf<String>()

    private class TemperatureRefused : IOException("temperature not supported")

    private suspend fun send(
        settings: RewriteSettings,
        system: String,
        user: String,
        onProgress: (Int) -> Unit,
        requested: Double?,
    ): String {
        val chatgpt = settings.isChatGpt
        val temperature = requested?.takeUnless { chatgpt && synchronized(noTemperature) { settings.model in noTemperature } }
        val body = if (chatgpt) responsesBody(settings, system, user, temperature) else JSONObject()
            .put("model", settings.model)
            .put("stream", true)
            .apply { temperature?.let { put("temperature", it) } }
            .apply { if (settings.effort.isNotBlank()) put("reasoning_effort", settings.effort) }
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
            .toString()
        val bearer = if (chatgpt) ChatGptTokens.access(settings.chatgpt!!) else settings.apiKey.trim()

        val url = if (chatgpt) "${ChatGptAuth.RESOURCE}/responses" else settings.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            // Idle time between chunks, not the length of the whole answer.
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer $bearer")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        // A blocked read does not notice cancellation; closing the socket does.
        val job = currentCoroutineContext()[Job]
        val watcher = job?.invokeOnCompletion { connection.disconnect() }
        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (chatgpt && code == 400 && temperature != null &&
                    (errorParam(text) == "temperature" || errorMessage(text).contains("temperature", ignoreCase = true))
                ) throw TemperatureRefused()
                if (chatgpt) android.util.Log.w("MDReaderRewrite", "ChatGPT answered $code: ${text.take(600)}")
                if (chatgpt && code == 401) throw IOException("ChatGPT no longer accepts this sign-in. Sign in again in the settings.")
                throw IOException(failure(code, connection.getHeaderField("Retry-After"), text))
            }
            val answer = StringBuilder()
            connection.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    if (chatgpt) responsesErrorOf(line)?.let { throw IOException(it) }
                    val delta = (if (chatgpt) responsesDeltaOf(line) else deltaOf(line)) ?: continue
                    answer.append(delta)
                    onProgress(answer.length)
                }
            }
            answer.toString()
        } finally {
            watcher?.dispose()
            connection.disconnect()
        }
    }

    /** A Responses API request: the system prompt as instructions, the rest as one user message. */
    internal fun responsesBody(settings: RewriteSettings, system: String, user: String, temperature: Double?): String =
        JSONObject()
            .put("model", settings.model)
            .put("stream", true)
            .put("store", false)
            .put("instructions", system)
            .put(
                "input",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", user))),
                ),
            )
            .apply { temperature?.let { put("temperature", it) } }
            .apply { if (settings.effort.isNotBlank()) put("reasoning", JSONObject().put("effort", settings.effort)) }
            .toString()

    /** The text a Responses `data:` line adds: only `response.output_text.delta` events carry any. */
    internal fun responsesDeltaOf(line: String): String? {
        if (!line.startsWith("data:")) return null
        val json = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: return null
        return if (json.optString("type") == "response.output_text.delta") json.optString("delta").takeIf { it.isNotEmpty() } else null
    }

    /** A failure reported inside the stream (`error` or `response.failed`), as a message to show. */
    internal fun responsesErrorOf(line: String): String? {
        if (!line.startsWith("data:")) return null
        val json = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: return null
        val message = when (json.optString("type")) {
            "error" -> json.optString("message").ifBlank { json.optJSONObject("error")?.optString("message").orEmpty() }
            "response.failed" -> json.optJSONObject("response")?.optJSONObject("error")?.optString("message").orEmpty()
            else -> return null
        }
        return "ChatGPT stopped: " + message.ifBlank { "unknown error" }.take(160)
    }

    /** The text a server-sent `data:` line adds to the answer; null for anything else. */
    internal fun deltaOf(line: String): String? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        return runCatching {
            JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("delta")?.optString("content")
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Short fixed messages, like the portfolio chat: never the raw upstream body. */
    internal fun failure(code: Int, retryAfter: String?, body: String): String = when (code) {
        401, 403 -> "The key was refused. Check it in the settings."
        404 -> "Model or endpoint not found. Check the base URL and model."
        429 -> {
            val seconds = retryAfter?.trim()?.toIntOrNull()?.coerceIn(1, 120) ?: 30
            "Rate limited. Try again in $seconds s."
        }
        in 500..599 -> "The service is unavailable right now. Try again shortly."
        // Never the raw body: an HTML error page is noise in a phone dialog.
        else -> listOf("The service answered $code.", errorMessage(body)).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * The models this ChatGPT account can write with, fetched the way Codex fetches them: a models
     * endpoint that answers `{"models": [{"slug", "visibility", "supported_reasoning_levels"}]}`
     * (or the classic `{"data": [{"id"}]}`), asked for with a `client_version` so newer models are
     * not hidden. Each model's effort levels come with it. Tries OpenAI's API, then ChatGPT's own
     * Codex endpoint; the first that lists anything wins, and if none does the last error is shown.
     */
    suspend fun chatGptModels(credential: ChatGptCredential): List<String> {
        val token = ChatGptTokens.access(credential)
        // The first source's error is the one worth showing; the fallback refusing too adds nothing.
        var firstError: Exception? = null
        for (source in ChatGptModels.sources(token)) {
            try {
                val text = withContext(Dispatchers.IO) { get(source.url, token, source.headers) }
                val listed = ChatGptModels.parse(text)
                if (listed.isEmpty()) continue
                synchronized(effortCache) { listed.forEach { (slug, levels) -> if (levels != null) effortCache[slug] = levels } }
                return listed.map { it.first }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: IOException("ChatGPT listed no models for this account.")
    }

    /** What each ChatGPT model source answers, for the debug lab: status and model count, never the token. */
    suspend fun chatGptModelReport(credential: ChatGptCredential): List<String> {
        val token = ChatGptTokens.access(credential)
        return ChatGptModels.sources(token).map { source ->
            runCatching { withContext(Dispatchers.IO) { get(source.url, token, source.headers) } }.fold(
                onSuccess = { text ->
                    val listed = ChatGptModels.parse(text)
                    "${source.url} → OK, ${listed.size} models: " + listed.joinToString { (slug, levels) -> "$slug${levels?.let { " $it" }.orEmpty()}" } +
                        if (listed.isEmpty()) " | body: " + text.take(300) else ""
                },
                onFailure = { "${source.url} → ${it.message}" },
            )
        }
    }

    /** A GET with a bearer token and extra headers; throws the short failure message on non-2xx. */
    private fun get(url: String, token: String, headers: Map<String, String>): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException(failure(code, connection.getHeaderField("Retry-After"), text))
            return text
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The effort levels a ChatGPT model takes. OpenAI's model list doesn't say, so this asks the
     * model itself: a request with a level that doesn't exist is refused before anything is
     * generated, and the refusal lists the levels it does take. A model with no levels refuses
     * reasoning outright, which reads as none. Remembered per model for the session.
     */
    suspend fun chatGptEfforts(credential: ChatGptCredential, model: String): List<String> {
        synchronized(effortCache) { effortCache[model] }?.let { return it }
        val token = ChatGptTokens.access(credential)
        val body = JSONObject()
            .put("model", model)
            .put("input", "Hi")
            .put("store", false)
            .put("max_output_tokens", 16)
            .put("reasoning", JSONObject().put("effort", "probe"))
            .toString()
        val levels = withContext(Dispatchers.IO) {
            val connection = (URL("${ChatGptAuth.RESOURCE}/responses").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray()) }
                val code = connection.responseCode
                if (code in 200..299) return@withContext emptyList<String>()
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code != 400) throw IOException(failure(code, connection.getHeaderField("Retry-After"), text))
                supportedEfforts(text)
            } finally {
                connection.disconnect()
            }
        }
        synchronized(effortCache) { effortCache[model] = levels }
        return levels
    }

    private val effortCache = mutableMapOf<String, List<String>>()

    /** The levels a "reasoning.effort" refusal lists ("Supported values are: 'low', 'medium'..."); none otherwise. */
    internal fun supportedEfforts(errorBody: String): List<String> {
        val message = errorMessage(errorBody)
        val param = errorParam(errorBody)
        if (param != null && !param.startsWith("reasoning")) return emptyList()
        val listed = message.substringAfter("upported values are", "")
        if (listed.isEmpty()) return emptyList()
        return Regex("'([^']+)'").findAll(listed).map { it.groupValues[1] }.filter { it != "probe" }.distinct().toList()
    }

    /** The request field an OpenAI error is about, such as "temperature" or "reasoning.effort". */
    internal fun errorParam(errorBody: String): String? =
        runCatching { JSONObject(errorBody).optJSONObject("error")?.optString("param") }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Model ids the endpoint offers (`GET /models`), sorted. */
    suspend fun models(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val text = call("GET", baseUrl.trim().trimEnd('/') + "/models", apiKey, null)
        val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
        (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }.sorted()
    }

    /**
     * The effort levels [model] accepts, from Ollama's `/api/show` (`thinking.values`). Empty when
     * the model cannot think or only switches thinking on and off. `false` in the list is "none".
     */
    suspend fun efforts(baseUrl: String, apiKey: String, model: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val origin = baseUrl.trim().trimEnd('/').removeSuffix("/v1")
            val text = call("POST", "$origin/api/show", apiKey, JSONObject().put("model", model).toString())
            val values = JSONObject(text).optJSONObject("thinking")?.optJSONArray("values") ?: JSONArray()
            val levels = (0 until values.length()).map { values.get(it) }
            val named = levels.filterIsInstance<String>()
            if (named.isEmpty()) emptyList() else (if (false in levels) listOf("none") else emptyList()) + named
        }.getOrDefault(emptyList())
    }

    private fun call(method: String, url: String, apiKey: String, body: String?): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException(failure(code, connection.getHeaderField("Retry-After"), text))
            return text
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The JSON error's message, or nothing: a body that isn't JSON (an HTML page) is never shown.
     * OpenAI says it as `{"error": {"message"}}`; its ChatGPT route as `{"detail": "..."}`.
     */
    private fun errorMessage(text: String): String =
        runCatching {
            val json = JSONObject(text)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("detail").takeIf { it.isNotBlank() }
        }.getOrNull()?.take(160).orEmpty()
}

/** Cheap, model-free checks of a rewrite against its source; their findings drive the repair. */
object Fidelity {
    fun wordCount(text: String): Int = words(text).size

    /** What is wrong with one rewritten scene, phrased as instructions for its repair. Empty when it passes. */
    fun sceneProblems(
        source: String,
        draft: String,
        beats: List<String>,
        minWords: Int,
        known: String,
        /** The scene written just before; a beat that slipped across the boundary is not missing. */
        before: String = "",
        /** Every scene written so far, so a description repeated across scenes still counts. */
        earlier: String = "",
    ): List<String> {
        val out = mutableListOf<String>()
        val count = wordCount(draft)
        if (minWords > 0 && count < minWords * 0.92) {
            out += "It has $count words; the minimum is $minWords. Add real texture to the beats that matter, not filler."
        }
        // Four-letter stems at 45% were measured on real runs: about 1 false alarm in 200 beats,
        // while still catching most beats that are really gone.
        val have = beatStems(draft + "\n" + before)
        val missing = beats.filter { beat ->
            val keys = beatStems(beat)
            keys.size >= 3 && keys.count { it in have }.toDouble() / keys.size < 0.45
        }
        if (missing.isNotEmpty()) out += "These beats are missing or changed; put them back exactly as the source has them: " + missing.joinToString("; ") { it.take(140) }

        val draftNumbers = numbers(draft)
        val lostNumbers = numbers(storyText(source)).filter { it !in draftNumbers }
        if (lostNumbers.isNotEmpty()) out += "These numbers from the source are missing; keep them as digits: " + lostNumbers.take(10).joinToString(", ")
        val spelledAges = Regex("""\b(?:two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)-year-old""", RegexOption.IGNORE_CASE)
            .findAll(draft).map { it.value }.distinct().toList()
        if (spelledAges.isNotEmpty()) out += "Write ages as digits: " + spelledAges.joinToString(", ")

        val lower = draft.lowercase().replace('\u2019', '\'')
        val banned = RewritePrompts.BANNED.filter { lower.contains(it) }
        if (banned.isNotEmpty()) out += "These banned words or phrases appear; replace them with plain, exact words: " + banned.joinToString(", ")

        val repeated = repeatedDescriptions(source, draft, earlier)
        if (repeated.isNotEmpty()) out += "These descriptions repeat; vary them with the name, a pronoun or an action: " + repeated.joinToString(", ") { "\"$it\"" }

        val invented = inventedNames(draft, source + "\n" + known)
        if (invented.isNotEmpty()) out += "These names or labels are not in the source or the memory; remove them or use the source's own words: " + invented.joinToString(", ")
        return out
    }

    private fun beatStems(text: String): Set<String> = words(text).filter { it.length >= 4 }.map { it.take(4) }.toSet()

    /** The source without translator notes and footnote markers, whose numbers are not story. */
    private fun storyText(source: String): String =
        source.lines().filterNot { line ->
            val t = line.trim()
            t.contains('☜') || Regex("""^\d+\\?\.\s*[”"“]""").containsMatchIn(t) ||
                Regex("""^(?:T/?N|TL|Translator|Author|A/N)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)
        }.joinToString("\n").replace(Regex("""\\?\[\d+\\?]"""), "")

    private fun numbers(text: String): Set<String> =
        Regex("""\d[\d,]*\d|\d""").findAll(text).map { it.value.replace(",", "") }.toSet()

    /** "the older soldier" three times in the draft, where the source does not repeat it itself. Names don't count. */
    internal fun repeatedDescriptions(source: String, draft: String, earlier: String = ""): List<String> {
        fun trigrams(text: String): Map<String, Int> {
            val w = Regex("[\\p{L}’'-]+").findAll(text).map { it.value }.toList()
            return w.windowed(3)
                .filter { (t, a, b) ->
                    t.equals("the", true) && a.lowercase() !in FILLER && b.lowercase() !in FILLER &&
                        a.first().isLowerCase() && b.first().isLowerCase()
                }
                .map { it.joinToString(" ").lowercase() }.groupingBy { it }.eachCount()
        }
        val inSource = trigrams(source)
        val before = trigrams(earlier)
        // Counted over the whole chapter so far, but only blamed on this scene if it uses it again.
        return trigrams(draft).filter { (k, v) -> v + (before[k] ?: 0) >= 3 && (inSource[k] ?: 0) < 2 }.keys.toList()
    }

    /**
     * Names or labels the rewrite made up: a capitalised word mid-sentence, or a quoted Title Case
     * phrase ("Social Parasite"), that the source and memory never use in any case. Contractions,
     * the first word of a line of dialogue and capitalised common nouns are not names.
     */
    internal fun inventedNames(draft: String, known: String): List<String> {
        val knownWords = Regex("\\p{L}+").findAll(known).map { it.value.lowercase() }.toSet()
        val single = Regex("""(?<=[a-z,;] )(\p{Lu}\p{Ll}{2,})(?![\p{L}’'])""").findAll(draft).map { it.groupValues[1] }
        val labels = Regex("""[“"](\p{Lu}\p{Ll}+(?: \p{Lu}\p{Ll}+)+)[”"]""").findAll(draft).map { it.groupValues[1] }
            .filter { phrase -> phrase.split(' ').any { it.lowercase() !in knownWords } }
        val inLabels = labels.flatMap { it.split(' ') }.toSet()
        return (single.filter { it.lowercase() !in knownWords && it !in COMMON_CAPS && it !in inLabels } + labels).distinct().toList()
    }

    private val FILLER = setOf(
        "of", "and", "a", "an", "the", "in", "on", "to", "his", "her", "their", "same", "other", "first", "last",
        "next", "only", "whole", "rest", "with", "was", "were", "is", "had", "has", "at", "from", "for", "as",
        "who", "that", "which", "by", "into", "onto", "over", "under", "before", "after", "he", "she", "it",
        "they", "him", "them", "said", "but", "or", "so", "then", "again", "too", "could", "would", "did",
    )

    private val COMMON_CAPS = setOf(
        "I", "The", "A", "An", "He", "She", "It", "They", "We", "You", "His", "Her", "Their", "Our", "My", "Your",
        "This", "That", "These", "Those", "But", "And", "Or", "So", "Then", "When", "While", "If", "As", "At", "In",
        "On", "Of", "For", "With", "From", "To", "By", "After", "Before", "Even", "Yet", "Still", "Just", "Only",
        "Now", "There", "Here", "What", "Who", "Why", "How", "Where", "Which", "No", "Yes", "Not", "Every", "Each",
        "All", "Some", "Most", "Many", "One", "Two", "Three", "Sir", "Lady", "Lord", "Oh", "Ah", "Hey", "Okay",
        "Please", "Thank", "Thanks", "Well", "Wait", "Damn", "Mr", "Mrs", "Miss", "Brother", "Sister", "Senior",
        "Junior", "Master", "Elder", "Young", "Old", "Little", "Big", "Great", "First", "Second", "Third", "Don't",
        "Didn't", "Can't", "Won't", "It's", "I'm", "That's", "What's", "Let", "Let's", "Look", "Come", "Go", "Stop",
    )

    private fun words(text: String): List<String> =
        text.lowercase().replace('\u2019', '\'').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            .split(' ').filter { it.isNotEmpty() }

    fun check(source: String, revised: String): List<String> {
        if (source.isBlank()) return emptyList()
        val problems = mutableListOf<String>()
        val have = words(revised).toSet()
        val missing = words(source).filter { w -> w.all(Char::isDigit) && w !in have }.distinct()
        if (missing.isNotEmpty()) problems += "source numbers missing from the revision: ${missing.take(12).joinToString(", ")}"
        val ratio = words(revised).size.toDouble() / words(source).size.coerceAtLeast(1)
        if (ratio < 0.95) problems += "revision is only ${(ratio * 100).toInt()}% of the source length; beats may have been dropped"
        if (ratio > 1.6) problems += "revision is ${(ratio * 100).toInt()}% of the source length; it may have padded or invented"
        return problems
    }
}
