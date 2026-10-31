package com.kudcrafts.kudkeys.voice

import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Per-session options for the `start` message. */
data class VoiceStart(
    /** Private field (incognito, password, URL, search): sent as `"private": true`. */
    val private: Boolean = false,
    /** Names to nudge spelling (Fenno, TypeMap). */
    val keywords: List<String> = emptyList(),
    val languages: List<String> = listOf("id", "en"),
)

/**
 * Streams the mic to the Kud Keys voice server and exposes one [state] for the UI.
 * One session at a time; all methods are safe to call from the main thread and never block.
 *
 * ```
 * val voice = VoiceClient.create(context)
 * voice.start(VoiceStart(private = fieldIsPrivate))   // hold
 * voice.cancel()                                       // slide left
 * val end = voice.stopAndWait()                        // release / stop button
 * ```
 */
class VoiceClient(
    /** Read at every [start]; a blank token fails with [VoiceErrorKind.NoToken] before the mic opens. */
    private val endpoint: () -> VoiceEndpoint,
    private val audioSource: () -> AudioSource,
    private val http: OkHttpClient = defaultHttp,
    /** How long [stop] waits for `done` before finishing with what it has. */
    private val doneTimeoutMs: Long = 5_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    private var current: Session? = null

    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /**
     * Start recording and streaming. Returns false (and sets [VoiceState.Error], or does nothing if
     * a session is already running) when it couldn't start. Works from Idle, Done and Error.
     */
    fun start(options: VoiceStart = VoiceStart()): Boolean = synchronized(lock) {
        if (current != null) return false
        val ep = endpoint()
        if (ep.token.isBlank()) return fail(VoiceErrorKind.NoToken, "no token set")
        val request = try {
            require(ep.url.startsWith("ws://", true) || ep.url.startsWith("wss://", true))
            Request.Builder().url(ep.url).header("Authorization", "Bearer ${ep.token}").build()
        } catch (_: IllegalArgumentException) {
            return fail(VoiceErrorKind.BadConfig, "server URL must be ws:// or wss://")
        }
        val source = audioSource()
        try {
            source.open()
        } catch (e: Exception) {
            runCatching { source.close() }
            return fail(VoiceErrorKind.Mic, e.javaClass.simpleName)
        }
        val s = Session(source)
        current = s
        _state.value = VoiceState.Connecting(0, 0f)
        // OkHttp queues these until the socket opens; the server queues audio until `ready`.
        s.ws = http.newWebSocket(request, Listener(s))
        s.ws.send(Protocol.start(options))
        s.pump = scope.launch(Dispatchers.IO) { pump(s) }
        true
    }

    /**
     * Release the mic now, send `stop`, and wait up to 5 s for `done`; then [VoiceState.Done] with
     * the text (or with what arrived, on timeout). No-op unless Connecting or Listening.
     */
    fun stop() {
        synchronized(lock) {
            val s = current ?: return
            if (s.phase != Phase.Connecting && s.phase != Phase.Listening) return
            s.phase = Phase.Finishing
            s.capturing = false
            s.source.close()
            publish(s)
            s.timeout = scope.launch {
                s.pump?.join() // the last captured frame goes out before `stop`
                synchronized(lock) { if (current === s) s.ws.send(Protocol.STOP) }
                delay(doneTimeoutMs)
                synchronized(lock) { if (current === s) end(s, VoiceState.Done(s.transcript.text), cancelSocket = true) }
            }
        }
    }

    /** [stop], then suspend until the session ends; returns [VoiceState.Done] or [VoiceState.Error]. */
    suspend fun stopAndWait(): VoiceState {
        stop()
        return state.first { !it.isActive }
    }

    /** Drop everything: send `cancel`, release the mic, back to Idle with no text. */
    fun cancel() {
        synchronized(lock) {
            val s = current
            if (s != null) {
                s.ws.send(Protocol.CANCEL)
                end(s, VoiceState.Idle, cancelSocket = false)
                s.ws.close(1000, null)
            } else {
                _state.value = VoiceState.Idle
            }
        }
    }

    /** Done/Error → Idle, once the caller has used the result. No-op while a session runs. */
    fun reset() {
        synchronized(lock) { if (current == null) _state.value = VoiceState.Idle }
    }

    /** Cancel any session and stop background work. Call from the IME's onDestroy. */
    fun close() {
        cancel()
        scope.cancel()
    }

    // --- internals ---------------------------------------------------------------------------

    private enum class Phase { Connecting, Listening, Finishing, Ended }

    private inner class Session(val source: AudioSource) {
        val startedNs = System.nanoTime()
        val transcript = Transcript()
        lateinit var ws: WebSocket
        var phase = Phase.Connecting
        @Volatile var capturing = true
        var level = 0f
        var pump: Job? = null
        var timeout: Job? = null
        val elapsedMs get() = (System.nanoTime() - startedNs) / 1_000_000
    }

    private fun fail(kind: VoiceErrorKind, message: String): Boolean {
        _state.value = VoiceState.Error(kind, message)
        return false
    }

    private fun publish(s: Session) {
        _state.value = when (s.phase) {
            Phase.Connecting -> VoiceState.Connecting(s.elapsedMs, s.level)
            Phase.Listening -> VoiceState.Listening(s.elapsedMs, s.transcript.text, s.level)
            Phase.Finishing -> VoiceState.Finishing(s.transcript.text)
            Phase.Ended -> return
        }
    }

    /** Must hold [lock] and `current === s`. */
    private fun end(s: Session, final: VoiceState, cancelSocket: Boolean) {
        s.phase = Phase.Ended
        s.capturing = false
        s.source.close()
        s.timeout?.cancel()
        current = null
        if (cancelSocket) s.ws.cancel()
        _state.value = final
    }

    private fun endWithError(s: Session, kind: VoiceErrorKind, message: String) {
        end(s, VoiceState.Error(kind, message, s.transcript.text), cancelSocket = true)
    }

    private fun pump(s: Session) {
        val buf = ByteArray(Protocol.FRAME_BYTES)
        try {
            while (s.capturing) {
                val n = s.source.read(buf)
                if (n <= 0) break
                val frame = buf.toByteString(0, n)
                val level = pcmLevel(buf, n)
                synchronized(lock) {
                    if (current !== s) return
                    s.ws.send(frame)
                    s.level = level
                    if (s.phase != Phase.Finishing) publish(s)
                }
            }
        } finally {
            s.source.close()
        }
        // The mic failed while still recording: end the session (mic released above), keeping what arrived,
        // instead of showing Recording with nothing captured.
        if (s.capturing && s.source.failed) synchronized(lock) {
            if (current === s) endWithError(s, VoiceErrorKind.Mic, "mic read failed")
        }
    }

    private inner class Listener(val s: Session) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(lock) {
                if (current !== s) return
                when (val m = Protocol.parse(text)) {
                    is Protocol.Msg.Ready -> if (s.phase == Phase.Connecting) s.phase = Phase.Listening
                    is Protocol.Msg.Partial -> s.transcript.partial(m.segment, m.text)
                    is Protocol.Msg.Final -> s.transcript.final(m.segment, m.text)
                    Protocol.Msg.Done -> {
                        end(s, VoiceState.Done(s.transcript.text), cancelSocket = false)
                        webSocket.close(1000, null)
                        return
                    }
                    is Protocol.Msg.Error -> {
                        endWithError(s, Protocol.errorKind(m.code), "${m.code}: ${m.message}")
                        return
                    }
                    Protocol.Msg.Unknown -> return
                }
                publish(s)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            closed(code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(code)

        private fun closed(code: Int) {
            synchronized(lock) {
                if (current !== s) return
                when (code) {
                    // Closed normally without `done` (e.g. the server's session cap): keep what arrived.
                    1000 -> end(s, VoiceState.Done(s.transcript.text), cancelSocket = true)
                    1008 -> endWithError(s, VoiceErrorKind.BadRequest, "closed 1008")
                    else -> endWithError(s, VoiceErrorKind.Server, "closed $code")
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) {
                if (current !== s) return
                when {
                    response?.code == 401 -> endWithError(s, VoiceErrorKind.TokenRejected, "HTTP 401")
                    response != null -> endWithError(s, VoiceErrorKind.Server, "HTTP ${response.code}")
                    t is IOException -> endWithError(s, VoiceErrorKind.NoConnection, t.javaClass.simpleName)
                    else -> endWithError(s, VoiceErrorKind.Server, t.javaClass.simpleName)
                }
            }
        }
    }

    companion object {
        private val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
        }

        /** The keyboard's client: settings from [VoiceSettings], audio from the mic. */
        fun create(context: Context): VoiceClient {
            val settings = VoiceSettings(context.applicationContext)
            return VoiceClient(endpoint = settings::endpoint, audioSource = ::MicAudioSource)
        }
    }
}
