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
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A word the model is unsure about: [start]..[end] are char offsets into the tidied text. */
data class TidyQuestion(val id: String, val original: String, val start: Int, val end: Int, val options: List<String>)

data class Tidied(val text: String, val questions: List<TidyQuestion>)

data class Fixed(val text: String, val changed: Boolean)

/** What a text call came back with. Never carries the request text. */
sealed interface TextResult<out T> {
    data class Ok<T>(val value: T) : TextResult<T>
    /** No session token on this phone: sign in from settings. Nothing was sent. */
    data object NotSignedIn : TextResult<Nothing>
    /** 401: the session is gone (the account is marked signed out). */
    data object SignedOut : TextResult<Nothing>
    /** 429. [retryAfterS] from Retry-After when given. */
    data class RateLimited(val retryAfterS: Int?) : TextResult<Nothing>
    /** 503 / other 5xx, timeout, refused connection: the model or server isn't answering. */
    data object Unavailable : TextResult<Nothing>
    /** DNS failed: almost always no internet. */
    data object NoNetwork : TextResult<Nothing>
    /** 400 or another answer we can't use. */
    data object Rejected : TextResult<Nothing>
}

/** The three text endpoints, as the keyboard uses them. A fake in tests. */
interface TextService {
    suspend fun tidy(text: String, private: Boolean): TextResult<Tidied>
    suspend fun fix(text: String, private: Boolean): TextResult<Fixed>
    suspend fun instruct(instruction: String, text: String, private: Boolean): TextResult<String>
}

/**
 * `POST /api/text/{tidy,fix,instruct}` (docs/v0.3-spec.md "Server contract"). Stateless: the caller passes the
 * session token. Every call is a suspend function on the IO dispatcher. Text is never logged.
 */
class TextClient(
    /** `https://keys.kudcrafts.com`, no trailing slash. */
    private val baseUrl: () -> String,
    private val http: OkHttpClient = defaultHttp,
    private val userAgent: () -> String? = { VoiceSettings.userAgent },
) {
    suspend fun tidy(token: String, text: String, private: Boolean): TextResult<Tidied> =
        post("/api/text/tidy", token, buildJsonObject {
            put("text", text.take(MAX_TIDY))
            if (private) put("private", true)
        }) { o ->
            val out = o["text"].str() ?: return@post null
            Tidied(out, parseQuestions(o["questions"], out))
        }

    suspend fun fix(token: String, text: String, private: Boolean): TextResult<Fixed> =
        post("/api/text/fix", token, buildJsonObject {
            put("text", text.take(MAX_FIX))
            if (private) put("private", true)
        }) { o ->
            val out = o["text"].str() ?: return@post null
            Fixed(out, (o["changed"] as? JsonPrimitive)?.booleanOrNull ?: (out != text))
        }

    suspend fun instruct(token: String, instruction: String, text: String, private: Boolean): TextResult<String> =
        post("/api/text/instruct", token, buildJsonObject {
            put("instruction", instruction.take(MAX_INSTRUCTION))
            put("text", text.take(MAX_TIDY))
            if (private) put("private", true)
        }) { o -> o["text"].str() }

    private suspend fun <T> post(
        path: String,
        token: String,
        body: JsonObject,
        parse: (JsonObject) -> T?,
    ): TextResult<T> = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(baseUrl().trimEnd('/') + path)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .apply { userAgent()?.let { header("User-Agent", it) } }
                .post(body.toString().toRequestBody(JSON))
                .build()
        } catch (_: IllegalArgumentException) {
            return@withContext TextResult.Rejected
        }
        try {
            http.newCall(request).execute().use { r ->
                when {
                    r.isSuccessful -> {
                        val o = runCatching { Json.parseToJsonElement(r.body?.string().orEmpty()) }.getOrNull() as? JsonObject
                        o?.let(parse)?.let { TextResult.Ok(it) } ?: TextResult.Rejected
                    }
                    r.code == 401 -> TextResult.SignedOut
                    r.code == 429 -> TextResult.RateLimited(r.header("Retry-After")?.trim()?.toIntOrNull())
                    r.code >= 500 -> TextResult.Unavailable
                    else -> TextResult.Rejected
                }
            }
        } catch (_: UnknownHostException) {
            TextResult.NoNetwork
        } catch (_: IOException) {
            TextResult.Unavailable
        }
    }

    companion object {
        const val MAX_TIDY = 20_000
        const val MAX_FIX = 4_000
        const val MAX_INSTRUCTION = 2_000
        private val JSON = "application/json".toMediaType()

        private val defaultHttp: OkHttpClient by lazy {
            // a model call can take a while on a long ramble
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
        }

        /**
         * Keeps the questions whose offsets fit [text], with their options trimmed, de-duplicated and not equal to
         * the word itself; sorted by position, overlapping ones dropped.
         */
        fun parseQuestions(e: JsonElement?, text: String): List<TidyQuestion> {
            val out = mutableListOf<TidyQuestion>()
            for (q in (e as? JsonArray).orEmpty()) {
                val o = q as? JsonObject ?: continue
                val start = (o["start"] as? JsonPrimitive)?.intOrNull ?: continue
                val end = (o["end"] as? JsonPrimitive)?.intOrNull ?: continue
                if (start < 0 || end <= start || end > text.length) continue
                val original = text.substring(start, end)
                val options = (o["options"] as? JsonArray).orEmpty().mapNotNull { it.str()?.trim() }
                    .filter { it.isNotEmpty() && it != original }.distinct().take(3)
                if (options.isEmpty()) continue
                out += TidyQuestion(o["id"].str() ?: "q${out.size}", original, start, end, options)
            }
            out.sortBy { it.start }
            var lastEnd = -1
            return out.filter { (it.start >= lastEnd).also { ok -> if (ok) lastEnd = it.end } }
        }

        private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull
    }
}

/**
 * [TextService] with the stored session token. No token → [TextResult.NotSignedIn] without a request; a 401 marks
 * the account signed out (the same rule as [Account]).
 */
class TextApi(
    private val settings: VoiceSettings,
    private val client: TextClient = TextClient({ settings.apiBase }),
) : TextService {
    override suspend fun tidy(text: String, private: Boolean) = authed { client.tidy(it, text, private) }
    override suspend fun fix(text: String, private: Boolean) = authed { client.fix(it, text, private) }
    override suspend fun instruct(instruction: String, text: String, private: Boolean) =
        authed { client.instruct(it, instruction, text, private) }

    private suspend fun <T> authed(block: suspend (String) -> TextResult<T>): TextResult<T> {
        val token = settings.sessionToken
        if (token.isEmpty()) return TextResult.NotSignedIn
        val r = block(token)
        if (r is TextResult.SignedOut) settings.markSignedOut()
        return r
    }
}
