package com.kudcrafts.kudkeys.voice

import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** `GET/PUT /api/sync/settings`: the prefs JSON and when it was last written (ISO 8601). */
data class SyncedSettings(val prefs: JsonObject, val updatedAt: String)

/** One row of `/api/sync/words`. [locale] is a language tag (`en`, `id`). */
data class SyncWord(
    val word: String,
    val locale: String,
    val count: Int,
    val source: String = SOURCE_TYPED,
    val deleted: Boolean = false,
    val updatedAt: String,
) {
    override fun toString() = "SyncWord(***, locale=$locale, count=$count, deleted=$deleted)" // never log words

    companion object {
        const val SOURCE_TYPED = "typed"
    }
}

data class WordsPage(val words: List<SyncWord>, val now: String)

/** One row of `GET /api/backups`. */
data class BackupInfo(val id: String, val createdAt: String, val deviceName: String, val kind: String, val sizeBytes: Long)

/** What a sync call came back with. Never carries settings values or words in its messages. */
sealed interface SyncResult<out T> {
    data class Ok<T>(val value: T) : SyncResult<T>
    /** 404 (no settings stored yet, or the backup is gone). */
    data object NotFound : SyncResult<Nothing>
    /** 401: the session is gone. */
    data object SignedOut : SyncResult<Nothing>
    /** DNS failed: almost always no internet. */
    data object NoNetwork : SyncResult<Nothing>
    /** 5xx, timeout, refused connection: try again later. */
    data object Unavailable : SyncResult<Nothing>
    /** 400/403/413 and other answers. */
    data class Failed(val code: Int, val message: String) : SyncResult<Nothing>
}

/**
 * The sync and backup endpoints of docs/v0.4-spec.md ("Server"), with the session token. Stateless; every call is
 * a suspend function on the IO dispatcher. Nothing it sends or receives is logged.
 */
