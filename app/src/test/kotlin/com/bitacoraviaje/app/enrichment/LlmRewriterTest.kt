package com.bitacoraviaje.app.enrichment

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/** PLAN 4.3 acceptance: with a simulated client the rewrite keeps the source and license, and invented numbers are discarded. */
class LlmRewriterTest {
    private lateinit var server: MockWebServer
    private val request = RewriteRequest("El Chaltén", "El Chaltén es un municipio de Santa Cruz, Argentina, fundado en 1985 al pie del cerro Fitz Roy.", listOf("Altitud: 397 m", "Población: 1.865 (2022)"))

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun `guard accepts numbers present in the source or facts and rejects invented ones`() {
        assertEquals(setOf("3406", "1985", "12", "2.5"), RewriteGuard.numbers("A 3.406 m, fundado en 1985, 12 km, 2,5 h"))
        assertTrue(RewriteGuard.isFaithful("Un pueblo de 1.865 habitantes a 397 m, fundado en 1985.", request.sourceText, request.facts))
        assertFalse(RewriteGuard.isFaithful("Un pueblo fundado en 1980.", request.sourceText, request.facts), "a year that is not in the source")
        assertNull(RewriteGuard.accept("SIN_DATOS", request))
        assertNull(RewriteGuard.accept(" «${"palabra ".repeat(80)}» ", request), "too long")
        assertEquals("Texto breve.", RewriteGuard.accept("\"Texto breve.\"\n", request))
        assertTrue(RewriteGuard.prompt(request).contains("TEXTO FUENTE") && RewriteGuard.prompt(request).contains("- Altitud: 397 m"))
    }

    private fun answer(text: String) = """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":${kotlinx.serialization.json.JsonPrimitive(text)}}],"model":"m","stop_reason":"end_turn"}"""

    @Test
    fun `the client sends only the source text with the key header and keeps a faithful answer`() = runTest {
        val c = ClaudeRewriter().apply { baseUrl = server.url("/").toString() }
        server.enqueue(MockResponse(body = answer("Llegamos a El Chaltén, un pueblo de Santa Cruz fundado en 1985 al pie del Fitz Roy, a 397 m de altura.")))
        val out = c.rewrite(request, "sk-test-key", "claude-sonnet-5")
        assertTrue(out!!.startsWith("Llegamos a El Chaltén"))
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.url.encodedPath)
        assertEquals("sk-test-key", req.headers["x-api-key"])
        assertEquals(ClaudeRewriter.API_VERSION, req.headers["anthropic-version"])
        val body = req.body!!.utf8()
        assertTrue(body.contains("\"model\":\"claude-sonnet-5\"") && body.contains("fundado en 1985") && body.contains("Altitud: 397 m"))
        assertFalse(body.contains("-49.") || body.contains("-72."), "no coordinates leave the phone")
    }

    @Test
    fun `invented figures, the sentinel and a rejected key are handled`() = runTest {
        val c = ClaudeRewriter().apply { baseUrl = server.url("/").toString() }
        server.enqueue(MockResponse(body = answer("Un pueblo de 5.000 habitantes fundado en 1985.")))
        assertNull(c.rewrite(request, "k", "m"), "5.000 is not in the source: discarded")
        server.enqueue(MockResponse(body = answer("SIN_DATOS")))
        assertNull(c.rewrite(request, "k", "m"))
        server.enqueue(MockResponse(code = 401, body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        assertFailsWith<LlmAuthException> { c.rewrite(request, "bad", "m") }
        server.enqueue(MockResponse(code = 529, body = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        val e = assertFailsWith<java.io.IOException> { c.rewrite(request, "k", "m") }
        assertTrue(e.message!!.contains("Overloaded"))
    }
}
