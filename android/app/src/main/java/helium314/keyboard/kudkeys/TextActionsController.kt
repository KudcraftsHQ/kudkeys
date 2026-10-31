// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import com.kudcrafts.kudkeys.voice.TextResult
import com.kudcrafts.kudkeys.voice.TextService
import com.kudcrafts.kudkeys.voice.VoiceStart
import com.kudcrafts.kudkeys.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the bar shows for ✦ Fix and Instruct. */
sealed interface TextBarState {
    data object Idle : TextBarState
    /** Waiting for the server: a spinner on [action]'s icon. */
    data class Busy(val action: TextAction) : TextBarState
    /** Instruct is held: the spoken instruction so far. */
    data class Listening(val elapsedMs: Long, val text: String) : TextBarState
    /** A short message; [undo]: it ends in "· Undo" and a tap undoes the edit. */
    data class Message(val text: String, val undo: Boolean = false) : TextBarState
}

/**
 * ✦ Fix and Instruct on the top bar (spec items 2 and 3). Both read the selection or the field around the cursor,
 * send it, and replace it in place in one batch edit; "Fixed · Undo" / "Done · Undo" stays [UNDO_MS] and a tap puts
 * back the exact previous text and selection. Another field cancels a call in flight and forgets the undo.
 */
class TextActionsController(
    private val engine: VoiceEngine,
    private val service: TextService,
    private val field: FieldEditor,
    private val host: Host,
    private val scope: CoroutineScope,
) {
    interface Host {
        fun isSignedIn(): Boolean
        fun isPrivateField(): Boolean
        fun hasMicPermission(): Boolean
        fun requestMicPermission()
        fun haptic()
        fun render(state: TextBarState)
        fun schedule(delayMs: Long, block: () -> Unit): () -> Unit
    }

    /** One applied edit: [start]..[start]+[newText] holds [newText]; undo puts [oldText] and the old selection back. */
    private data class Edit(val start: Int, val oldText: String, val newText: String, val selStart: Int, val selEnd: Int)

    var state: TextBarState = TextBarState.Idle
        private set
    private var job: Job? = null
    private var lastEdit: Edit? = null
    private var cancelMessage: (() -> Unit)? = null
    private var cancelHold: (() -> Unit)? = null
    private var listening = false
    private var fieldKey: Any? = null
    /** Bumped on every start of input, restarts in the same field included: a result for an older one is dropped. */
    private var generation = 0
    private val collector: Job = scope.launch { engine.state.collect { onEngineState(it) } }

    val isBusy get() = state is TextBarState.Busy || state is TextBarState.Listening

    // ---- Fix --------------------------------------------------------------------------------------------------

    fun onFix() {
        if (isBusy) return
        if (!host.isSignedIn()) return message(TextAction.NOT_SIGNED_IN)
        val snap = field.snapshot(MAX_CHARS) ?: return message(NOTHING_TO_FIX)
        if (snap.text.isBlank()) return message(NOTHING_TO_FIX)
        run(TextAction.Fix, snap) { service.fix(snap.text, host.isPrivateField()).map { if (it.changed) it.text else null } }
    }

    // ---- Instruct ---------------------------------------------------------------------------------------------

    fun onInstructDown() {
        if (isBusy) return
        cancelHold?.invoke()
        cancelHold = host.schedule(QuickVoiceController.HOLD_MS) {
            cancelHold = null
            if (!host.isSignedIn()) return@schedule message(TextAction.NOT_SIGNED_IN)
            if (!host.hasMicPermission()) return@schedule host.requestMicPermission()
            if (engine.start(VoiceStart(private = host.isPrivateField()))) {
                listening = true
                clearMessage()
                host.haptic()
                render(TextBarState.Listening(0, ""))
            }
        }
    }

    fun onInstructUp() {
        if (cancelHold != null) {
            cancelHold?.invoke()
            cancelHold = null
            return message(HINT_INSTRUCT)
        }
        if (!listening) return
        engine.stop()
        render(TextBarState.Busy(TextAction.Instruct))
    }

    fun onInstructCancelled() {
        cancelHold?.invoke()
        cancelHold = null
        if (listening) { listening = false; engine.cancel(); render(TextBarState.Idle) }
    }

    private fun onInstruction(said: String) {
        val instruction = said.trim()
        if (instruction.isEmpty()) return message(DIDNT_CATCH)
        // An empty field is fine: the instruction is "write this".
        val snap = field.snapshot(MAX_CHARS) ?: return message(TextAction.Instruct.errorMessage(TextResult.Rejected))
        // A cut-off selection would come back as the whole text: refuse rather than drop its tail.
        if (snap.truncated) return message(SELECTION_TOO_LONG)
        run(TextAction.Instruct, snap) {
            service.instruct(instruction, snap.text, host.isPrivateField()).map { it.takeIf { t -> t != snap.text } }
        }
    }

    private fun onEngineState(s: VoiceState) {
        if (!listening) return
        when (s) {
            is VoiceState.Connecting -> render(TextBarState.Listening(s.elapsedMs, ""))
            is VoiceState.Listening -> render(TextBarState.Listening(s.elapsedMs, s.text))
            is VoiceState.Finishing -> render(TextBarState.Busy(TextAction.Instruct))
            is VoiceState.Done -> { listening = false; engine.reset(); onInstruction(s.text) }
            is VoiceState.Error -> {
                // A cut-off instruction ("hapus semua" of "hapus semua kecuali…") is never applied.
                listening = false
                engine.reset()
                message(s.kind.userMessage)
            }
            VoiceState.Idle -> { listening = false; if (state is TextBarState.Listening) render(TextBarState.Idle) }
        }
    }

    // ---- shared -----------------------------------------------------------------------------------------------

    /** Calls [call]; a null result means "nothing to change". Applies the answer if the field still holds [snap]. */
    private fun run(action: TextAction, snap: FieldSnapshot, call: suspend () -> TextResult<String?>) {
        clearMessage()
        lastEdit = null
        render(TextBarState.Busy(action))
        val key = fieldKey
        val gen = generation
        job = scope.launch {
            val r = call()
            job = null
            if (key != fieldKey || gen != generation) return@launch
            if (r !is TextResult.Ok) return@launch message(action.errorMessage(r))
            val out = r.value
            when {
                out == null -> message(if (action == TextAction.Fix) LOOKS_GOOD else NO_CHANGE)
                field.read(snap.start, snap.end) != snap.text -> message(TEXT_CHANGED)
                // An empty range always "matches": the field must also still be empty with the cursor where it was.
                snap.text.isEmpty() && field.snapshot(MAX_CHARS) != snap -> message(TEXT_CHANGED)
                else -> apply(action, snap, out)
            }
        }
    }

    private fun apply(action: TextAction, snap: FieldSnapshot, out: String) {
        val newEnd = snap.start + out.length
        val (selStart, selEnd) = if (snap.isSelection) snap.start to newEnd else {
            // keep the cursor as far from the end as it was (at the end stays at the end)
            val c = (newEnd - (snap.end - snap.selStart)).coerceIn(snap.start, newEnd)
            c to c
        }
        if (!field.replace(snap.start, snap.end, snap.text, out, selStart, selEnd))
            return message(if (action == TextAction.Fix) CANT_FIX_FIELD else CANT_EDIT_FIELD)
        lastEdit = Edit(snap.start, snap.text, out, snap.selStart, snap.selEnd)
        message(if (action == TextAction.Instruct) DONE else if (snap.truncated) FIXED_PART else FIXED, undo = true)
    }

    /** The message was tapped: undo when it offers it. */
    fun onMessageTapped() {
        val m = state as? TextBarState.Message ?: return
        if (!m.undo) return
        undo()
    }

    fun undo(): Boolean {
        val e = lastEdit ?: return false
        lastEdit = null
        clearMessage()
        val end = e.start + e.newText.length
        if (field.read(e.start, end) != e.newText || !field.replace(e.start, end, e.newText, e.oldText, e.selStart, e.selEnd)) {
            message(CANT_UNDO)
            return false
        }
        render(TextBarState.Idle)
        return true
    }

    /** The IME is on [key] now; another field drops a call in flight, a held instruction and the undo. */
    fun onField(key: Any?) {
        if (key == fieldKey) return
        fieldKey = key
        cancelAll()
    }

    /**
     * Input started again, maybe in the same field (an app reusing one EditText across chats restarts input): a call
     * in flight is dropped, so its answer never lands in what is now another conversation. The undo stays: it checks
     * the text before it touches anything.
     */
    fun onInputStarted() {
        generation++
        if (job == null) return
        job?.cancel()
        job = null
        render(TextBarState.Idle)
    }

    fun onHidden() = cancelAll()

    private fun cancelAll() {
        job?.cancel()
        job = null
        cancelHold?.invoke()
        cancelHold = null
        if (listening) { listening = false; engine.cancel() }
        lastEdit = null
        clearMessage()
        render(TextBarState.Idle)
    }

    fun destroy() {
        cancelAll()
        collector.cancel()
        engine.close()
    }

    private fun render(s: TextBarState) {
        state = s
        host.render(s)
    }

    private fun message(text: String, undo: Boolean = false) {
        clearMessage()
        render(TextBarState.Message(text, undo))
        cancelMessage = host.schedule(if (undo) UNDO_MS else MESSAGE_MS) {
            cancelMessage = null
            if (undo) lastEdit = null
            render(TextBarState.Idle)
        }
    }

    private fun clearMessage() {
        cancelMessage?.invoke()
        cancelMessage = null
    }

    private inline fun <T, R> TextResult<T>.map(f: (T) -> R): TextResult<R> = when (this) {
        is TextResult.Ok -> TextResult.Ok(f(value))
        is TextResult.RateLimited -> this
        TextResult.TooLong -> TextResult.TooLong
        TextResult.ChangedTooMuch -> TextResult.ChangedTooMuch
        TextResult.NotSignedIn -> TextResult.NotSignedIn
        TextResult.SignedOut -> TextResult.SignedOut
        TextResult.Unavailable -> TextResult.Unavailable
        TextResult.NoNetwork -> TextResult.NoNetwork
        TextResult.Rejected -> TextResult.Rejected
    }

    companion object {
        /** Spec: up to 4,000 chars around the cursor. */
        const val MAX_CHARS = 4_000
        const val UNDO_MS = 10_000L
        const val MESSAGE_MS = 2_500L
        const val FIXED = "Fixed · Undo"
        const val FIXED_PART = "Fixed the first 4,000 characters · Undo"
        const val SELECTION_TOO_LONG = "Selection too long — 4,000 characters at most"
        const val CANT_FIX_FIELD = "Couldn't fix this field"
        const val CANT_EDIT_FIELD = "Couldn't edit this field"
        const val DONE = "Done · Undo"
        const val LOOKS_GOOD = "Looks good"
        const val NO_CHANGE = "Nothing to change"
        const val NOTHING_TO_FIX = "Nothing to fix"
        const val TEXT_CHANGED = "Text changed — try again"
        const val CANT_UNDO = "Can't undo — the text changed"
        const val HINT_INSTRUCT = "Hold and say what to do"
        const val DIDNT_CATCH = "Didn't catch that"
    }
}
