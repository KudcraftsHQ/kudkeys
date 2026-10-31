package com.kudcrafts.kudkeys.voice

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Process

/** Where to connect and with what. [isSession]: [token] is an account's session token, not a legacy one. */
data class VoiceEndpoint(
    val url: String,
    val token: String,
    val isSession: Boolean = false,
    val userAgent: String? = null,
) {
    override fun toString() = "VoiceEndpoint(url=$url, token=***, isSession=$isSession)"
}

/**
 * Settings → Voice and the account: server URL, the signed-in account (session token, name, email) and the
 * legacy token, in app-private SharedPreferences that backup excludes. The settings screens write these;
 * [VoiceClient] reads them at every [VoiceClient.start]. Tokens never ship in the APK and are never logged.
 */
class VoiceSettings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** Server URL; blank resets to [DEFAULT_URL]. */
    var serverUrl: String
        get() = prefs.getString(KEY_URL, null)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_URL
        set(value) { prefs.edit().putString(KEY_URL, value.trim()).apply() }

    /** The account API's base, from the server URL: `wss://host/v1/stream` → `https://host`. */
    val apiBase: String get() = apiBaseOf(serverUrl)

    /** Legacy static token (Settings → Voice → Advanced). */
    var token: String
        get() = prefs.getString(KEY_TOKEN, null)?.trim() ?: ""
        set(value) { prefs.edit().putString(KEY_TOKEN, value.trim()).apply() }

    /** The account's session token (`set-auth-token`), or "" when signed out. */
    val sessionToken: String get() = prefs.getString(KEY_SESSION, null)?.trim() ?: ""
    val accountName: String get() = prefs.getString(KEY_NAME, null) ?: ""
    val accountEmail: String get() = prefs.getString(KEY_EMAIL, null) ?: ""
    val isSignedIn: Boolean get() = sessionToken.isNotEmpty()
    /** True after the server rejected the session (401), until the next sign-in. */
    val wasSignedOut: Boolean get() = prefs.getBoolean(KEY_SIGNED_OUT, false)

    /** Signed in, or a legacy token set: voice can start. */
    val hasToken: Boolean get() = isSignedIn || token.isNotEmpty()

    fun signedIn(sessionToken: String, name: String, email: String) {
        prefs.edit().putString(KEY_SESSION, sessionToken.trim()).putString(KEY_NAME, name).putString(KEY_EMAIL, email)
            .putBoolean(KEY_SIGNED_OUT, false).commit()
    }

    fun updateAccount(name: String, email: String) {
        prefs.edit().putString(KEY_NAME, name).putString(KEY_EMAIL, email).apply()
    }

    /** Signed out on purpose: forget the account. */
    fun signOut() {
        prefs.edit().remove(KEY_SESSION).remove(KEY_NAME).remove(KEY_SIGNED_OUT).commit()
    }

    /** The server rejected the session: drop the token, keep the email to prefill sign-in. */
    fun markSignedOut() {
        prefs.edit().remove(KEY_SESSION).putBoolean(KEY_SIGNED_OUT, true).commit()
    }

    /** The session token when signed in, else the legacy token. */
    fun endpoint(): VoiceEndpoint {
        val session = sessionToken
        return if (session.isNotEmpty()) VoiceEndpoint(serverUrl, session, isSession = true, userAgent = userAgent)
        else VoiceEndpoint(serverUrl, token, userAgent = userAgent)
    }

    companion object {
        const val DEFAULT_URL = "wss://keys.kudcrafts.com/v1/stream"
        const val PREFS = "kudkeys_voice"
        const val KEY_URL = "voice_server_url"
        const val KEY_TOKEN = "voice_token"
        const val KEY_SESSION = "account_session_token"
        const val KEY_NAME = "account_name"
        const val KEY_EMAIL = "account_email"
        const val KEY_SIGNED_OUT = "account_signed_out"

        /** Set once by the app (it knows its version); sent on account calls and the stream upgrade. */
        @Volatile var userAgent: String? = null

        fun apiBaseOf(serverUrl: String): String {
            val u = serverUrl.trim()
            val scheme = when {
                u.startsWith("wss://", true) -> "https://"
                u.startsWith("ws://", true) -> "http://"
                u.startsWith("https://", true) -> "https://"
                u.startsWith("http://", true) -> "http://"
                else -> return "https://keys.kudcrafts.com"
            }
            val host = u.substringAfter("://").substringBefore('/').substringBefore('?')
            return scheme + host
        }
    }
}

/** True when the app holds RECORD_AUDIO. Asking for it is the caller's job. */
fun hasMicPermission(context: Context): Boolean =
    context.checkPermission(Manifest.permission.RECORD_AUDIO, Process.myPid(), Process.myUid()) ==
        PackageManager.PERMISSION_GRANTED
