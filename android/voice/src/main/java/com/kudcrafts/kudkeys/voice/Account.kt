package com.kudcrafts.kudkeys.voice

import android.content.Context

/**
 * The signed-in account, as the settings screens use it: [AccountClient] calls with the stored session token.
 * Sign-in stores the token; a 401 on any call marks the account signed out; sign-out forgets it.
 */
class Account(val settings: VoiceSettings, val client: AccountClient = AccountClient({ settings.apiBase })) {
    constructor(context: Context) : this(VoiceSettings(context.applicationContext))

    suspend fun signIn(email: String, password: String): AccountResult<AccountClient.SignedIn> {
        val r = client.signIn(email, password)
        if (r is AccountResult.Ok) settings.signedIn(r.value.token, r.value.name, r.value.email)
        return r
    }

    suspend fun me(): AccountResult<Profile> = authed { client.me(it) }.also(::remember)

    suspend fun save(name: String?, voiceKeywords: List<String>?, languages: List<String>?): AccountResult<Profile> =
        authed { client.updateMe(it, name, voiceKeywords, languages) }.also(::remember)

    suspend fun usage(): AccountResult<Usage> = authed { client.usage(it) }

    /** Tells the server (best effort: offline still signs out here), then forgets the token. */
    suspend fun signOut() {
        val token = settings.sessionToken
        if (token.isNotEmpty()) client.signOut(token)
        settings.signOut()
    }

    private suspend fun <T> authed(block: suspend (String) -> AccountResult<T>): AccountResult<T> {
        val token = settings.sessionToken
        if (token.isEmpty()) return AccountResult.SignedOut
        val r = block(token)
        if (r is AccountResult.SignedOut) settings.markSignedOut()
        return r
    }

    private fun remember(r: AccountResult<Profile>) {
        if (r is AccountResult.Ok) settings.updateAccount(r.value.name, r.value.email)
    }
}
