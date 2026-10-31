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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** `GET /api/me`. */
data class Profile(
    val id: String,
    val email: String,
    val name: String,
    val voiceKeywords: List<String>,
    val languages: List<String>,
)

/** One row of `GET /api/me/usage`. */
data class UsageSession(val startedAt: String, val audioSeconds: Double, val finals: Int, val outcome: String)
data class UsagePeriod(val sessions: Int, val audioMinutes: Double)
data class Usage(val week: UsagePeriod, val month: UsagePeriod, val sessions: List<UsageSession>)

/** What an account call came back with. */
sealed interface AccountResult<out T> {
    data class Ok<T>(val value: T) : AccountResult<T>
    /** Sign-in: 401 `INVALID_EMAIL_OR_PASSWORD`. */
    data object WrongPassword : AccountResult<Nothing>
    /** 401 on anything else: the session is gone, sign in again. */
    data object SignedOut : AccountResult<Nothing>
    /** 403: the email isn't on the allowlist (any more). */
    data object NotAllowed : AccountResult<Nothing>
    /** DNS failed: almost always no internet. */
    data object NoNetwork : AccountResult<Nothing>
    /** Connect refused, timeout, TLS, or a 5xx: the server isn't answering. */
    data object Unreachable : AccountResult<Nothing>
    /** 400 and other answers: the server's message. */
    data class Failed(val message: String) : AccountResult<Nothing>
}

/**
 * The account API of server/README.md "API for the app": sign in/out, profile and usage. Bearer auth only,
 * no cookies. Every call is a suspend function on the IO dispatcher.
 */
