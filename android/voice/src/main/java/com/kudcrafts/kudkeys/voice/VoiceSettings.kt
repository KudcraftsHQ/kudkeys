package com.kudcrafts.kudkeys.voice

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Process

/** Where to connect and with what. */
data class VoiceEndpoint(val url: String, val token: String) {
    override fun toString() = "VoiceEndpoint(url=$url, token=***)"
}

/**
 * Settings → Voice: server URL and token, in app-private SharedPreferences. The settings screen
 * writes these; [VoiceClient] reads them at every [VoiceClient.start]. The token never ships in
 * the APK and is never logged.
 */
class VoiceSettings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** Server URL; blank resets to [DEFAULT_URL]. */
    var serverUrl: String
        get() = prefs.getString(KEY_URL, null)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_URL
        set(value) { prefs.edit().putString(KEY_URL, value.trim()).apply() }

    var token: String
        get() = prefs.getString(KEY_TOKEN, null)?.trim() ?: ""
        set(value) { prefs.edit().putString(KEY_TOKEN, value.trim()).apply() }

    val hasToken: Boolean get() = token.isNotEmpty()

    fun endpoint() = VoiceEndpoint(serverUrl, token)

    companion object {
        const val DEFAULT_URL = "wss://keys.kudcrafts.com/v1/stream"
        const val PREFS = "kudkeys_voice"
        const val KEY_URL = "voice_server_url"
        const val KEY_TOKEN = "voice_token"
    }
}

/** True when the app holds RECORD_AUDIO. Asking for it is the caller's job. */
fun hasMicPermission(context: Context): Boolean =
    context.checkPermission(Manifest.permission.RECORD_AUDIO, Process.myPid(), Process.myUid()) ==
        PackageManager.PERMISSION_GRANTED