class SyncClient(
    /** `https://keys.kudcrafts.com`, no trailing slash. */
    private val baseUrl: () -> String,
    private val http: OkHttpClient = defaultHttp,
    private val userAgent: () -> String? = { VoiceSettings.userAgent },
) {
    suspend fun getSettings(token: String): SyncResult<SyncedSettings> =
        call("GET", "/api/sync/settings", token, null) { parseSettings(it) }

    /** Sends this device's prefs; the answer is the merged result (last writer wins per key). */
    suspend fun putSettings(token: String, prefs: JsonObject, updatedAt: String, deviceId: String): SyncResult<SyncedSettings> {
        val body = buildJsonObject { put("prefs", prefs); put("updatedAt", updatedAt); put("deviceId", deviceId) }
        return call("PUT", "/api/sync/settings", token, body.toString().toRequestBody(JSON)) { parseSettings(it) }
    }

    /** Every word changed after [since] (all of them when null), deleted ones included. */
    suspend fun getWords(token: String, since: String?): SyncResult<WordsPage> {
        val path = "/api/sync/words" + (since?.let { "?since=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
        return call("GET", path, token, null) { r ->
            val o = parse(r) as? JsonObject ?: return@call bad()
            val words = (o["words"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseWord) }
            SyncResult.Ok(WordsPage(words, o["now"]?.str() ?: ""))
        }
    }

    /** One batch of at most [MAX_WORDS_PER_CALL] words. Returns the server's `now`. */
    suspend fun postWords(token: String, words: List<SyncWord>): SyncResult<String> {
        require(words.size <= MAX_WORDS_PER_CALL) { "at most $MAX_WORDS_PER_CALL words per call" }
        val body = buildJsonObject {
            putJsonArray("words") {
                for (w in words) add(buildJsonObject {
                    put("word", w.word); put("locale", w.locale); put("count", w.count); put("source", w.source)
                    put("deleted", w.deleted); put("updatedAt", w.updatedAt)
                })
            }
        }
        return call("POST", "/api/sync/words", token, body.toString().toRequestBody(JSON)) { r ->
            SyncResult.Ok((parse(r) as? JsonObject)?.get("now")?.str() ?: "")
        }
    }

    suspend fun listBackups(token: String): SyncResult<List<BackupInfo>> =
        call("GET", "/api/backups", token, null) { r ->
            val e = parse(r)
            val arr = e as? JsonArray ?: (e as? JsonObject)?.get("backups") as? JsonArray ?: return@call bad()
            SyncResult.Ok(arr.mapNotNull { x ->
                val o = x as? JsonObject ?: return@mapNotNull null
                BackupInfo(
                    id = o["id"]?.str() ?: return@mapNotNull null,
                    createdAt = o["createdAt"]?.str() ?: "",
                    deviceName = o["deviceName"]?.str() ?: "",
                    kind = o["kind"]?.str() ?: "",
                    sizeBytes = (o["sizeBytes"] as? JsonPrimitive)?.longOrNull ?: 0L,
                )
            })
        }

    /** Uploads one backup zip. [kind] is `auto` or `manual`. Returns its id. */
    suspend fun uploadBackup(token: String, zip: ByteArray, deviceId: String, deviceName: String, kind: String): SyncResult<String> =
        call("POST", "/api/backups", token, zip.toRequestBody(ZIP), headers = mapOf(
            "X-Device-Id" to deviceId, "X-Device-Name" to headerSafe(deviceName), "X-Backup-Kind" to kind,
        )) { r -> SyncResult.Ok((parse(r) as? JsonObject)?.get("id")?.str() ?: "") }

    suspend fun downloadBackup(token: String, id: String): SyncResult<ByteArray> =
        call("GET", "/api/backups/" + enc(id), token, null, accept = "application/zip") { r ->
            SyncResult.Ok(r.body?.bytes() ?: ByteArray(0))
        }

    suspend fun deleteBackup(token: String, id: String): SyncResult<Unit> =
        call("DELETE", "/api/backups/" + enc(id), token, null) { SyncResult.Ok(Unit) }

    // --- internals ---------------------------------------------------------------------------

    private suspend fun <T> call(
        method: String,
        path: String,
        token: String,
        body: RequestBody?,
        headers: Map<String, String> = emptyMap(),
        accept: String = "application/json",
        ok: (Response) -> SyncResult<T>,
    ): SyncResult<T> = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(baseUrl().trimEnd('/') + path).apply {
                header("Authorization", "Bearer $token")
                userAgent()?.let { header("User-Agent", it) }
                header("Accept", accept)
                headers.forEach { (k, v) -> header(k, v) }
                method(method, body)
            }.build()
        } catch (_: IllegalArgumentException) {
            return@withContext SyncResult.Failed(0, "Check the server URL in Settings → Voice")
        }
        try {
            http.newCall(request).execute().use { r ->
                when {
                    r.isSuccessful -> ok(r)
                    r.code == 401 -> SyncResult.SignedOut
                    r.code == 404 -> SyncResult.NotFound
                    r.code >= 500 || r.code == 429 -> SyncResult.Unavailable
                    else -> SyncResult.Failed(r.code, errorMessage(r) ?: "HTTP ${r.code}")
                }
            }
        } catch (_: UnknownHostException) {
            SyncResult.NoNetwork
        } catch (_: IOException) {
            SyncResult.Unavailable
        }
    }

    companion object {
        const val MAX_WORDS_PER_CALL = 5000
        private val JSON = "application/json".toMediaType()
        private val ZIP = "application/zip".toMediaType()

        private val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS).build()
        }

        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** OkHttp refuses non-ASCII header values: keep printable ASCII. */
        fun headerSafe(s: String) = s.filter { it in ' '..'~' }.trim().ifEmpty { "Android" }

        private fun parse(r: Response): JsonElement? =
            runCatching { Json.parseToJsonElement(r.body?.string().orEmpty()) }.getOrNull()

        private fun bad() = SyncResult.Failed(0, "Bad answer")

        private fun parseSettings(r: Response): SyncResult<SyncedSettings> {
            val o = parse(r) as? JsonObject ?: return bad()
            val prefs = o["prefs"] as? JsonObject ?: JsonObject(emptyMap())
            return SyncResult.Ok(SyncedSettings(prefs, o["updatedAt"]?.str() ?: ""))
        }

        private fun parseWord(o: JsonObject): SyncWord? {
            val word = o["word"]?.str()?.takeIf { it.isNotEmpty() } ?: return null
            return SyncWord(
                word = word,
                locale = o["locale"]?.str() ?: "",
                count = (o["count"] as? JsonPrimitive)?.intOrNull ?: 0,
                source = o["source"]?.str() ?: "",
                deleted = (o["deleted"] as? JsonPrimitive)?.booleanOrNull ?: false,
                updatedAt = o["updatedAt"]?.str() ?: "",
            )
        }

        private fun errorMessage(r: Response): String? {
            val o = parse(r) as? JsonObject ?: return null
            return o["message"]?.str() ?: o["error"]?.str()
        }

        private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    }
}
