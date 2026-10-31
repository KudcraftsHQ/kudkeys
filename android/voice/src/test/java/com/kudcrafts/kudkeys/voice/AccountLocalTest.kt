package com.kudcrafts.kudkeys.voice

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in end to end against a LOCAL server (never production): sign in, `/api/me`, PATCH, a voice stream
 * with the session token, its usage row, sign out, and the stream refusing the dead token.
 *
 * ```
 * KUDKEYS_LOCAL_BASE=http://localhost:18080 KUDKEYS_LOCAL_EMAIL=… KUDKEYS_LOCAL_PASSWORD=… \
 *   [KUDKEYS_LOCAL_WAV=clip.wav] ./gradlew :voice:testDebugUnitTest --tests '*AccountLocalTest*'
 * ```
 */
class AccountLocalTest {
    @Test fun signInProfileStreamSignOut() = runBlocking {
        val base = System.getenv("KUDKEYS_LOCAL_BASE").orEmpty()
        assumeTrue("set KUDKEYS_LOCAL_BASE", base.isNotEmpty())
        val settings = VoiceSettings(FakePrefs()).apply { serverUrl = base.replaceFirst("http", "ws") + "/v1/stream" }
        assertEquals(base, settings.apiBase)
        val account = Account(settings, AccountClient({ settings.apiBase }, userAgent = { "KudKeys/e2e (Android 15; JVM)" }))

        assertEquals(AccountResult.WrongPassword, account.signIn(System.getenv("KUDKEYS_LOCAL_EMAIL").orEmpty(), "not-the-password"))
        val signedIn = account.signIn(System.getenv("KUDKEYS_LOCAL_EMAIL").orEmpty(), System.getenv("KUDKEYS_LOCAL_PASSWORD").orEmpty())
        assertTrue("$signedIn", signedIn is AccountResult.Ok)
        println("signed in as ${settings.accountName} <${settings.accountEmail}>")

        val me = (account.me() as AccountResult.Ok).value
        println("GET /api/me → $me")
        val saved = account.save("Android E2E", listOf("Fenno", "TypeMap", "fenno"), listOf("id", "en", "jv"))
        val profile = (saved as AccountResult.Ok).value
        println("PATCH /api/me → $profile")
        assertEquals(listOf("Fenno", "TypeMap"), profile.voiceKeywords) // server drops the case duplicate
        assertEquals(listOf("id", "en", "jv"), profile.languages)

        // a voice note with the session token (settings.endpoint() picks it over the legacy one)
        settings.token = "legacy-is-not-used-0123456789"
        val wav = System.getenv("KUDKEYS_LOCAL_WAV").orEmpty()
        val source = if (wav.isNotEmpty()) WavAudioSource(File(wav), frameDelayMs = 100)
        else File.createTempFile("tone", ".wav").let { writeWav(it, tonePcm(3.0)); WavAudioSource(it, 100) }
        val before = (account.usage() as AccountResult.Ok).value.month.sessions
        val client = VoiceClient(settings::endpoint, { source }, onSessionRejected = settings::markSignedOut)
        assertTrue(client.start(VoiceStart()))
        withTimeout(60_000) { while (!source.exhausted && client.state.value.isActive) kotlinx.coroutines.delay(50) }
        val end = withTimeout(15_000) { client.stopAndWait() }
        println("stream → $end")
        assertTrue("$end", end is VoiceState.Done)
        client.close()

        // the usage row is written when the session ends
        var usage = (account.usage() as AccountResult.Ok).value
        repeat(20) { if (usage.month.sessions > before) return@repeat; Thread.sleep(250); usage = (account.usage() as AccountResult.Ok).value }
        println("usage → week ${usage.week}, month ${usage.month}, latest ${usage.sessions.firstOrNull()}")
        assertEquals(before + 1, usage.month.sessions)

        // sign out: the token is dead on the server too, so a stream with it is refused
        val dead = settings.sessionToken
        account.signOut(this)?.join()
        assertFalse(settings.isSignedIn)
        val deadSettings = VoiceSettings(FakePrefs()).apply { serverUrl = settings.serverUrl; signedIn(dead, "x", "x") }
        val c2 = VoiceClient(deadSettings::endpoint, { File.createTempFile("tone", ".wav").let { writeWav(it, tonePcm(1.0)); WavAudioSource(it, 100) } },
            onSessionRejected = deadSettings::markSignedOut)
        c2.start(VoiceStart())
        val refused = withTimeout(15_000) { c2.state.first { !it.isActive } }
        println("stream after sign-out → $refused")
        assertEquals(VoiceErrorKind.SignedOut, (refused as VoiceState.Error).kind)
        assertTrue(deadSettings.wasSignedOut)
        assertEquals(AccountResult.SignedOut, Account(deadSettings.apply { signedIn(dead, "x", "x") }).me())
        c2.close()
    }
}
