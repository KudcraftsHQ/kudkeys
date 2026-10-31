package com.kudcrafts.kudkeys.voice

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
        // the out-of-range question is dropped
        assertEquals(listOf(TidyQuestion("q1", "Feno", 13, 17, listOf("Fenno", "Feno"))), r.value.questions)
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
            {"id":"a","start":0,"end":5,"options":["AA BB"]},
            {"id":"c","start":6,"end":8,"options":["CC","CC"," "]}]"""), text)
        assertEquals(listOf("a", "c"), q.map { it.id })
        assertEquals(listOf("CC"), q[1].options)
        assertEquals("cc", q[1].original)
    }
}
