package com.kudcrafts.kudkeys.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The signed-in account, as the settings screens use it: [AccountClient] calls with the stored session token.
 * Sign-in stores the token; only a 401 on a call marks the account signed out (a 5xx or no network keeps it);
 * sign-out forgets it.
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

    /**
     * Forgets the token on this phone first (so closing the screen, a slow network or being offline cannot leave
     * it behind), then revokes it on the server best effort, on [background] so it outlives the screen.
     * Failures are ignored. Returns the server call, for tests.
     */
    fun signOut(background: CoroutineScope = signOutScope): Job? {
        val token = settings.sessionToken
        settings.signOut()
        if (token.isEmpty()) return null
        return background.launch { runCatching { client.signOut(token) } }
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

    private companion object {
        /** App-wide: a server sign-out keeps going after the profile screen is gone. */
        val signOutScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
