# Voice client API

Package `com.kudcrafts.kudkeys.voice`.

## Entry points

| | |
|---|---|
| `VoiceClient.create(context)` | The keyboard's client: endpoint from `VoiceSettings`, audio from `MicAudioSource`. One per IME instance. |
| `client.state: StateFlow<VoiceState>` | The only thing the UI reads. |
| `client.start(VoiceStart(private, keywords)): Boolean` | Opens the mic and the socket. False when it couldn't (state says why) or a session is already running. Works from Idle, Done, Error. |
| `client.stop()` | Releases the mic, sends `stop`, waits up to 5 s for `done`, then `Done(text)` (on timeout: what arrived). |
| `suspend client.stopAndWait(): VoiceState` | `stop()` and suspend until `Done` or `Error`. |
| `client.cancel()` | Sends `cancel`, releases the mic, state → `Idle` at once. No text. |
| `client.reset()` | `Done`/`Error` → `Idle` after the caller used the result. |
| `client.close()` | Cancel and stop background work (IME `onDestroy`). |
| `VoiceSettings(context)` | `serverUrl` (default `wss://keys.kudcrafts.com/v1/stream`), `apiBase` (`https://host` from it), the account (`sessionToken`, `accountName`, `accountEmail`, `isSignedIn`, `wasSignedOut`, `signedIn`, `signOut`, `markSignedOut`), the legacy `token`, `hasToken` (either). `endpoint()` sends the session token when signed in, else the legacy one. The client reads it at every `start`. |
| `Account(context)` | The account API (server/README.md "API for the app") with the stored token: `signIn` (stores `set-auth-token`), `me`, `save(name, voiceKeywords, languages)` (PATCH, only given fields), `usage`, `signOut`. Results are `AccountResult` (`Ok`, `WrongPassword`, `SignedOut`, `NotAllowed`, `NoNetwork`, `Unreachable`, `Failed(message)`); a `SignedOut` marks the account signed out. `AccountClient` is the stateless HTTP part. |
| `hasMicPermission(context)` | True when `RECORD_AUDIO` is granted. |
| `AudioSource` | Mic interface (`open`, blocking `read` of one 100 ms frame, thread-safe `close`). For tests or other inputs: `VoiceClient(endpoint = { … }, audioSource = { … })`. |

All methods are non-blocking and safe on the main thread.

`VoiceStart` sends no `languages` or `keywords` by default: with a session token the server uses the profile's
(keywords merged into Whisper's prompt), with a legacy token its default (id, en).

## State

```
Idle → Connecting(elapsedMs, level) → Listening(elapsedMs, text, level) → Finishing(text) → Done(text)
                                                                                          ↘ Error(kind, message, text)
cancel() from anywhere → Idle
```

- `text` is the whole transcript so far (finals in segment order + the phrase in progress). Replace,
  don't append. `state.liveText` gives it for any state ("" when none).
- `level` is the mic level 0..1 (RMS on a −60..0 dBFS scale), updated every 100 ms with `elapsedMs`.
- Audio is recorded from the first moment of `Connecting`; nothing said before `ready` is lost.
- `Error.kind.userMessage` is the bar text; `Error.text` is whatever arrived before the failure.

| `VoiceErrorKind` | When | `userMessage` |
|---|---|---|
| `NoToken` | not signed in and no legacy token (mic never opened) | Voice: sign in from settings |
| `TokenRejected` | HTTP 401 on upgrade with the legacy token | Voice: token rejected |
| `SignedOut` | HTTP 401 on upgrade with a session token (also calls `onSessionRejected`, which `create` wires to `markSignedOut`) | Signed out — sign in again |
| `NoConnection` | DNS/connect/TLS failure, dropped connection | Voice: no connection |
| `Mic` | `AudioRecord` failed to start | Voice: microphone unavailable |
| `BadConfig` | server URL not `ws://`/`wss://` | Voice: check the server URL in settings |
| `BadRequest`, `NotStarted` | server `bad_request`/`not_started`, close 1008 | Voice: request rejected |
| `Provider` | server `provider` | Voice: transcription failed |
| `Limit` | server `limit` | Voice: limit reached |
| `Server` | server `internal`, close 1011, other HTTP codes | Voice: server error |

## Hold-to-talk (quick voice mode)

```kotlin
class QuickVoice(private val ime: InputMethodService, private val bar: TopBar) {
    private val voice = VoiceClient.create(ime)
    private val settings = VoiceSettings(ime)
    private val scope = MainScope()

    init {
        scope.launch {
            voice.state.collect { s ->
                when (s) {
                    is VoiceState.Connecting -> bar.showRecording(s.elapsedMs, "", s.level)
                    is VoiceState.Listening -> bar.showRecording(s.elapsedMs, s.text, s.level)
                    is VoiceState.Finishing -> bar.showFinishing(s.text)
                    is VoiceState.Done -> { insert(s.text); voice.reset() }
                    is VoiceState.Error -> { bar.flash(s.kind.userMessage); voice.reset() }
                    VoiceState.Idle -> bar.showIdle()
                }
            }
        }
    }

    fun onMicDown(fieldIsPrivate: Boolean) {
        if (!settings.hasToken) return bar.flash(VoiceErrorKind.NoToken.userMessage)
        if (!hasMicPermission(ime)) return openPermissionActivity()
        if (voice.start(VoiceStart(private = fieldIsPrivate))) haptic()
    }
    fun onMicUp() = voice.stop()            // release: Done arrives in the collector
    fun onSlideLeftPastThreshold() = voice.cancel()
    fun onSlideUp() = bar.showLocked()      // lock: nothing to call, recording continues
    fun onStopButton() = voice.stop()       // locked mode's stop button
    fun onDestroy() { voice.close(); scope.cancel() }

    private fun insert(text: String) {
        if (text.isEmpty()) return
        val ic = ime.currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        val lead = if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " " else ""
        ic.commitText(lead + text, 1)
    }
}
```

Note: `Done` can also arrive without `stop()`, when the server ends a session on its own (600 s cap,
30 s without audio). The collector above handles that the same way.
