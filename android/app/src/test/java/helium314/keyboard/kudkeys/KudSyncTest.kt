// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.content.Context
import com.kudcrafts.kudkeys.voice.SyncClient
import com.kudcrafts.kudkeys.voice.VoiceSettings
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.kudkeys.sync.KudLearnedWords
import helium314.keyboard.kudkeys.sync.KudSync
import helium314.keyboard.kudkeys.sync.KudSyncOutcome
import helium314.keyboard.kudkeys.sync.KudSyncStore
import helium314.keyboard.kudkeys.sync.KudSyncedDicts
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.preferences.writeBackupZip
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sync, backup and restore (KudSync) against a MockWebServer that speaks docs/v0.4-spec.md. The typed words and
 * the synced dictionaries are fakes here (the native dictionary code is KudSyncedDictionaryTest's). Made-up words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class KudSyncTest {
    private val ime = Robolectric.setupService(LatinIME::class.java)
    private val ctx: Context = ime.applicationContext
    private val prefs = ctx.prefs()
    private val voice = VoiceSettings(ctx.getSharedPreferences(VoiceSettings.PREFS, Context.MODE_PRIVATE))
    private val server = MockWebServer()
    private val requests = mutableListOf<Pair<RecordedRequest, ByteArray>>()

    /** What the fake server holds and answers. */
    private var serverSettings: String? = null
    private var mergedSettings: String? = null
    private var serverWords = """{"words":[],"now":"2026-10-31T00:00:00.000Z"}"""
    private var status = 200
    private var backupZip = ByteArray(0)

    private var typed: Map<String, Map<String, Int>>? = emptyMap()
    private val rebuilt = mutableListOf<Pair<Set<String>, Map<String, Map<String, Int>>>>()
    private var signedOutCalls = 0
    private var now = 1_790_000_000_000L

    private val store = KudSyncStore(File(ctx.cacheDir, "sync-test").also { it.deleteRecursively() })
    private val sync = KudSync(
        ctx, voice, SyncClient({ voice.apiBase }), store,
        learned = KudLearnedWords { typed },
        dicts = KudSyncedDicts { langs, mirror -> rebuilt += langs to mirror },
        prefs = prefs, clock = { now }, onSignedOut = { signedOutCalls++ },
    )

    init {
        prefs.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx) // en-US with id as the secondary language: langs en, id
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readByteArray()
                synchronized(requests) { requests += request to body }
                if (status != 200) return json(status, """{"error":"x"}""")
                val path = request.path!!
                return when {
                    path == "/api/sync/settings" && request.method == "GET" ->
                        serverSettings?.let { json(200, it) } ?: json(404, """{"error":"not found"}""")
                    path == "/api/sync/settings" && request.method == "PUT" -> json(200, mergedSettings ?: run {
                        val b = Json.parseToJsonElement(String(body)).jsonObject
                        """{"prefs":${b["prefs"]},"updatedAt":${b["updatedAt"]}}"""
                    })
                    path.startsWith("/api/sync/words") && request.method == "GET" -> json(200, serverWords)
                    path == "/api/sync/words" -> json(200, """{"accepted":1,"now":"2026-10-31T01:00:00.000Z"}""")
                    path == "/api/backups" && request.method == "POST" -> json(200, """{"id":"b9"}""")
                    path == "/api/backups" -> json(200, """[{"id":"b1","createdAt":"2026-10-30T02:00:00.000Z","deviceName":"Pixel 8","kind":"auto","sizeBytes":2048}]""")
                    path == "/api/backups/b1" && request.method == "GET" ->
                        MockResponse().setHeader("Content-Type", "application/zip").setBody(Buffer().write(backupZip))
                    path == "/api/backups/b1" -> MockResponse().setResponseCode(204)
                    else -> json(404, "{}")
                }
            }
        }
        server.start()
        voice.serverUrl = server.url("/v1/stream").toString().replace("http://", "ws://")
        voice.signedIn("sess.SECRET-TOKEN", "Test", "test@example.com")
    }

    @After fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun calls() = synchronized(requests) { requests.map { "${it.first.method} ${it.first.path}" } }
    private fun bodies(method: String, pathPrefix: String) = synchronized(requests) {
        requests.filter { it.first.method == method && it.first.path!!.startsWith(pathPrefix) }.map { it.second }
    }
    private fun postedWords(): List<JsonObject> = bodies("POST", "/api/sync/words").flatMap {
        Json.parseToJsonElement(String(it)).jsonObject["words"]!!.jsonArray.map { w -> w.jsonObject }
    }

    @Test fun firstSyncAfterSignIn_pullsThenPushes() = runBlocking<Unit> {
        prefs.edit().putBoolean("show_hints", true).putInt("key_longpress_timeout", 450).commit()
        serverSettings = """{"prefs":{"boolean":{"show_hints":false},"int":{"key_longpress_timeout":300}},"updatedAt":"2026-10-30T08:00:00.000Z"}"""
        serverWords = """{"words":[
            {"word":"glorbix","locale":"en","count":4,"source":"swiftkey","deleted":false,"updatedAt":"2026-10-30T00:00:00Z"},
            {"word":"vandelop","locale":"id","count":1,"source":"manual","deleted":false,"updatedAt":"2026-10-30T00:00:00Z"},
            {"word":"zuntrel","locale":"en","count":2,"source":"typed","deleted":true,"updatedAt":"2026-10-30T00:00:00Z"}],
            "now":"2026-10-31T00:00:00.000Z"}"""
        typed = mapOf("en" to mapOf("frabjous" to 3), "id" to mapOf("klimpang" to 1))

        assertEquals(KudSyncOutcome.Done, sync.sync())

        assertEquals(listOf("GET /api/sync/settings", "PUT /api/sync/settings", "GET /api/sync/words", "POST /api/sync/words"), calls())
        // the server's settings came first
        assertFalse(prefs.getBoolean("show_hints", true))
        assertEquals(300, prefs.getInt("key_longpress_timeout", 0))
        // all words pulled into the mirror (the deleted one left out) and the dictionaries rebuilt for both langs
        assertEquals(mapOf("en" to mapOf("glorbix" to 4), "id" to mapOf("vandelop" to 1)), store.mirror)
        assertEquals(setOf("en", "id"), rebuilt.last().first)
        // the typed words went up with their counts
        val sent = postedWords().associate { it["word"]!!.jsonPrimitive.content to it }
        assertEquals(setOf("frabjous", "klimpang"), sent.keys)
        assertEquals(3, sent["frabjous"]!!["count"]!!.jsonPrimitive.int)
        assertEquals("en", sent["frabjous"]!!["locale"]!!.jsonPrimitive.content)
        assertEquals("typed", sent["frabjous"]!!["source"]!!.jsonPrimitive.content)
        val st = store.state
        assertTrue(st.initialDone)
        assertEquals(now, st.lastSyncAt)
        assertEquals("2026-10-31T00:00:00.000Z", st.wordsSince)
        assertNull(st.lastError)
    }

    @Test fun incrementalSync_pushesOnlyChangedWords_pullsSince() = runBlocking<Unit> {
        typed = mapOf("en" to mapOf("frabjous" to 3, "snorfle" to 1))
        assertEquals(KudSyncOutcome.Done, sync.sync())
        requests.clear()

        typed = mapOf("en" to mapOf("frabjous" to 5, "snorfle" to 1, "quibbitz" to 1))
        serverWords = """{"words":[{"word":"blimtak","locale":"en","count":2,"source":"typed","deleted":false,"updatedAt":"x"}],"now":"2026-10-31T06:00:00.000Z"}"""
        now += 3_600_000
        assertEquals(KudSyncOutcome.Done, sync.sync())

        // no settings pull after the first sync; words since the last pull
        assertEquals(listOf("PUT /api/sync/settings", "GET /api/sync/words?since=2026-10-31T00%3A00%3A00.000Z", "POST /api/sync/words"), calls())
        assertEquals(mapOf("frabjous" to 5, "quibbitz" to 1), postedWords().associate { it["word"]!!.jsonPrimitive.content to it["count"]!!.jsonPrimitive.int })
        assertEquals(setOf("en"), rebuilt.last().first)
        assertEquals(mapOf("blimtak" to 2), store.mirror["en"])

        // nothing changed: nothing posted
        requests.clear()
        assertEquals(KudSyncOutcome.Done, sync.sync())
        assertTrue(bodies("POST", "/api/sync/words").isEmpty())
    }

    @Test fun pushGoesInBatchesOf5000() = runBlocking<Unit> {
        typed = mapOf("en" to (0 until 12_000).associate { "zz${it}word" to 1 })
        assertEquals(KudSyncOutcome.Done, sync.sync())
        assertEquals(listOf(5000, 5000, 2000), bodies("POST", "/api/sync/words").map {
            Json.parseToJsonElement(String(it)).jsonObject["words"]!!.jsonArray.size
        })
    }

    @Test fun removedSuggestion_tombstoneAndDroppedFromSyncedDict() = runBlocking<Unit> {
        serverWords = """{"words":[{"word":"glorbix","locale":"en","count":4,"source":"swiftkey","deleted":false,"updatedAt":"x"},
            {"word":"plonkett","locale":"en","count":1,"source":"swiftkey","deleted":false,"updatedAt":"x"}],"now":"2026-10-31T00:00:00.000Z"}"""
        assertEquals(KudSyncOutcome.Done, sync.sync())
        rebuilt.clear(); requests.clear()

        sync.recordRemoved("glorbix")
        // gone from the synced dictionary at once
        assertEquals(setOf("en"), rebuilt.single().first)
        assertEquals(mapOf("plonkett" to 1), rebuilt.single().second["en"])
        assertEquals(2, store.tombstones.size) // en and id

        serverWords = """{"words":[],"now":"2026-10-31T02:00:00.000Z"}"""
        assertEquals(KudSyncOutcome.Done, sync.sync())
        val tombs = postedWords().filter { it["deleted"]!!.jsonPrimitive.boolean }
        assertEquals(setOf("en", "id"), tombs.map { it["locale"]!!.jsonPrimitive.content }.toSet())
        assertTrue(tombs.all { it["word"]!!.jsonPrimitive.content == "glorbix" && it["count"]!!.jsonPrimitive.int == 0 })
        assertTrue(store.tombstones.isEmpty())
    }

    @Test fun removedSuggestionWhileSignedOut_recordsNothing() {
        voice.signOut()
        sync.recordRemoved("glorbix")
        assertTrue(store.tombstones.isEmpty())
    }

    @Test fun settingsExclusions_noSecretsDraftOrDeviceSizesLeave() = runBlocking<Unit> {
        prefs.edit()
            .putBoolean("show_hints", true)
            .putFloat("keyboard_height_scale_portrait", 1.3f)
            .putFloat("bottom_padding_scale_landscape", 0.5f)
            .putInt("version_code", 77)
            .putString("voice_token", "legacy.SECRET-PREF")
            .putString("recent_emojis", "x")
            .commit()
        voice.token = "legacy.SECRET-VOICE"
        val draftUrl = voice.serverUrl
        File(ctx.filesDir, PrefsDraftStore.DRAFT_FILE).writeText("""{"text":"DRAFT-SENTINEL"}""")
        ctx.getSharedPreferences(PrefsDraftStore.PREFS, Context.MODE_PRIVATE).edit().putString("x", "DRAFT-SENTINEL").commit()

        assertEquals(KudSyncOutcome.Done, sync.sync())
        assertEquals(KudSyncOutcome.Done, sync.backup("manual"))

        val put = Json.parseToJsonElement(String(bodies("PUT", "/api/sync/settings").single())).jsonObject
        val sentKeys = put["prefs"]!!.jsonObject.values.flatMap { it.jsonObject.keys }.toSet()
        assertTrue("show_hints" in sentKeys)
        for (k in listOf("keyboard_height_scale_portrait", "bottom_padding_scale_landscape", "version_code", "voice_token", "recent_emojis"))
            assertFalse(k in sentKeys, "$k was sent")
        assertEquals(store.state.deviceId, put["deviceId"]!!.jsonPrimitive.content)

        // nothing secret in any body (the zip unpacked)
        val texts = synchronized(requests) { requests.map { (r, b) -> if (r.path == "/api/backups") unzip(b).values.joinToString("\n") else String(b) } }
        for (secret in listOf("SECRET-TOKEN", "SECRET-PREF", "SECRET-VOICE", "DRAFT-SENTINEL", draftUrl, "/v1/stream"))
            assertTrue(texts.none { secret in it }, "$secret left the device")
    }

    @Test fun mergedSettingsFromTheServerAreApplied() = runBlocking<Unit> {
        prefs.edit().putBoolean("show_hints", true).commit()
        assertEquals(KudSyncOutcome.Done, sync.sync())
        mergedSettings = """{"prefs":{"boolean":{"show_hints":false},"string":{"space_bar_text":"glorp"}},"updatedAt":"2026-10-31T09:00:00.000Z"}"""
        assertEquals(KudSyncOutcome.Done, sync.sync())
        assertFalse(prefs.getBoolean("show_hints", true))
        assertEquals("glorp", prefs.getString("space_bar_text", null))
        // a flat {key: value} answer is read too, typed like the local pref
        mergedSettings = """{"prefs":{"show_hints":true},"updatedAt":"2026-10-31T10:00:00.000Z"}"""
        assertEquals(KudSyncOutcome.Done, sync.sync())
        assertTrue(prefs.getBoolean("show_hints", false))
    }

    @Test fun errors_401SignsOut_5xxRetries_signedOutSendsNothing() = runBlocking<Unit> {
        status = 503
        assertEquals(KudSyncOutcome.Retry, sync.sync())
        assertEquals(KudSync.SYNC_ERROR, store.state.lastError)
        assertTrue(voice.isSignedIn)

        status = 401
        assertEquals(KudSyncOutcome.SignedOut, sync.sync())
        assertFalse(voice.isSignedIn)
        assertTrue(voice.wasSignedOut)
        assertEquals(1, signedOutCalls)

        requests.clear()
        assertEquals(KudSyncOutcome.NotSignedIn, sync.sync())
        assertEquals(KudSyncOutcome.NotSignedIn, sync.backup("manual"))
        assertTrue(requests.isEmpty())
    }

    @Test fun backup_zipHasHistoryDictsAndPrefs_notSecrets() = runBlocking<Unit> {
        prefs.edit().putBoolean("show_hints", true).putString("account_session_token", "SECRET-IN-PREFS").commit()
        writeFile("UserHistoryDictionary.en-US/UserHistoryDictionary.en-US.body", "HISTORY-A")
        writeFile("dicts/en-US/synced_user.dict", "SYNCED-A")
        File(ctx.filesDir, PrefsDraftStore.DRAFT_FILE).writeText("DRAFT-SENTINEL")

        assertEquals(KudSyncOutcome.Done, sync.backup("manual"))
        val up = synchronized(requests) { requests.single { it.first.path == "/api/backups" } }
        assertEquals("manual", up.first.getHeader("X-Backup-Kind"))
        assertEquals(store.state.deviceId, up.first.getHeader("X-Device-Id"))
        assertNotNull(up.first.getHeader("X-Device-Name"))
        val entries = unzip(up.second)
        assertEquals("HISTORY-A", entries["UserHistoryDictionary.en-US/UserHistoryDictionary.en-US.body"])
        assertEquals("SYNCED-A", entries["dicts/en-US/synced_user.dict"])
        assertTrue("\"show_hints\":true" in entries["preferences.json"]!!)
        assertFalse(entries.keys.any { "draft" in it })
        assertFalse(entries.values.any { "SECRET" in it })
        assertEquals(now, store.state.lastBackupAt)
    }

    @Test fun restore_appliesPrefsAndDictionaries_keepsDraft_thenSyncs() = runBlocking<Unit> {
        // the backup: show_hints on, history A
        prefs.edit().putBoolean("show_hints", true).commit()
        writeFile("UserHistoryDictionary.en-US/UserHistoryDictionary.en-US.body", "HISTORY-A")
        writeFile("dicts/en-US/myaddon_user.dict", "ADDON-A")
        backupZip = ByteArrayOutputStream().also { writeBackupZip(ctx, it) }.toByteArray()
        assertEquals(KudSyncOutcome.Done, sync.sync())
        // then the phone moves on
        prefs.edit().putBoolean("show_hints", false).commit()
        writeFile("UserHistoryDictionary.en-US/UserHistoryDictionary.en-US.body", "HISTORY-B")
        writeFile("UserHistoryDictionary.id/UserHistoryDictionary.id.body", "HISTORY-ID-B")
        File(ctx.filesDir, "dicts/en-US/myaddon_user.dict").delete()
        File(ctx.filesDir, PrefsDraftStore.DRAFT_FILE).writeText("DRAFT-KEPT")
        requests.clear(); rebuilt.clear()

        assertEquals(KudSyncOutcome.Done, sync.restore("b1"))

        assertTrue(prefs.getBoolean("show_hints", false))
        assertEquals("HISTORY-A", File(ctx.filesDir, "UserHistoryDictionary.en-US/UserHistoryDictionary.en-US.body").readText())
        assertFalse(File(ctx.filesDir, "UserHistoryDictionary.id/UserHistoryDictionary.id.body").exists()) // exact state
        assertEquals("ADDON-A", File(ctx.filesDir, "dicts/en-US/myaddon_user.dict").readText())
        assertEquals("DRAFT-KEPT", File(ctx.filesDir, PrefsDraftStore.DRAFT_FILE).readText())
        // then re-synced: the restored settings go up as the newest
        assertEquals("GET /api/backups/b1", calls().first())
        assertTrue("PUT /api/sync/settings" in calls())
        val put = Json.parseToJsonElement(String(bodies("PUT", "/api/sync/settings").single())).jsonObject
        assertTrue(put["prefs"]!!.jsonObject["boolean"]!!.jsonObject["show_hints"]!!.jsonPrimitive.boolean)
        assertTrue(rebuilt.isNotEmpty())
    }

    @Test fun restore_badZip_keepsEverything() = runBlocking<Unit> {
        prefs.edit().putBoolean("show_hints", false).commit()
        writeFile("UserHistoryDictionary.en-US/UserHistoryDictionary.en-US.body", "HISTORY-B")
        backupZip = "not a zip".toByteArray()
        assertEquals(KudSyncOutcome.Failed(KudSync.RESTORE_ERROR), sync.restore("b1"))
        assertFalse(prefs.getBoolean("show_hints", true))
        assertEquals("HISTORY-B", File(ctx.filesDir, "UserHistoryDictionary.en-US/UserHistoryDictionary.en-US.body").readText())
    }

    @Test fun listAndDeleteBackups() = runBlocking<Unit> {
        val list = sync.listBackups() as com.kudcrafts.kudkeys.voice.SyncResult.Ok
        assertEquals(listOf("b1"), list.value.map { it.id })
        assertEquals(KudSyncOutcome.Done, sync.deleteBackup("b1"))
        assertEquals(listOf("GET /api/backups", "DELETE /api/backups/b1"), calls())
    }

    private fun writeFile(rel: String, text: String) = File(ctx.filesDir, rel).also { it.parentFile!!.mkdirs() }.writeText(text)

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = String(z.readBytes())
            }
        }
        return out
    }
}
