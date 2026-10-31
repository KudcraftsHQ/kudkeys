package com.kudcrafts.kudkeys.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceClientTest {
    private lateinit var server: MockWebServer
    private val token = "test-token-0123456789abcdef"

    /** Server side of one socket: records what arrives, the test scripts replies. */
    private class Fake(val readyDelayMs: Long = 0, val onText: Fake.(String) -> Unit = {}) : WebSocketListener() {
        val texts = CopyOnWriteArrayList<String>()
        private val audio = ByteArrayOutputStream()
        @Volatile lateinit var ws: WebSocket
        val audioBytes: ByteArray get() = synchronized(audio) { audio.toByteArray() }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            ws = webSocket
            Thread { Thread.sleep(readyDelayMs); ws.send("""{"type":"ready","session":"t1","model":"fake"}""") }.start()
        }
        override fun onMessage(webSocket: WebSocket, text: String) { texts += text; onText(text) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { synchronized(audio) { audio.write(bytes.toByteArray()) } }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }

        fun final(seg: Int, text: String) = ws.send("""{"type":"final","segment":$seg,"text":"$text","confidence":0.9}""")
        fun partial(seg: Int, text: String) = ws.send("""{"type":"partial","segment":$seg,"text":"$text"}""")
        fun type(t: String) = Json.parseToJsonElement(t).jsonObject["type"]!!.jsonPrimitive.content
    }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun url() = server.url("/v1/stream").toString().replace("http://", "ws://")
    private fun client(source: AudioSource, url: String = url(), tok: String = token, timeout: Long = 5_000) =
        VoiceClient(endpoint = { VoiceEndpoint(url, tok) }, audioSource = { source }, doneTimeoutMs = timeout)
    private fun upgrade(f: Fake) = server.enqueue(MockResponse().withWebSocketUpgrade(f))

    private fun wavSource(seconds: Double, frameDelayMs: Long = 0): Pair<WavAudioSource, ByteArray> {
        val pcm = tonePcm(seconds)
        val f = File.createTempFile("voice", ".wav").apply { deleteOnExit() }
        writeWav(f, pcm)
        return WavAudioSource(f, frameDelayMs) to pcm
    }

    private fun <T> await(ms: Long = 5_000, block: suspend () -> T): T = runBlocking { withTimeout(ms) { block() } }
    private fun until(ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { check(System.currentTimeMillis() < end) { "timed out" }; Thread.sleep(10) }
    }

    @Test fun happyPath_audioQueuedBeforeReady_partialsReplace_outOfOrderFinals() {
        val fake = Fake(readyDelayMs = 300) { t ->
            if (type(t) == "stop") {
                final(2, "dua."); final(0, "Satu,") // segment 1 skipped (silence), finals out of order
                ws.send("""{"type":"done"}"""); ws.close(1000, null)
            }
        }
        upgrade(fake)
        val (source, pcm) = wavSource(1.0) // drained long before `ready`
        val c = client(source)
        assertTrue(c.start(VoiceStart(private = true, keywords = listOf("Fenno"), languages = listOf("id", "en"))))
        await { c.state.first { it is VoiceState.Listening } }
        until { fake.audioBytes.size == pcm.size }

        fake.partial(0, "sa")
        await { c.state.first { it is VoiceState.Listening && it.text == "sa" } }
        fake.partial(0, "satu ka")
        await { c.state.first { it is VoiceState.Listening && it.text == "satu ka" } }

        val end = await { c.stopAndWait() }
        assertEquals("Satu, dua.", (end as VoiceState.Done).text)
        assertEquals(listOf("Satu,", "dua."), end.segments.map { it.text }) // with confidence, for tidy
        assertTrue(source.closed)

        val start = Json.parseToJsonElement(fake.texts.first()).jsonObject
        assertEquals("start", start["type"]!!.jsonPrimitive.content)
        assertEquals("16000", start["sampleRate"]!!.jsonPrimitive.content)
        assertEquals("""["id","en"]""", start["languages"].toString())
        assertEquals("""["Fenno"]""", start["keywords"].toString())
        assertEquals("true", start["private"]!!.jsonPrimitive.content)
        assertEquals("stop", fake.type(fake.texts.last()))
        assertArrayEquals(pcm, fake.audioBytes)
    }

    @Test fun finalReplacesPartial_andLateFinalLandsInPlace() {
        val fake = Fake { t -> if (type(t) == "stop") { final(0, "satu"); ws.send("""{"type":"done"}""") } }
        upgrade(fake)
        val (source, _) = wavSource(30.0, frameDelayMs = 20)
        val c = client(source)
        c.start()
        await { c.state.first { it is VoiceState.Listening } }
        fake.final(1, "dua"); fake.partial(2, "ti")
        await { c.state.first { it is VoiceState.Listening && it.text == "dua ti" } }
        fake.final(2, "tiga")
        await { c.state.first { it is VoiceState.Listening && it.text == "dua tiga" } }
        assertEquals("satu dua tiga", (await { c.stopAndWait() } as VoiceState.Done).text)
    }

    @Test fun stopWithoutDone_finishesWithWhatItHasAfterTimeout() {
        val fake = Fake { t -> if (type(t) == "stop") final(0, "halo") } // never sends done
        upgrade(fake)
        val (source, _) = wavSource(30.0, frameDelayMs = 20)
        val c = client(source, timeout = 600)
        c.start()
        await { c.state.first { it is VoiceState.Listening } }
        val t0 = System.nanoTime()
        c.stop()
        assertTrue(c.state.value is VoiceState.Finishing)
        assertTrue("mic released at stop", source.closed)
        val end = await { c.state.first { !it.isActive } }
        val waited = (System.nanoTime() - t0) / 1_000_000
        assertEquals("halo", (end as VoiceState.Done).text)
        assertTrue("waited $waited ms", waited in 550..3_000)
    }

    @Test fun cancel_sendsCancel_returnsToIdle_noText() {
        val fake = Fake()
        upgrade(fake)
        val (source, _) = wavSource(30.0, frameDelayMs = 20)
        val c = client(source)
        c.start()
        await { c.state.first { it is VoiceState.Listening } }
        fake.final(0, "jangan")
        await { c.state.first { it is VoiceState.Listening && it.text == "jangan" } }
        c.cancel()
        assertEquals(VoiceState.Idle, c.state.value)
        assertTrue(source.closed)
        until { fake.texts.any { fake.type(it) == "cancel" } }
        runCatching { fake.final(1, "late") }
        Thread.sleep(200)
        assertEquals(VoiceState.Idle, c.state.value)
    }

    @Test fun http401_isTokenRejected() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized\n"))
        val (source, _) = wavSource(30.0, frameDelayMs = 20)
        val c = client(source)
        c.start()
        val end = await { c.state.first { !it.isActive } } as VoiceState.Error
        assertEquals(VoiceErrorKind.TokenRejected, end.kind)
        assertEquals("Voice: token rejected", end.kind.userMessage)
        assertTrue(source.closed)
        val sent = server.takeRequest()
        assertEquals("Bearer $token", sent.getHeader("Authorization"))
    }

    @Test fun providerError_keepsTextSoFar() {
        val fake = Fake { t ->
            if (type(t) == "stop") {
                ws.send("""{"type":"error","code":"provider","message":"groq refused the key"}""")
                ws.close(1011, "provider")
            }
        }
        upgrade(fake)
        val (source, _) = wavSource(30.0, frameDelayMs = 20)
        val c = client(source)
        c.start()
        await { c.state.first { it is VoiceState.Listening } }
        fake.final(0, "sebagian")
        await { c.state.first { it is VoiceState.Listening && it.text == "sebagian" } }
        val end = await { c.stopAndWait() } as VoiceState.Error
        assertEquals(VoiceErrorKind.Provider, end.kind)
        assertEquals("sebagian", end.text)
    }

    @Test fun close1011WithoutErrorFrame_isServerError() {
        val fake = Fake { t -> if (type(t) == "stop") ws.close(1011, "boom") }
        upgrade(fake)
        val (source, _) = wavSource(30.0, frameDelayMs = 20)
        val c = client(source)
        c.start()
        await { c.state.first { it is VoiceState.Listening } }
        val end = await { c.stopAndWait() } as VoiceState.Error
        assertEquals(VoiceErrorKind.Server, end.kind)
    }

    @Test fun networkFailure_isNoConnection() {
        val port = ServerSocket(0).use { it.localPort } // nothing listens here now
        val (source, _) = wavSource(30.0, frameDelayMs = 20)
        val c = client(source, url = "ws://127.0.0.1:$port/v1/stream")
        c.start()
        val end = await { c.state.first { !it.isActive } } as VoiceState.Error
        assertEquals(VoiceErrorKind.NoConnection, end.kind)
        assertEquals("Voice: no connection", end.kind.userMessage)
        assertTrue(source.closed)
    }

    @Test fun sessionToken401_marksSignedOut() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized\n"))
        val settings = VoiceSettings(FakePrefs()).apply {
            serverUrl = url()
            token = "legacy-token-0123456789abcdef"
            signedIn("sess.sig", "Hammas", "h@example.com")
        }
        val (source, _) = wavSource(30.0, frameDelayMs = 20)
        val c = VoiceClient(settings::endpoint, { source }, onSessionRejected = settings::markSignedOut)
        c.start()
        val end = await { c.state.first { !it.isActive } } as VoiceState.Error
        assertEquals(VoiceErrorKind.SignedOut, end.kind)
        assertEquals("Signed out — sign in again", end.kind.userMessage)
        assertFalse(settings.isSignedIn)
        assertTrue(settings.wasSignedOut)
        assertEquals("h@example.com", settings.accountEmail) // kept to prefill sign-in
        // the legacy token is next
        assertEquals("legacy-token-0123456789abcdef", settings.endpoint().token)
    }

    @Test fun sessionToken503_keepsTheToken_andSaysServerUnavailable() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
            .setBody("""{"error":"unavailable","reason":"session_lookup_failed"}"""))
        val settings = VoiceSettings(FakePrefs()).apply {
            serverUrl = url()
            signedIn("sess.sig", "Hammas", "h@example.com")
        }
        var rejected = false
        val (source, _) = wavSource(30.0, frameDelayMs = 20)
        val c = VoiceClient(settings::endpoint, { source }, onSessionRejected = { rejected = true; settings.markSignedOut() })
        c.start()
        val end = await { c.state.first { !it.isActive } } as VoiceState.Error
        assertEquals(VoiceErrorKind.Unavailable, end.kind)
        assertEquals("Voice: server unavailable, try again", end.kind.userMessage)
        assertFalse(rejected)
        assertTrue(settings.isSignedIn)
        assertEquals("sess.sig", settings.endpoint().token)
        assertTrue(source.closed)
    }

    @Test fun sessionToken_isSentOverLegacyToken_withoutLanguagesOrKeywords() {
        val fake = Fake { t -> if (type(t) == "stop") { ws.send("""{"type":"done"}"""); ws.close(1000, null) } }
        upgrade(fake)
        val settings = VoiceSettings(FakePrefs()).apply {
            serverUrl = url()
            token = "legacy-token-0123456789abcdef"
            signedIn("sess.sig", "Hammas", "h@example.com")
        }
        val (source, _) = wavSource(0.3)
        val c = VoiceClient(settings::endpoint, { source })
        assertTrue(c.start())
        await { c.state.first { it is VoiceState.Listening } }
        val end = await { c.stopAndWait() }
        assertTrue(end is VoiceState.Done)
        assertEquals("Bearer sess.sig", server.takeRequest().getHeader("Authorization"))
        val start = Json.parseToJsonElement(fake.texts.first()).jsonObject
        assertEquals("start", start["type"]!!.jsonPrimitive.content)
        assertFalse("server merges the profile's languages", "languages" in start)
        assertFalse("server merges the profile's keywords", "keywords" in start)
    }

    @Test fun legacyToken_whenSignedOut() {
        val settings = VoiceSettings(FakePrefs()).apply { token = "legacy-token-0123456789abcdef" }
        assertEquals(VoiceEndpoint(VoiceSettings.DEFAULT_URL, "legacy-token-0123456789abcdef"), settings.endpoint())
        assertFalse(settings.endpoint().isSession)
        settings.signedIn("sess.sig", "H", "h@example.com")
        assertTrue(settings.endpoint().isSession)
        settings.signOut()
        assertFalse(settings.endpoint().isSession)
        assertEquals("https://keys.kudcrafts.com", settings.apiBase)
        assertEquals("http://10.0.2.2:8080", VoiceSettings.apiBaseOf("ws://10.0.2.2:8080/v1/stream"))
    }

    @Test fun noToken_failsBeforeOpeningMic() {
        val (source, _) = wavSource(1.0)
        val c = client(source, tok = "  ")
        assertFalse(c.start())
        assertEquals(VoiceErrorKind.NoToken, (c.state.value as VoiceState.Error).kind)
        assertFalse(source.opened)
        c.reset()
        assertEquals(VoiceState.Idle, c.state.value)
    }

    @Test fun levelAndElapsedMove() {
        val fake = Fake()
        upgrade(fake)
        val (source, _) = wavSource(30.0, frameDelayMs = 20)
        val c = client(source)
        c.start()
        val s = await { c.state.first { it is VoiceState.Listening && it.elapsedMs > 200 } } as VoiceState.Listening
        assertTrue("level ${s.level}", s.level in 0.5f..1f) // a -12 dBFS tone
        c.cancel()
    }

    /** A mic that delivers frames until told to fail, like AudioRecord after a route change. */
    private class FailingMic : AudioSource {
        @Volatile var fail = false
        @Volatile var closed = false
        override val failed get() = fail
        override fun open() = Unit
        override fun read(buffer: ByteArray): Int {
            if (closed || fail) return -1
            Thread.sleep(20)
            buffer.fill(0)
            return buffer.size
        }
        override fun close() { closed = true }
    }

    @Test fun micReadFailure_endsWithMicError_keepsText_releasesMic() {
        val fake = Fake()
        upgrade(fake)
        val mic = FailingMic()
        val c = client(mic)
        assertTrue(c.start())
        await { c.state.first { it is VoiceState.Listening } }
        fake.final(0, "sampai sini")
        await { c.state.first { it is VoiceState.Listening && it.text == "sampai sini" } }
        mic.fail = true
        val end = await { c.state.first { !it.isActive } } as VoiceState.Error
        assertEquals(VoiceErrorKind.Mic, end.kind)
        assertEquals("sampai sini", end.text)
        assertTrue(mic.closed)
    }

    @Test fun sourceRunningOut_isNotAnError() {
        // A WAV that ends (failed = false) keeps the session listening, as before.
        upgrade(Fake())
        val (source, _) = wavSource(0.2)
        val c = client(source)
        assertTrue(c.start())
        await { c.state.first { it is VoiceState.Listening } }
        until { source.exhausted }
        Thread.sleep(100)
        assertTrue(c.state.value is VoiceState.Listening)
        c.cancel()
    }
}
