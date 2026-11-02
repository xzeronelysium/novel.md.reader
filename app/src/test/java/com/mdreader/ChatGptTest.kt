package com.mdreader

import com.mdreader.data.ChatGptAuth
import com.mdreader.data.ChatGptCredential
import com.mdreader.data.ChatGptModels
import com.mdreader.data.RewriteClient
import com.mdreader.data.RewriteSettings
import com.mdreader.data.RewriteStore
import com.mdreader.data.SecretBox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.URLDecoder

class ChatGptTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun pkceChallengeMatchesTheRfcExample() {
        // RFC 7636, appendix B.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            ChatGptAuth.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun authorizeUrlAsksForDirectUse() {
        val url = ChatGptAuth.authorizeUrl("1B4E28BA-2FA1-11D2-883F-0016D3CCA427", "chal", "st", "no")
        val query = url.substringAfter('?').split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        assertTrue(url.startsWith("https://auth.openai.com/api/accounts/authorize?"))
        assertEquals("dynamic_agent_client", query["client_id"])
        assertEquals("urn:uuid:1b4e28ba-2fa1-11d2-883f-0016d3cca427", query["ext_agent_host_id"])
        assertEquals("http://127.0.0.1:1455/auth/callback", query["redirect_uri"])
        assertEquals("https://api.openai.com/v1", query["resource"])
        assertTrue(query["scope"]!!.split(' ').contains("chatgpt.tokens.use.direct"))
        assertEquals("S256", query["code_challenge_method"])
        assertTrue(!url.contains('+') && !url.contains(' '))
    }

    @Test fun callbackIsReadFromTheRequestOrAPastedUrl() {
        val got = ChatGptAuth.parseCallback("/auth/callback?code=abc&state=s1&client_id=cl-9", "s1")
        assertEquals(ChatGptAuth.Callback("abc", "cl-9"), got)
        assertEquals(got, ChatGptAuth.parseCallback("http://127.0.0.1:1455/auth/callback?state=s1&client_id=cl-9&code=abc", "s1"))
        assertThrows(IOException::class.java) { ChatGptAuth.parseCallback("/auth/callback?code=abc&state=other&client_id=c", "s1") }
        assertThrows(IOException::class.java) { ChatGptAuth.parseCallback("/auth/callback?error=access_denied&state=s1", "s1") }
        assertThrows(IOException::class.java) { ChatGptAuth.parseCallback("https://example.com/x?code=a&state=s1&client_id=c", "s1") }
    }

    @Test fun tokenResponseMustCarryTheDirectScope() {
        val ok = """{"access_token":"at","refresh_token":"rt","expires_in":3600,"scope":"openid chatgpt.tokens.use.direct","id_token":"x"}"""
        val credential = ChatGptAuth.credentialOf(ok, "cl", now = 1_000)
        assertEquals("at", credential.access)
        assertEquals(1_000 + 3_600_000 - 180_000L, credential.expires)
        val missing = """{"access_token":"at","refresh_token":"rt","expires_in":3600,"scope":"openid"}"""
        assertThrows(IOException::class.java) { ChatGptAuth.credentialOf(missing, "cl") }
    }

    @Test fun responsesRequestAndStream() {
        val settings = RewriteSettings(provider = RewriteSettings.CHATGPT, model = "gpt-5.5", effort = "high")
        val body = JSONObject(RewriteClient.responsesBody(settings, "SYSTEM", "USER", 0.7))
        assertEquals("SYSTEM", body.getString("instructions"))
        assertEquals("USER", body.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
        assertEquals(false, body.has("temperature"))
        assertEquals(true, JSONObject(RewriteClient.responsesBody(settings.copy(model = "gpt-4.1", effort = ""), "s", "u", 0.7)).has("temperature"))

        assertEquals("Hi", RewriteClient.responsesDeltaOf("""data: {"type":"response.output_text.delta","delta":"Hi"}"""))
        assertNull(RewriteClient.responsesDeltaOf("""data: {"type":"response.created"}"""))
        assertNull(RewriteClient.responsesDeltaOf("event: response.output_text.delta"))
        assertEquals("ChatGPT stopped: too much", RewriteClient.responsesErrorOf("""data: {"type":"error","message":"too much"}"""))
        assertEquals(
            "ChatGPT stopped: quota",
            RewriteClient.responsesErrorOf("""data: {"type":"response.failed","response":{"error":{"message":"quota"}}}"""),
        )
    }

    @Test fun effortLevelsFollowTheModel() {
        assertEquals(listOf("none", "low", "medium", "high", "xhigh"), ChatGptModels.efforts("gpt-5.5"))
        assertEquals(listOf("minimal", "low", "medium", "high"), ChatGptModels.efforts("gpt-5-mini"))
        assertEquals(emptyList<String>(), ChatGptModels.efforts("gpt-4.1"))
        assertEquals(true, ChatGptModels.isWritingModel("gpt-5.4-mini"))
        assertEquals(false, ChatGptModels.isWritingModel("gpt-4o-realtime-preview"))
    }

    @Test fun signInIsSealedAndANewerGrantIsNeverUndone() {
        val dir = tmp.newFolder()
        val store = RewriteStore(dir, PlainBox)
        val first = store.settings()
        assertTrue(first.deviceId.matches(Regex("[0-9a-f-]{36}")))
        assertEquals(first.deviceId, store.settings().deviceId)

        val old = ChatGptCredential("a1", "r1", 1_000, "cl")
        val renewed = ChatGptCredential("a2", "r2", 2_000, "cl")
        store.saveSettings(first.copy(provider = RewriteSettings.CHATGPT, chatgpt = renewed))
        assertEquals(false, java.io.File(dir, "rewrite-settings.json").readText().contains("r2"))
        // A screen still holding the older grant saves something else: the renewal stays.
        store.saveSettings(store.settings().copy(chatgpt = old, model = "gpt-5.5"))
        assertEquals(renewed, store.settings().chatgpt)
        assertEquals("gpt-5.5", store.settings().model)
        assertTrue(store.settings().isChatGpt)
        // Signing out clears it.
        store.saveSettings(store.settings().copy(chatgpt = null))
        assertNull(store.settings().chatgpt)
    }

    private object PlainBox : SecretBox {
        override fun seal(plain: String) = plain.reversed()
        override fun open(sealed: String) = sealed.reversed()
    }
}
