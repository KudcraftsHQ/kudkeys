package com.kudcrafts.kudkeys.voice

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The three text endpoints against MockWebServer, with the JSON of docs/v0.3-spec.md "Server contract". */
class TextClientTest {
    private lateinit var server: MockWebServer
    private lateinit var settings: VoiceSettings
    private lateinit var api: TextApi

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        settings = VoiceSettings(FakePrefs())
        settings.serverUrl = server.url("/v1/stream").toString().replace("http://", "ws://")
        settings.signedIn("sess.tok", "Hammas", "h@example.com")
        api = TextApi(settings, TextClient({ settings.apiBase }, userAgent = { "KudKeys/test" }))
    }
    @After fun tearDown() { server.shutdown() }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun body() = server.takeRequest().let { r ->
        assertEquals("POST", r.method)
        assertEquals("Bearer sess.tok", r.getHeader("Authorization"))
        assertEquals("KudKeys/test", r.getHeader("User-Agent"))
        r.path to Json.parseToJsonElement(r.body.readUtf8())
    }

    @Test fun tidy_textAndQuestions() = runBlocking {
        val tidied = "Pertama, cek Feno.\n\n1. Satu\n2. Dua"
        server.enqueue(json(200, """{"text":${kotlinx.serialization.json.JsonPrimitive(tidied)},"questions":[
            {"id":"q1","original":"Feno","start":13,"end":17,"options":["Fenno","Feno"]},
            {"id":"bad","original":"x","start":90,"end":95,"options":["y"]}]}"""))
        val r = api.tidy("pertama cek feno satu dua", private = false)
        r as TextResult.Ok
        assertEquals(tidied, r.value.text)
        // the out-of-range question is dropped, and so is the option that is the word itself
        assertEquals(listOf(TidyQuestion("q1", "Feno", 13, 17, listOf("Fenno"))), r.value.questions)
        val (path, b) = body()
        assertEquals("/api/text/tidy", path)
        assertEquals(Json.parseToJsonElement("""{"text":"pertama cek feno satu dua"}"""), b)
    }

    @Test fun fix_sendsPrivate() = runBlocking {
        server.enqueue(json(200, """{"text":"Halo, apa kabar?","changed":true}"""))
        val r = api.fix("halo apa kabr", private = true)
        assertEquals(TextResult.Ok(Fixed("Halo, apa kabar?", true)), r)
        val (path, b) = body()
        assertEquals("/api/text/fix", path)
        assertEquals(Json.parseToJsonElement("""{"text":"halo apa kabr","private":true}"""), b)
    }

    @Test fun fix_unchanged() = runBlocking {
        server.enqueue(json(200, """{"text":"Oke.","changed":false}"""))
        val r = api.fix("Oke.", private = false) as TextResult.Ok
        assertFalse(r.value.changed)
    }

    @Test fun instruct_emptyText() = runBlocking {
        server.enqueue(json(200, """{"text":"Maaf, aku telat 10 menit ya."}"""))
        val r = api.instruct("tulis aku telat 10 menit", "", private = false)
        assertEquals(TextResult.Ok("Maaf, aku telat 10 menit ya."), r)
        val (path, b) = body()
        assertEquals("/api/text/instruct", path)
        assertEquals(Json.parseToJsonElement("""{"instruction":"tulis aku telat 10 menit","text":""}"""), b)
    }

    @Test fun errors() = runBlocking {
        server.enqueue(json(429, """{"error":"rate_limited"}""").setHeader("Retry-After", "7"))
        assertEquals(TextResult.RateLimited(7), api.fix("a", false))
        server.enqueue(json(503, """{"error":"unavailable"}"""))
        assertEquals(TextResult.Unavailable, api.fix("a", false))
        server.enqueue(json(400, """{"error":"bad_request"}"""))
        assertEquals(TextResult.Rejected, api.fix("a", false))
        server.enqueue(json(200, """not json"""))
        assertEquals(TextResult.Rejected, api.fix("a", false))
        assertTrue(settings.isSignedIn)
        server.enqueue(json(401, """{"error":"unauthorized"}"""))
        assertEquals(TextResult.SignedOut, api.instruct("x", "y", false))
        assertFalse(settings.isSignedIn)
        assertTrue(settings.wasSignedOut)
        // signed out now: nothing is sent
        val before = server.requestCount
        assertEquals(TextResult.NotSignedIn, api.tidy("a", false))
        assertEquals(before, server.requestCount)
    }

    @Test fun serverDown() = runBlocking {
        server.shutdown()
        assertEquals(TextResult.Unavailable, api.fix("a", false))
    }

    @Test fun questions_overlapDropped() {
        val text = "aa bb cc"
        val q = TextClient.parseQuestions(Json.parseToJsonElement("""[
            {"id":"b","start":3,"end":5,"options":["BB"]},
            {"id":"a","start":0,"end":5,"options":["AB BC"]},
            {"id":"c","start":6,"end":8,"options":["DD","dd"," "]}]"""), text)
        assertEquals(listOf("a", "c"), q.map { it.id })
        assertEquals(listOf("DD"), q[1].options)
        assertEquals("cc", q[1].original)
    }

    @Test fun questions_optionEqualToTheWordIgnoringCaseIsDropped_andAQuestionWithNoneLeft() {
        val text = "cek Feno dan Budi"
        val q = TextClient.parseQuestions(Json.parseToJsonElement("""[
            {"id":"q1","start":4,"end":8,"options":["feno","FENO.","Fenno"]},
            {"id":"q2","start":13,"end":17,"options":["budi","Budi!"]}]"""), text)
        assertEquals(listOf(TidyQuestion("q1", "Feno", 4, 8, listOf("Fenno"))), q)
    }

    @Test fun tidy_sendsSegmentsWithConfidence() = runBlocking {
        server.enqueue(json(200, """{"text":"Cek feno.","questions":[]}"""))
        api.tidy("cek feno", private = false, segments = listOf(Segment("cek", 0.95f), Segment("feno", 0.4f)))
        val (_, b) = body()
        assertEquals(Json.parseToJsonElement("""{"text":"cek feno","segments":[{"text":"cek","confidence":0.95},{"text":"feno","confidence":0.4}]}"""), b)
    }

    @Test fun tooLongAndChangedTooMuch() = runBlocking {
        server.enqueue(json(413, """{"error":"too_long"}"""))
        assertEquals(TextResult.TooLong, api.tidy("a", false))
        server.enqueue(json(422, """{"error":"changed_too_much"}"""))
        assertEquals(TextResult.ChangedTooMuch, api.fix("a", false))
        // over the limit: refused here, nothing sent (and nothing truncated)
        val before = server.requestCount
        assertEquals(TextResult.TooLong, api.instruct("x", "a".repeat(TextClient.MAX_TIDY + 1), false))
        assertEquals(TextResult.TooLong, api.tidy("a".repeat(TextClient.MAX_TIDY + 1), false))
        assertEquals(before, server.requestCount)
    }

    @Test fun safeTakeNeverSplitsAnEmoji() {
        val s = "ab\uD83D\uDE00cd" // ab😀cd
        assertEquals("ab", TextClient.safeTake(s, 3))
        assertEquals("ab\uD83D\uDE00", TextClient.safeTake(s, 4))
        assertEquals("cd", TextClient.safeTakeLast(s, 3))
        assertEquals("\uD83D\uDE00cd", TextClient.safeTakeLast(s, 4))
        assertEquals(s, TextClient.safeTake(s, 99))
    }

    @Test fun cancellingTheCoroutineCancelsTheCall() = runBlocking {
        server.enqueue(json(200, """{"text":"late"}""").setHeadersDelay(5, java.util.concurrent.TimeUnit.SECONDS))
        val job = launch(kotlinx.coroutines.Dispatchers.IO) { api.instruct("x", "y", false) }
        server.takeRequest()
        val t0 = System.nanoTime()
        job.cancel()
        job.join()
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }
}
