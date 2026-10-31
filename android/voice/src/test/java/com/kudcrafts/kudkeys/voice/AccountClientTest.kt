package com.kudcrafts.kudkeys.voice

import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccountClientTest {
    private lateinit var server: MockWebServer
    private lateinit var settings: VoiceSettings
    private lateinit var account: Account

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        settings = VoiceSettings(FakePrefs())
        settings.serverUrl = server.url("/v1/stream").toString().replace("http://", "ws://")
        account = Account(settings, AccountClient({ settings.apiBase }, userAgent = { "KudKeys/test (Android 15; Test)" }))
    }
    @After fun tearDown() { server.shutdown() }

    private val profileJson =
        """{"id":"u1","email":"h@example.com","name":"Hammas","image":null,"voiceKeywords":["Fenno","TypeMap"],"languages":["id","en"]}"""

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun signIn_storesTokenFromHeader() = runBlocking {
        server.enqueue(
            json(200, """{"redirect":false,"token":"unsigned","user":{"id":"u1","email":"h@example.com","name":"Hammas"}}""")
                .setHeader("set-auth-token", "tok.signature")
        )
        val r = account.signIn(" h@example.com ", "correct horse battery")
        assertTrue(r is AccountResult.Ok)
        assertEquals("tok.signature", settings.sessionToken)
        assertEquals("Hammas", settings.accountName)
        assertEquals("h@example.com", settings.accountEmail)
        assertTrue(settings.endpoint().isSession)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/auth/sign-in/email", req.path)
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("Origin"))
        assertEquals("KudKeys/test (Android 15; Test)", req.getHeader("User-Agent"))
        assertEquals(
            Json.parseToJsonElement("""{"email":"h@example.com","password":"correct horse battery"}"""),
            Json.parseToJsonElement(req.body.readUtf8()),
        )
    }

    @Test fun signIn_wrongPassword() = runBlocking {
        server.enqueue(json(401, """{"code":"INVALID_EMAIL_OR_PASSWORD","message":"Invalid email or password"}"""))
        assertEquals(AccountResult.WrongPassword, account.signIn("h@example.com", "nope"))
        assertFalse(settings.isSignedIn)
    }

    @Test fun signIn_serverNotReachable() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        settings.serverUrl = "ws://127.0.0.1:$port/v1/stream"
        assertEquals(AccountResult.Unreachable, account.signIn("h@example.com", "x"))
    }

    @Test fun signIn_noNetwork() = runBlocking {
        settings.serverUrl = "wss://no-such-host.invalid/v1/stream"
        assertEquals(AccountResult.NoNetwork, account.signIn("h@example.com", "x"))
    }

    @Test fun me_401_signsOut() = runBlocking {
        settings.signedIn("tok.sig", "Hammas", "h@example.com")
        server.enqueue(json(401, """{"error":"unauthorized"}"""))
        assertEquals(AccountResult.SignedOut, account.me())
        assertFalse(settings.isSignedIn)
        assertTrue(settings.wasSignedOut)
        assertEquals("Bearer tok.sig", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun me_parsesProfile() = runBlocking {
        settings.signedIn("tok.sig", "old", "h@example.com")
        server.enqueue(json(200, profileJson))
        val p = (account.me() as AccountResult.Ok).value
        assertEquals(listOf("Fenno", "TypeMap"), p.voiceKeywords)
        assertEquals(listOf("id", "en"), p.languages)
        assertEquals("Hammas", settings.accountName)
    }

    @Test fun patch_bodyShape() = runBlocking {
        settings.signedIn("tok.sig", "Hammas", "h@example.com")
        server.enqueue(json(200, profileJson))
        val r = account.save(" Hammas ", listOf("Fenno", "TypeMap"), listOf("id", "en", "jv"))
        assertTrue(r is AccountResult.Ok)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/me", req.path)
        assertEquals("Bearer tok.sig", req.getHeader("Authorization"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals(
            Json.parseToJsonElement("""{"name":"Hammas","voiceKeywords":["Fenno","TypeMap"],"languages":["id","en","jv"]}"""),
            Json.parseToJsonElement(req.body.readUtf8()),
        )
        // only the fields given
        assertEquals("""{"voiceKeywords":[]}""", AccountClient.patchBody(null, emptyList(), null).toString())
    }

    @Test fun patch_400_showsIssue() = runBlocking {
        settings.signedIn("tok.sig", "Hammas", "h@example.com")
        server.enqueue(json(400, """{"error":"invalid","issues":[{"path":"voiceKeywords","message":"at most 100"}]}"""))
        assertEquals(AccountResult.Failed("voiceKeywords: at most 100"), account.save(null, listOf("x"), null))
        assertTrue(settings.isSignedIn)
    }

    @Test fun usage_parses() = runBlocking {
        settings.signedIn("tok.sig", "Hammas", "h@example.com")
        server.enqueue(json(200, """{"week":{"since":"2026-10-25T17:00:00.000Z","sessions":12,"audioMinutes":8.4},
            "month":{"since":"2026-09-30T17:00:00.000Z","sessions":40,"audioMinutes":31.2},
            "sessions":[{"id":"c1","session":"f87ce63f","startedAt":"2026-10-30T10:00:00.000Z","endedAt":"x",
              "audioSeconds":4.0,"finals":2,"outcome":"stopped","model":"whisper-large-v3"}]}"""))
        val u = (account.usage() as AccountResult.Ok).value
        assertEquals(UsagePeriod(12, 8.4), u.week)
        assertEquals(UsagePeriod(40, 31.2), u.month)
        assertEquals(UsageSession("2026-10-30T10:00:00.000Z", 4.0, 2, "stopped"), u.sessions.single())
        assertEquals("/api/me/usage", server.takeRequest().path)
    }

    @Test fun signOut_callsServerWithOriginAndForgetsToken() = runBlocking {
        settings.signedIn("tok.sig", "Hammas", "h@example.com")
        server.enqueue(json(200, """{"success":true}"""))
        account.signOut(this)!!.join()
        val req = server.takeRequest()
        assertEquals("/api/auth/sign-out", req.path)
        assertEquals("Bearer tok.sig", req.getHeader("Authorization"))
        assertEquals(AccountClient.originOf(settings.apiBase), req.getHeader("Origin"))
        assertEquals("http://${server.hostName}:${server.port}", req.getHeader("Origin"))
        assertFalse(settings.isSignedIn)
        assertFalse(settings.wasSignedOut)
    }

    @Test fun signOut_forgetsTokenBeforeTheServerAnswers_andIgnoresFailures() = runBlocking {
        settings.signedIn("tok.sig", "Hammas", "h@example.com")
        server.enqueue(json(500, """{"error":"boom"}""").setHeadersDelay(1, TimeUnit.SECONDS))
        val job = account.signOut(this)!!
        assertFalse(settings.isSignedIn) // already gone while the server is still thinking
        assertTrue(job.isActive)
        job.join()
        assertFalse(settings.isSignedIn)
    }

    @Test fun signOut_offline_stillSignsOut() = runBlocking {
        settings.signedIn("tok.sig", "Hammas", "h@example.com")
        settings.serverUrl = "ws://127.0.0.1:${ServerSocket(0).use { it.localPort }}/v1/stream"
        account.signOut(this)!!.join()
        assertFalse(settings.isSignedIn)
    }

    @Test fun originOf_dropsPathAndDefaultPort() {
        assertEquals("https://keys.kudcrafts.com", AccountClient.originOf("https://keys.kudcrafts.com"))
        assertEquals("https://keys.kudcrafts.com", AccountClient.originOf("https://keys.kudcrafts.com:443/api/"))
        assertEquals("http://10.0.2.2:8080", AccountClient.originOf("http://10.0.2.2:8080"))
    }

    @Test fun me_503_keepsTheToken() = runBlocking {
        settings.signedIn("tok.sig", "Hammas", "h@example.com")
        server.enqueue(json(503, """{"error":"unavailable","reason":"session_lookup_failed"}"""))
        assertEquals(AccountResult.Unreachable, account.me())
        assertTrue(settings.isSignedIn)
        assertFalse(settings.wasSignedOut)
        assertEquals("tok.sig", settings.sessionToken)
    }
}
