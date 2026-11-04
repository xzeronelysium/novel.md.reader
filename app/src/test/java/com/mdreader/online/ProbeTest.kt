package com.mdreader.online

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeTest {

    @Test
    fun `probe toolchain`() = runBlocking {
        val json = org.json.JSONObject("""{"code":200,"page":1}""")
        assertEquals(200, json.getInt("code"))

        val engine = MockEngine { respond("hello") }
        val client = HttpClient(engine)
        assertEquals("hello", client.get("https://example.com/").bodyAsText())
        client.close()

        val doc = Jsoup.parse("<p>hi</p>")
        assertEquals("hi", doc.selectFirst("p")?.text())
    }
}
