// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import com.kudcrafts.kudkeys.voice.VoiceClient
import com.kudcrafts.kudkeys.voice.VoiceErrorKind
import com.kudcrafts.kudkeys.voice.VoiceStart
import com.kudcrafts.kudkeys.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What the controller needs from the voice client; [VoiceClient] in the app, a fake in tests. */
interface VoiceEngine {
    val state: StateFlow<VoiceState>
    fun start(options: VoiceStart): Boolean
    fun stop()
    fun cancel()
    fun reset()
    fun close()
}

class VoiceClientEngine(
    private val client: VoiceClient,
    /** The keyboard's active languages, sent with every start so nothing else is transcribed. */
    private val activeLanguages: () -> List<String> = { emptyList() },
) : VoiceEngine {
    override val state get() = client.state
    override fun start(options: VoiceStart) =
        client.start(if (options.languages != null) options else options.copy(languages = activeLanguages().ifEmpty { null }))
    override fun stop() = client.stop()
    override fun cancel() = client.cancel()
    override fun reset() = client.reset()
    override fun close() = client.close()
}

/** What the top bar shows for quick voice. */
sealed interface VoiceBarState {
    data object Idle : VoiceBarState
    /**
     * A short message in the middle of the bar ("Hold to talk", an error). Goes away by itself.
     * [opensSettings]: tapping it opens the app's settings page (the mic is blocked).
     */
    data class Message(val text: String, val opensSettings: Boolean = false) : VoiceBarState
    /** Recording: red dot, elapsed time, live text. [locked]: hands-free, the Mic spot is a stop button. */
    data class Recording(val elapsedMs: Long, val text: String, val locked: Boolean) : VoiceBarState
    /** Released, waiting for the last words. */
    data class Finishing(val text: String) : VoiceBarState
}

/**
 * Hold-to-talk on the Mic icon (spec item 6). Hold → record; release → insert; slide left past
 * [CANCEL_FRACTION] of the bar → cancel; slide up past the lock distance → lock (tap the stop button to insert).
 * A short tap calls [Host.onTap] (the full voice editor). Touch coordinates are raw screen pixels.
 *
 * Text only goes into the field the session started in: the IME reports every field with [onField], and a
 * different field cancels the session (an app can switch fields with restartInput, without onFinishInputView).
 */
