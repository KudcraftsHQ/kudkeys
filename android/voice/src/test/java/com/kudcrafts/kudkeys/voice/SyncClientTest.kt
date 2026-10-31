package com.kudcrafts.kudkeys.voice

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Every sync and backup endpoint of docs/v0.4-spec.md against MockWebServer. Made-up words only. */
class SyncClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: SyncClient

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = SyncClient({ server.url("/").toString().trimEnd('/') }, userAgent = { "KudKeys/test" })
    }
    @After fun tearDown() { server.shutdown() }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun take(method: String, path: String): RecordedRequest = server.takeRequest().also {
        assertEquals(method, it.method)
        assertEquals(path, it.path)
        assertEquals("Bearer sess.tok", it.getHeader("Authorization"))
        assertEquals("KudKeys/test", it.getHeader("User-Agent"))
    }

    @Test fun getSettings_okAnd404() = runBlocking<Unit> {
        server.enqueue(json(200, """{"prefs":{"boolean":{"show_hints":false}},"updatedAt":"2026-10-30T08:00:00.000Z"}"""))
        val r = client.getSettings("sess.tok") as SyncResult.Ok
        assertEquals(false, r.value.prefs["boolean"]!!.jsonObject["show_hints"]!!.jsonPrimitive.boolean)
        assertEquals("2026-10-30T08:00:00.000Z", r.value.updatedAt)
        take("GET", "/api/sync/settings")

        server.enqueue(json(404, """{"error":"none"}"""))
        assertEquals(SyncResult.NotFound, client.getSettings("sess.tok"))
    }

    @Test fun putSettings_sendsPrefsUpdatedAtDevice_returnsMerged() = runBlocking<Unit> {
        server.enqueue(json(200, """{"prefs":{"int":{"key_longpress_timeout":300}},"updatedAt":"2026-10-31T01:00:00.000Z"}"""))
        val prefs = buildJsonObject { putJsonObject("int") { put("key_longpress_timeout", 450) } }
        val r = client.putSettings("sess.tok", prefs, "2026-10-31T00:00:00Z", "dev-1") as SyncResult.Ok
        assertEquals(300, r.value.prefs["int"]!!.jsonObject["key_longpress_timeout"]!!.jsonPrimitive.int)
        val req = take("PUT", "/api/sync/settings")
        assertEquals("application/json; charset=utf-8", req.getHeader("Content-Type"))
        val b = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(prefs, b["prefs"])
        assertEquals("2026-10-31T00:00:00Z", b["updatedAt"]!!.jsonPrimitive.content)
        assertEquals("dev-1", b["deviceId"]!!.jsonPrimitive.content)
    }

    @Test fun getWords_allThenSince() = runBlocking<Unit> {
        server.enqueue(json(200, """{"words":[
            {"word":"glorbix","locale":"en","count":4,"source":"typed","deleted":false,"updatedAt":"2026-10-30T00:00:00Z"},
            {"word":"vandelop","locale":"id","count":1,"source":"swiftkey","deleted":true,"updatedAt":"2026-10-30T00:00:01Z"}],
            "now":"2026-10-31T00:00:00.000Z"}"""))
        val r = client.getWords("sess.tok", null) as SyncResult.Ok
        assertEquals(listOf(
            SyncWord("glorbix", "en", 4, "typed", false, "2026-10-30T00:00:00Z"),
            SyncWord("vandelop", "id", 1, "swiftkey", true, "2026-10-30T00:00:01Z"),
        ), r.value.words)
        assertEquals("2026-10-31T00:00:00.000Z", r.value.now)
        take("GET", "/api/sync/words")

        server.enqueue(json(200, """{"words":[],"now":"2026-10-31T06:00:00.000Z"}"""))
        client.getWords("sess.tok", "2026-10-31T00:00:00.000Z")
        take("GET", "/api/sync/words?since=2026-10-31T00%3A00%3A00.000Z")
    }

    @Test fun postWords_bodyAndLimit() = runBlocking<Unit> {
        server.enqueue(json(200, """{"accepted":2,"now":"2026-10-31T00:00:00.000Z"}"""))
        val words = listOf(
            SyncWord("snorfle", "en", 3, updatedAt = "2026-10-31T00:00:00Z"),
            SyncWord("blimtak", "id", 0, deleted = true, updatedAt = "2026-10-31T00:00:00Z"),
        )
        assertEquals(SyncResult.Ok("2026-10-31T00:00:00.000Z"), client.postWords("sess.tok", words))
        val b = Json.parseToJsonElement(take("POST", "/api/sync/words").body.readUtf8()).jsonObject["words"]!!.jsonArray
        assertEquals(2, b.size)
        val first = b[0].jsonObject
        assertEquals("snorfle", first["word"]!!.jsonPrimitive.content)
        assertEquals("en", first["locale"]!!.jsonPrimitive.content)
        assertEquals(3, first["count"]!!.jsonPrimitive.int)
        assertEquals("typed", first["source"]!!.jsonPrimitive.content)
        assertEquals(false, first["deleted"]!!.jsonPrimitive.boolean)
        assertEquals(true, b[1].jsonObject["deleted"]!!.jsonPrimitive.boolean)

        val tooMany = List(SyncClient.MAX_WORDS_PER_CALL + 1) { SyncWord("w$it", "en", 1, updatedAt = "x") }
        assertTrue(runCatching { client.postWords("sess.tok", tooMany) }.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun backups_listUploadDownloadDelete() = runBlocking<Unit> {
        server.enqueue(json(200, """[{"id":"b2","createdAt":"2026-10-31T02:00:00.000Z","deviceName":"Pixel 8","kind":"auto","sizeBytes":123456},
            {"id":"b1","createdAt":"2026-10-30T02:00:00.000Z","deviceName":"Pixel 8","kind":"manual","sizeBytes":99}]"""))
        val list = client.listBackups("sess.tok") as SyncResult.Ok
        assertEquals(BackupInfo("b2", "2026-10-31T02:00:00.000Z", "Pixel 8", "auto", 123456), list.value[0])
        assertEquals(2, list.value.size)
        take("GET", "/api/backups")

        val zip = byteArrayOf(0x50, 0x4b, 3, 4, 9, 9)
        server.enqueue(json(200, """{"id":"b3"}"""))
        assertEquals(SyncResult.Ok("b3"), client.uploadBackup("sess.tok", zip, "dev-1", "Pixel 8 Pro", "manual"))
        val up = take("POST", "/api/backups")
        assertEquals("application/zip", up.getHeader("Content-Type"))
        assertEquals("dev-1", up.getHeader("X-Device-Id"))
        assertEquals("Pixel 8 Pro", up.getHeader("X-Device-Name"))
        assertEquals("manual", up.getHeader("X-Backup-Kind"))
        assertArrayEquals(zip, up.body.readByteArray())

        server.enqueue(MockResponse().setHeader("Content-Type", "application/zip")
            .setHeader("X-Backup-Sha256", SyncClient.sha256(zip)).setBody(Buffer().write(zip)))
        assertArrayEquals(zip, (client.downloadBackup("sess.tok", "b3") as SyncResult.Ok).value)
        take("GET", "/api/backups/b3")
        // a download that doesn't match its sha256 is refused
        server.enqueue(MockResponse().setHeader("Content-Type", "application/zip")
            .setHeader("X-Backup-Sha256", "00".repeat(32)).setBody(Buffer().write(zip)))
        assertTrue(client.downloadBackup("sess.tok", "b3") is SyncResult.Failed)
        take("GET", "/api/backups/b3")

        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(SyncResult.Ok(Unit), client.deleteBackup("sess.tok", "b3"))
        take("DELETE", "/api/backups/b3")
    }

    @Test fun errors() = runBlocking<Unit> {
        server.enqueue(json(401, """{"error":"unauthorized"}"""))
        assertEquals(SyncResult.SignedOut, client.getSettings("sess.tok"))
        server.enqueue(json(503, "{}"))
        assertEquals(SyncResult.Unavailable, client.getWords("sess.tok", null))
        server.enqueue(json(413, """{"message":"Backup over 20 MB"}"""))
        assertEquals(SyncResult.Failed(413, "Backup over 20 MB"), client.uploadBackup("sess.tok", ByteArray(1), "d", "n", "auto"))
        server.shutdown()
        assertEquals(SyncResult.Unavailable, client.listBackups("sess.tok"))
    }

    @Test fun nonAsciiDeviceNameIsCleaned() {
        assertEquals("Galaxy S24", SyncClient.headerSafe("Galaxy S24 ✨"))
        assertEquals("Android", SyncClient.headerSafe("✨"))
    }

    @Test fun wordsNeverInToString() {
        assertTrue("glorbix" !in SyncWord("glorbix", "en", 1, updatedAt = "x").toString())
    }
}
