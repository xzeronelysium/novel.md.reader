package com.mdreader

import com.mdreader.data.RewriteClient
import com.mdreader.data.RewriteSettings
import com.mdreader.data.RewriteStore
import com.mdreader.data.SecretBox
import com.mdreader.data.XaiAuth
import com.mdreader.data.XaiCredential
import com.mdreader.data.XaiModels
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class XaiTest {
    @get:Rule val tmp = TemporaryFolder()

    private val deviceAnswer = """
        {"device_code":"dev-123","user_code":"ABCD-1234","verification_uri":"https://accounts.x.ai/device",
         "verification_uri_complete":"https://accounts.x.ai/device?user_code=ABCD-1234","expires_in":900,"interval":5}
    """.trimIndent()

    @Test fun theDeviceCodeIsReadAndTheAddressOpensWithTheCodeFilledIn() {
        val code = XaiAuth.deviceCodeOf(deviceAnswer)
        assertEquals("ABCD-1234", code.userCode)
        assertEquals("https://accounts.x.ai/device?user_code=ABCD-1234", code.openUrl)
        assertEquals(5, code.intervalSeconds)
        // Without a ready-made address, the code is appended to the page's address.
        val bare = XaiAuth.deviceCodeOf(deviceAnswer.replace(Regex("\"verification_uri_complete\":\"[^\"]*\","), ""))
        assertEquals("https://accounts.x.ai/device?user_code=ABCD-1234", bare.openUrl)
    }

    @Test fun aCodeOrAddressThatIsNotSafeIsRefused() {
        assertThrows(IOException::class.java) { XaiAuth.deviceCodeOf(deviceAnswer.replace("ABCD-1234", "AB CD\\n12")) }
        assertThrows(IOException::class.java) { XaiAuth.deviceCodeOf(deviceAnswer.replace("https://accounts.x.ai/device\"", "http://accounts.x.ai/device\"")) }
        assertThrows(IOException::class.java) { XaiAuth.deviceCodeOf(deviceAnswer.replace("accounts.x.ai/device\"", "evil.example.com/device\"")) }
        assertThrows(IOException::class.java) { XaiAuth.deviceCodeOf(deviceAnswer.replace("accounts.x.ai", "x.ai.evil.com")) }
        assertThrows(IOException::class.java) { XaiAuth.deviceCodeOf("not json") }
    }

    @Test fun eachPollMeansWhatTheServiceSays() {
        assertEquals(XaiAuth.Poll.Waiting, XaiAuth.pollResult(400, """{"error":"authorization_pending"}"""))
        assertEquals(XaiAuth.Poll.SlowDown, XaiAuth.pollResult(400, """{"error":"slow_down"}"""))
        assertTrue(XaiAuth.pollResult(400, """{"error":"access_denied"}""") is XaiAuth.Poll.Failed)
        assertTrue((XaiAuth.pollResult(400, """{"error":"expired_token"}""") as XaiAuth.Poll.Failed).message.contains("ran out"))
        val done = XaiAuth.pollResult(200, """{"access_token":"a","refresh_token":"r","expires_in":3600}""", now = 1_000_000) as XaiAuth.Poll.Done
        assertEquals(XaiCredential("a", "r", 1_000_000 + 3_600_000 - 5 * 60_000), done.credential)
        assertTrue(XaiAuth.pollResult(200, """{"access_token":"a"}""") is XaiAuth.Poll.Failed)
    }

    @Test fun aRenewalMayLeaveTheRefreshTokenAsItWas() {
        val text = """{"access_token":"a2","expires_in":900}"""
        assertEquals("r1", XaiAuth.credentialOf(text, previousRefresh = "r1").refresh)
        assertThrows(IOException::class.java) { XaiAuth.credentialOf(text) }
        assertThrows(IOException::class.java) { XaiAuth.credentialOf("""{"access_token":"a","refresh_token":"r"}""") }
    }

    @Test fun theProxyIsAskedTheWayTheCliAsks() {
        val headers = XaiAuth.proxyHeaders("tok", "grok-4.7", "conv-1")
        assertEquals("Bearer tok", headers["Authorization"])
        assertEquals("xai-grok-cli", headers["X-XAI-Token-Auth"])
        assertEquals("authenticate-response", headers["x-authenticateresponse"])
        assertEquals("grok-4.7", headers["x-grok-model-override"])
        assertEquals("conv-1", headers["x-grok-conv-id"])
        assertTrue(headers["User-Agent"]!!.startsWith("grok-shell/"))
        assertEquals(false, XaiAuth.proxyHeaders("tok").containsKey("x-grok-model-override"))
    }

    @Test fun responsesRequestAndStream() {
        val settings = RewriteSettings(provider = RewriteSettings.GROK, model = "grok-4.7", effort = "high")
        val body = JSONObject(RewriteClient.responsesBody(settings, "SYSTEM", "USER", null))
        assertEquals("SYSTEM", body.getString("instructions"))
        assertEquals("USER", body.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
        assertEquals(false, body.has("temperature"))
        assertEquals(true, JSONObject(RewriteClient.responsesBody(settings.copy(effort = ""), "s", "u", 0.7)).has("temperature"))

        assertEquals("Hi", RewriteClient.responsesDeltaOf("""data: {"type":"response.output_text.delta","delta":"Hi"}"""))
        assertNull(RewriteClient.responsesDeltaOf("""data: {"type":"response.created"}"""))
        assertEquals("Grok stopped: too much", RewriteClient.responsesErrorOf("""data: {"type":"error","message":"too much"}"""))
        assertEquals(
            "Grok stopped: quota",
            RewriteClient.responsesErrorOf("""data: {"type":"response.failed","response":{"error":{"message":"quota"}}}"""),
        )
    }

    @Test fun modelsAreReadInEitherShapeWithTheirEffortLevels() {
        val listed = XaiModels.parse(
            """{"data":[
                {"id":"grok-4.7","supports_reasoning_effort":["low","medium","high"]},
                {"id":"grok-4.5","supports_reasoning_effort":[{"effort":"low"},{"effort":"high"}]},
                {"id":"grok-4.6","supports_reasoning_effort":true},
                {"id":"grok-4.7-build-fast","supports_reasoning_effort":false},
                {"id":"grok-imagine-video"},
                {"id":"grok-3"}
            ]}""",
        )
        assertEquals(listOf("grok-4.7", "grok-4.5", "grok-4.6", "grok-4.7-build-fast", "grok-3"), listed.map { it.first })
        assertEquals(listOf("low", "medium", "high"), listed[0].second)
        assertEquals(listOf("low", "high"), listed[1].second)
        assertEquals(listOf("low", "medium", "high"), listed[2].second)
        assertEquals(emptyList<String>(), listed[3].second)
        assertNull(listed[4].second)
        assertEquals(listOf("grok-4.7", "grok-4.5"), XaiModels.parse("""{"models":[{"slug":"grok-4.7"},{"name":"grok-4.5"},{"visibility":"hide","id":"x"}]}""").map { it.first })
        assertEquals(listOf("grok-4.7"), XaiModels.parse("""["grok-4.7"]""").map { it.first })
        assertEquals(emptyList<Pair<String, List<String>?>>(), XaiModels.parse("<html>"))
    }

    @Test fun signInIsSealedAndANewerGrantIsNeverUndone() {
        val dir = tmp.newFolder()
        val store = RewriteStore(dir, PlainBox)
        val old = XaiCredential("a1", "r1", 1_000)
        val renewed = XaiCredential("a2", "r2", 2_000)
        store.saveSettings(store.settings().copy(provider = RewriteSettings.GROK, xai = renewed))
        assertEquals(false, File(dir, "rewrite-settings.json").readText().contains("r2"))
        // A screen still holding the older grant saves something else: the renewal stays.
        store.saveSettings(store.settings().copy(xai = old, model = "grok-4.7"))
        assertEquals(renewed, store.settings().xai)
        assertEquals("grok-4.7", store.settings().model)
        assertTrue(store.settings().isGrok)
        assertTrue(store.settings().ready)
        // Signing out clears it.
        store.saveSettings(store.settings().copy(xai = null))
        assertNull(store.settings().xai)
        assertEquals(false, store.settings().ready)
    }

    @Test fun anEarlierChatGptSignInIsDroppedFromTheFile() {
        val dir = tmp.newFolder()
        val file = File(dir, "rewrite-settings.json")
        file.writeText("""{"provider":"chatgpt","deviceId":"abc","chatgptEnc":"zzz","baseUrl":"https://ollama.com/v1","model":"gemma4:31b"}""")
        val settings = RewriteStore(dir, PlainBox).settings()
        assertEquals(RewriteSettings.OLLAMA, if (settings.isGrok) RewriteSettings.GROK else RewriteSettings.OLLAMA)
        assertEquals("gemma4:31b", settings.model)
        assertEquals(false, file.readText().contains("chatgptEnc"))
        assertEquals(false, file.readText().contains("deviceId"))
    }

    private object PlainBox : SecretBox {
        override fun seal(plain: String) = plain.reversed()
        override fun open(sealed: String) = sealed.reversed()
    }
}