class QuickVoiceController(
    private val engine: VoiceEngine,
    private val host: Host,
    private val scope: CoroutineScope,
) {
    interface Host {
        fun hasToken(): Boolean
        fun hasMicPermission(): Boolean
        fun requestMicPermission()
        /** The user denied the mic for good ("don't ask again"): asking again would only flash an activity. */
        fun isMicBlocked(): Boolean
        /** Open this app's page in system settings, where the mic can be allowed. */
        fun openMicSettings()
        fun isPrivateField(): Boolean
        fun haptic()
        fun insert(text: String)
        fun render(state: VoiceBarState)
        /** Width of the whole bar in px, for the cancel threshold. */
        fun barWidth(): Int
        /** Upward distance in px that locks recording. */
        fun lockDistance(): Int
        /** Run [block] after [delayMs] on the main thread; the returned function cancels it. */
        fun schedule(delayMs: Long, block: () -> Unit): () -> Unit
        /** A short tap on Mic (v0.3: opens the full voice editor). */
        fun onTap()
    }

    enum class Phase { Idle, Pressed, Recording, Locked, Finishing }

    var phase = Phase.Idle
        private set
    private var downX = 0f
    private var downY = 0f
    private var cancelHold: (() -> Unit)? = null
    private var cancelMessage: (() -> Unit)? = null
    private var messageShowing = false
    /** In locked mode: the finger that locked is still down, its release must not stop. */
    private var lockingTouchDown = false
    private var lastState: VoiceState = VoiceState.Idle
    private var messageOpensSettings = false
    /** The field the IME is on now ([onField]) and the one the running session started in. */
    private var field: Any? = null
    private var sessionField: Any? = null
    private val collector: Job = scope.launch { engine.state.collect { onEngineState(it) } }

    fun onMicDown(x: Float, y: Float) {
        when (phase) {
            Phase.Idle -> {
                phase = Phase.Pressed
                downX = x
                downY = y
                cancelHold = host.schedule(HOLD_MS) { onHoldTimeout() }
            }
            Phase.Locked -> lockingTouchDown = false // a new touch on the stop button
            else -> Unit
        }
    }

    fun onMicMove(x: Float, y: Float) {
        if (phase != Phase.Recording) return
        val dx = x - downX
        val dy = downY - y
        if (dx < -CANCEL_FRACTION * host.barWidth()) {
            cancel()
        } else if (dy > host.lockDistance() && dy > abs(dx)) {
            phase = Phase.Locked
            lockingTouchDown = true
            host.haptic()
            renderRecording(lastState)
        }
    }

    fun onMicUp() {
        when (phase) {
            Phase.Pressed -> {
                clearHold()
                phase = Phase.Idle
                clearMessage()
                host.onTap()
            }
            Phase.Recording -> finish()
            Phase.Locked -> if (lockingTouchDown) lockingTouchDown = false else finish()
            else -> Unit
        }
    }

    /** The system took the touch away (ACTION_CANCEL). A held recording is dropped, a locked one keeps going. */
    fun onMicTouchCancelled() {
        when (phase) {
            Phase.Pressed -> { clearHold(); phase = Phase.Idle }
            Phase.Recording -> cancel()
            Phase.Locked -> lockingTouchDown = false
            else -> Unit
        }
    }

    /**
     * The IME started input in [key] (any value that is equal for the same field). A different field drops the
     * session, including one waiting for its last words, so its text can't land in the new field.
     */
    fun onField(key: Any?) {
        if (key == field) return
        field = key
        cancelSession()
    }

    /**
     * The keyboard was hidden (or the screen went off) while the field stays bound. A recording is dropped and the
     * mic released; a released one waiting for its last words still finishes, and inserts if the field is the same.
     */
    fun onHidden() {
        if (phase == Phase.Finishing) return
        cancelSession()
    }

    /** Start hands-free (locked) recording without a finger on Mic: the comma long-press. */
    fun startLocked() {
        if (phase != Phase.Idle) return
        clearHold()
        startRecording(locked = true)
    }

    /** The bar message was tapped. */
    fun onMessageTapped() {
        if (messageShowing && messageOpensSettings) {
            clearMessage()
            if (phase == Phase.Idle) host.render(VoiceBarState.Idle)
            host.openMicSettings()
        }
    }

    /** Leaving the field or hiding the keyboard: drop everything and release the mic. */
    fun cancelSession() {
        clearHold()
        if (phase != Phase.Idle || engine.state.value.isActive) engine.cancel()
        phase = Phase.Idle
        if (!messageShowing) host.render(VoiceBarState.Idle)
    }

    fun close() {
        cancelSession()
        collector.cancel()
        engine.close()
    }

    private fun onHoldTimeout() {
        cancelHold = null
        if (phase != Phase.Pressed) return
        phase = Phase.Idle
        startRecording(locked = false)
    }

    private fun startRecording(locked: Boolean) {
        if (!host.hasToken()) return message(VoiceErrorKind.NoToken.userMessage)
        if (!host.hasMicPermission()) {
            return if (host.isMicBlocked()) message(MIC_BLOCKED, opensSettings = true)
            else host.requestMicPermission()
        }
        sessionField = field
        if (engine.start(VoiceStart(private = host.isPrivateField()))) {
            phase = if (locked) Phase.Locked else Phase.Recording
            lockingTouchDown = false
            clearMessage()
            host.haptic()
            renderRecording(engine.state.value)
        }
        // on false the engine is in Error, and the collector shows it
    }

    /** Insert only into the field the session started in. */
    private fun insert(text: String) {
        if (field == sessionField) host.insert(text)
    }

    private fun finish() {
        phase = Phase.Finishing
        engine.stop()
        renderFinishing(engine.state.value)
    }

    private fun cancel() {
        engine.cancel()
        phase = Phase.Idle
        host.render(VoiceBarState.Idle)
    }

    private fun onEngineState(s: VoiceState) {
        lastState = s
        when (s) {
            is VoiceState.Connecting, is VoiceState.Listening -> renderRecording(s)
            is VoiceState.Finishing -> renderFinishing(s)
            is VoiceState.Done -> {
                // Done can also come without a release, when the server ends the session itself.
                if (phase != Phase.Idle && phase != Phase.Pressed) insert(s.text)
                phase = Phase.Idle
                engine.reset()
                if (!messageShowing) host.render(VoiceBarState.Idle)
            }
            is VoiceState.Error -> {
                // Keep what arrived before the failure, whether or not Mic was released yet.
                if (phase != Phase.Idle && phase != Phase.Pressed && s.text.isNotEmpty()) insert(s.text)
                if (phase != Phase.Pressed) phase = Phase.Idle
                engine.reset()
                message(s.kind.userMessage)
            }
            VoiceState.Idle -> if (phase == Phase.Recording || phase == Phase.Locked || phase == Phase.Finishing) {
                phase = Phase.Idle
                if (!messageShowing) host.render(VoiceBarState.Idle)
            }
        }
    }

    private fun renderRecording(s: VoiceState) {
        if (phase != Phase.Recording && phase != Phase.Locked) return
        val elapsed = when (s) {
            is VoiceState.Connecting -> s.elapsedMs
            is VoiceState.Listening -> s.elapsedMs
            else -> 0L
        }
        host.render(VoiceBarState.Recording(elapsed, s.liveText, phase == Phase.Locked))
    }

    private fun renderFinishing(s: VoiceState) {
        if (phase != Phase.Finishing) return
        host.render(VoiceBarState.Finishing(s.liveText))
    }

    private fun message(text: String, opensSettings: Boolean = false) {
        clearMessage()
        messageShowing = true
        messageOpensSettings = opensSettings
        host.render(VoiceBarState.Message(text, opensSettings))
        cancelMessage = host.schedule(if (opensSettings) ACTION_MESSAGE_MS else MESSAGE_MS) {
            cancelMessage = null
            messageShowing = false
            messageOpensSettings = false
            if (phase == Phase.Idle || phase == Phase.Pressed) host.render(VoiceBarState.Idle)
        }
    }

    private fun clearMessage() {
        cancelMessage?.invoke()
        cancelMessage = null
        messageShowing = false
        messageOpensSettings = false
    }

    private fun clearHold() {
        cancelHold?.invoke()
        cancelHold = null
    }

    companion object {
        /** A press shorter than this is a tap. */
        const val HOLD_MS = 250L
        const val MESSAGE_MS = 2_500L
        /** A message you can tap stays longer. */
        const val ACTION_MESSAGE_MS = 6_000L
        /** Slide left past this fraction of the bar width to cancel. */
        const val CANCEL_FRACTION = 0.3f
        const val HINT_HOLD = "Hold to talk"
        const val HINT_SLIDE = "‹ Slide to cancel"
        const val MIC_BLOCKED = "Microphone blocked — tap to open settings"
    }
}
