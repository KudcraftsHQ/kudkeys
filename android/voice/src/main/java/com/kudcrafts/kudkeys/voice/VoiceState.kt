package com.kudcrafts.kudkeys.voice

/**
 * The one thing the UI watches. A session goes
 * Idle → Connecting → Listening → Finishing → Done | Error, and back to Idle on [VoiceClient.cancel]
 * or [VoiceClient.reset]. [VoiceClient.start] also works from Done and Error.
 *
 * `text` is always the whole transcript so far: settled phrases in speaking order, then the phrase
 * in progress (if the server sends partials). Show it as is; it is not a delta.
 */
sealed interface VoiceState {
    /** Nothing running. */
    data object Idle : VoiceState

    /** Mic is recording, the socket is opening. Audio is queued, not dropped. */
    data class Connecting(val elapsedMs: Long, val level: Float) : VoiceState

    /** The server is ready and transcribing. [level] is the mic level, 0..1. [partial]: the end of [text] still in progress. */
    data class Listening(val elapsedMs: Long, val text: String, val level: Float, val partial: String = "") : VoiceState

    /** Mic released, waiting (up to 5 s) for the last phrases. */
    data class Finishing(val text: String, val partial: String = "") : VoiceState

    /** Finished. Insert [text] (may be empty when nothing was said). [segments]: its phrases, with confidence. */
    data class Done(val text: String, val segments: List<Segment> = emptyList()) : VoiceState

    /**
     * Failed. [text] holds whatever arrived before the failure, so a caller may still insert it.
     * [message] is technical detail for debugging; show [VoiceErrorKind.userMessage] instead.
     */
    data class Error(val kind: VoiceErrorKind, val message: String, val text: String = "") : VoiceState

    /** True while the mic or socket is in use (Connecting, Listening, Finishing). */
    val isActive: Boolean get() = this is Connecting || this is Listening || this is Finishing

    /** The end of [liveText] that is still a partial (may change), or "". */
    val partialText: String
        get() = when (this) {
            is Listening -> partial
            is Finishing -> partial
            else -> ""
        }

    /** Text shown in the bar for this state, or "" when there is none. */
    val liveText: String
        get() = when (this) {
            is Listening -> text
            is Finishing -> text
            is Done -> text
            else -> ""
        }
}

/** One settled phrase; [confidence] is 0–1 when the transcription provider gave one. */
data class Segment(val text: String, val confidence: Float? = null)

enum class VoiceErrorKind(val userMessage: String) {
    /** Not signed in and no legacy token in Settings → Voice. The mic was never opened. */
    NoToken("Voice: sign in from settings"),
    /** The server URL in settings isn't a ws:// or wss:// URL. */
    BadConfig("Voice: check the server URL in settings"),
    /** HTTP 401 on the WebSocket upgrade. */
    TokenRejected("Voice: token rejected"),
    /** HTTP 401 with an account's session token: it was signed out or expired. */
    SignedOut("Signed out — sign in again"),
    /** DNS, connect, TLS or a dropped connection. */
    NoConnection("Voice: no connection"),
    /** AudioRecord could not start (permission revoked, mic busy). */
    Mic("Voice: microphone unavailable"),
    /** Server `bad_request` or close 1008: this client broke the protocol. */
    BadRequest("Voice: request rejected"),
    /** Server `not_started`. */
    NotStarted("Voice: request rejected"),
    /** Server `provider`: the transcription provider failed. */
    Provider("Voice: transcription failed"),
    /** Server `limit`: session or rate limit. */
    Limit("Voice: limit reached"),
    /**
     * HTTP 5xx on the upgrade (e.g. 503: the server could not check the session because its database is down).
     * Not a sign-out: the token is kept and the next try may work.
     */
    Unavailable("Voice: server unavailable, try again"),
    /** Server `internal`, close 1011, any other HTTP error or close code. */
    Server("Voice: server error"),
}