class AccountClient(
    /** `https://keys.kudcrafts.com`, no trailing slash. */
    private val baseUrl: () -> String,
    private val http: OkHttpClient = defaultHttp,
    private val userAgent: () -> String? = { VoiceSettings.userAgent },
) {
    /** The token to send, and the [SignedIn] result of a sign-in. */
    data class SignedIn(val token: String, val name: String, val email: String)

    suspend fun signIn(email: String, password: String): AccountResult<SignedIn> {
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }
        return call("POST", "/api/auth/sign-in/email", null, body, signIn = true) { r, json ->
            val token = r.header("set-auth-token")?.takeIf { it.isNotBlank() }
                ?: json?.obj("token")?.str()
                ?: return@call AccountResult.Failed("No session token in the answer")
            val user = json?.obj("user")?.jsonObject
            AccountResult.Ok(SignedIn(token, user?.get("name")?.str() ?: "", user?.get("email")?.str() ?: email.trim()))
        }
    }

    /**
     * Revokes [token] on the server. Sends `Origin: <server base>`: Better Auth turns the bearer token into a
     * cookie, and then refuses a cookie write that names no trusted origin.
     */
    suspend fun signOut(token: String): AccountResult<Unit> =
        call("POST", "/api/auth/sign-out", token, JsonObject(emptyMap()), sendOrigin = true) { _, _ -> AccountResult.Ok(Unit) }

    suspend fun me(token: String): AccountResult<Profile> =
        call("GET", "/api/me", token, null) { _, json -> parseProfile(json) }

    /** `PATCH /api/me` with only the fields given. */
    suspend fun updateMe(
        token: String,
        name: String? = null,
        voiceKeywords: List<String>? = null,
        languages: List<String>? = null,
    ): AccountResult<Profile> =
        call("PATCH", "/api/me", token, patchBody(name, voiceKeywords, languages)) { _, json -> parseProfile(json) }

    suspend fun usage(token: String): AccountResult<Usage> =
        call("GET", "/api/me/usage", token, null) { _, json ->
            val o = json?.jsonObject ?: return@call AccountResult.Failed("Bad answer")
            fun period(k: String) = o[k]?.jsonObject.let {
                UsagePeriod(it?.get("sessions")?.int() ?: 0, it?.get("audioMinutes")?.dbl() ?: 0.0)
            }
            val sessions = (o["sessions"] as? JsonArray).orEmpty().map { e ->
                val s = e.jsonObject
                UsageSession(
                    s["startedAt"]?.str() ?: "", s["audioSeconds"]?.dbl() ?: 0.0,
                    s["finals"]?.int() ?: 0, s["outcome"]?.str() ?: "",
                )
            }
            AccountResult.Ok(Usage(period("week"), period("month"), sessions))
        }

    // --- internals ---------------------------------------------------------------------------

    private suspend fun <T> call(
        method: String,
        path: String,
        token: String?,
        body: JsonObject?,
        signIn: Boolean = false,
        sendOrigin: Boolean = false,
        ok: (Response, JsonElement?) -> AccountResult<T>,
    ): AccountResult<T> = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(baseUrl().trimEnd('/') + path).apply {
                if (token != null) header("Authorization", "Bearer $token")
                if (sendOrigin) header("Origin", originOf(baseUrl()))
                userAgent()?.let { header("User-Agent", it) }
                header("Accept", "application/json")
                method(method, body?.toString()?.toRequestBody(JSON))
            }.build()
        } catch (_: IllegalArgumentException) {
            return@withContext AccountResult.Failed("Check the server URL in Settings → Voice")
        }
        try {
            http.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val json = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                when {
                    r.isSuccessful -> ok(r, json)
                    r.code == 401 && signIn -> AccountResult.WrongPassword
                    r.code == 401 -> AccountResult.SignedOut
                    r.code == 403 -> AccountResult.NotAllowed
                    r.code >= 500 -> AccountResult.Unreachable
                    else -> AccountResult.Failed(errorMessage(json) ?: "HTTP ${r.code}")
                }
            }
        } catch (_: UnknownHostException) {
            AccountResult.NoNetwork
        } catch (_: IOException) {
            AccountResult.Unreachable
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** `https://keys.kudcrafts.com/x` → `https://keys.kudcrafts.com` (the port only when it isn't the default). */
        fun originOf(base: String): String {
            val u = base.toHttpUrl()
            return HttpUrl.Builder().scheme(u.scheme).host(u.host).port(u.port).build().toString().trimEnd('/')
        }

        private val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        }

        /** The JSON a PATCH sends: only the fields given. */
        fun patchBody(name: String?, voiceKeywords: List<String>?, languages: List<String>?): JsonObject =
            buildJsonObject {
                if (name != null) put("name", name.trim())
                if (voiceKeywords != null) putJsonArray("voiceKeywords") { voiceKeywords.forEach { add(JsonPrimitive(it)) } }
                if (languages != null) putJsonArray("languages") { languages.forEach { add(JsonPrimitive(it)) } }
            }

        private fun parseProfile(json: JsonElement?): AccountResult<Profile> {
            val o = json as? JsonObject ?: return AccountResult.Failed("Bad answer")
            return AccountResult.Ok(
                Profile(
                    id = o["id"]?.str() ?: "",
                    email = o["email"]?.str() ?: "",
                    name = o["name"]?.str() ?: "",
                    voiceKeywords = (o["voiceKeywords"] as? JsonArray).orEmpty().mapNotNull { it.str() },
                    languages = (o["languages"] as? JsonArray).orEmpty().mapNotNull { it.str() },
                )
            )
        }

        private fun errorMessage(json: JsonElement?): String? {
            val o = json as? JsonObject ?: return null
            (o["issues"] as? JsonArray)?.firstOrNull()?.jsonObject?.let { i ->
                return listOfNotNull(i["path"]?.str(), i["message"]?.str()).joinToString(": ")
            }
            return o["message"]?.str() ?: o["error"]?.str()
        }

        private fun JsonElement.obj(key: String): JsonElement? = (this as? JsonObject)?.get(key)
        private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        private fun JsonElement.int(): Int? = (this as? JsonPrimitive)?.intOrNull
        private fun JsonElement.dbl(): Double? = (this as? JsonPrimitive)?.doubleOrNull
    }
}
