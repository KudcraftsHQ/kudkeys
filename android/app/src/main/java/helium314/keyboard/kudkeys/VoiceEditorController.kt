// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import com.kudcrafts.kudkeys.voice.TextResult
import com.kudcrafts.kudkeys.voice.TextService
import com.kudcrafts.kudkeys.voice.TidyQuestion
import com.kudcrafts.kudkeys.voice.VoiceErrorKind
import com.kudcrafts.kudkeys.voice.VoiceStart
import com.kudcrafts.kudkeys.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the full-voice editor draws. Everything the view needs; it keeps no state of its own. */
data class EditorUi(
    val open: Boolean = false,
    val mode: EditorMode = EditorMode.Idle,
    /** The text as shown (tidied or raw), plus the live dictation's settled part while recording. */
    val text: String = "",
    /** The end of the live dictation that may still change: drawn dimmed after [text]. */
    val partial: String = "",
    val elapsedMs: Long = 0,
    val tidyOn: Boolean = true,
    val showingRaw: Boolean = false,
    /** The raw text differs from the tidied one, so the Raw switch does something. */
    val canShowRaw: Boolean = false,
    val canUndo: Boolean = false,
    /** The question on the card (never while showing raw or busy). */
    val question: TidyQuestion? = null,
    val questionsLeft: Int = 0,
    /** The spoken edit so far, while Instruct is held. */
    val instruction: String = "",
    val message: String? = null,
    val hasDraft: Boolean = false,
) {
    val isRecording get() = mode == EditorMode.Recording
    val isBusy get() = mode != EditorMode.Idle
    val canInsert get() = mode == EditorMode.Idle && text.isNotBlank()
}

enum class EditorMode { Idle, Recording, Finishing, Tidying, InstructListening, InstructFinishing, Instructing }

/**
 * Full voice mode (spec item 1): the dictation editor that replaces the key area. Pure Kotlin, driven by the view
 * and the IME; [Host] does the Android parts. The draft is saved to [store] after every change and loaded at
 * construction, so it survives the keyboard closing and the process dying.
 */
class VoiceEditorController(
    private val engine: VoiceEngine,
    private val text: TextService,
    private val store: DraftStore,
    private val host: Host,
    private val scope: CoroutineScope,
) {
    interface Host {
        fun hasVoiceToken(): Boolean
        fun hasMicPermission(): Boolean
        fun requestMicPermission()
        fun isPrivateField(): Boolean
        /** Put [text] into the current field. */
        fun insert(text: String)
        fun render(ui: EditorUi)
        fun haptic()
        fun schedule(delayMs: Long, block: () -> Unit): () -> Unit
    }

    private var open = false
    private var mode = EditorMode.Idle
    private var draft: VoiceDraft = store.load() ?: VoiceDraft("", "")
    private val undo = ArrayDeque<VoiceDraft>()
    private var showingRaw = false
    private var tidyOn = store.tidyOn
    private var live: VoiceState = VoiceState.Idle
    private var message: String? = null
    private var cancelMessage: (() -> Unit)? = null
    private var cancelHold: (() -> Unit)? = null
    private var job: Job? = null
    private val collector: Job = scope.launch { engine.state.collect { onEngineState(it) } }

    val isOpen get() = open
    val hasDraft get() = !draft.isEmpty
    val ui: EditorUi get() = buildUi()
    /** For tests. */
    val currentDraft get() = draft

    // ---- opening and closing --------------------------------------------------------------------------------

    /** A tap on Mic: open the editor. A new draft starts recording at once; an existing one is shown as it was. */
    fun open() {
        if (open) return
        open = true
        if (draft.isEmpty) startDictation() else render()
    }

    /** Close (×): the draft stays. A recording stops and its words join the draft. */
    fun close() {
        if (!open) return
        cancelInstructHold()
        when (mode) {
            EditorMode.Recording -> stopDictation()
            EditorMode.InstructListening -> { engine.cancel(); mode = EditorMode.Idle }
            else -> Unit
        }
        open = false
        clearMessage()
        render()
    }

    /** The keyboard was hidden (another app, screen off): like Close. */
    fun onHidden() = close()

    /** Discard: the draft is gone, nothing is inserted. */
    fun discard() {
        cancelInstructHold()
        job?.cancel()
        if (engine.state.value.isActive) engine.cancel()
        mode = EditorMode.Idle
        setDraft(VoiceDraft("", ""), pushUndo = false)
        undo.clear()
        showingRaw = false
        open = false
        clearMessage()
        render()
    }

    /** Insert: the shown text goes into the field; the draft is cleared and the editor closes. */
    fun insert() {
        if (mode != EditorMode.Idle) return
        val out = shownText()
        if (out.isBlank()) return
        host.insert(out)
        setDraft(VoiceDraft("", ""), pushUndo = false)
        undo.clear()
        showingRaw = false
        open = false
        clearMessage()
        render()
    }

    // ---- dictation --------------------------------------------------------------------------------------------

    /** The big round button: stop while recording, else continue dictating. */
    fun onMicButton() {
        when (mode) {
            EditorMode.Recording -> stopDictation()
            EditorMode.Idle -> startDictation()
            else -> Unit
        }
    }

    fun stopDictation() {
        if (mode != EditorMode.Recording) return
        mode = EditorMode.Finishing
        engine.stop()
        render()
    }

    private fun startDictation() {
        if (!canRecord()) return render()
        if (engine.start(VoiceStart(private = host.isPrivateField()))) {
            mode = EditorMode.Recording
            clearMessage()
            host.haptic()
        }
        render() // on false the engine is in Error and the collector shows it
    }

    private fun canRecord(): Boolean {
        if (!host.hasVoiceToken()) { showMessage(VoiceErrorKind.NoToken.userMessage); return false }
        if (!host.hasMicPermission()) { host.requestMicPermission(); return false }
        return true
    }

    private fun onDictationDone(said: String) {
        val chunk = said.trim()
        if (chunk.isEmpty()) {
            mode = EditorMode.Idle
            return render()
        }
        if (!tidyOn) {
            mode = EditorMode.Idle
            appendChunk(chunk, chunk, emptyList())
            return render()
        }
        mode = EditorMode.Tidying
        render()
        val private = host.isPrivateField()
        job = scope.launch {
            val r = text.tidy(chunk, private)
            job = null
            mode = EditorMode.Idle
            if (r is TextResult.Ok) appendChunk(r.value.text.trim(), chunk, r.value.questions)
            else { appendChunk(chunk, chunk, emptyList()); showMessage(TextAction.Tidy.errorMessage(r)) }
            render()
        }
    }

    private fun appendChunk(tidied: String, raw: String, questions: List<TidyQuestion>) {
        val base = if (showingRaw) VoiceDraft(draft.raw, draft.raw) else draft
        val trimmed = tidied.length - tidied.trimStart().length
        setDraft(base.append(tidied.trim(), raw, shift(questions, -trimmed)), pushUndo = true)
        showingRaw = false
    }

    private fun shift(qs: List<TidyQuestion>, by: Int) =
        if (by == 0) qs else qs.mapNotNull { q -> q.copy(start = q.start + by, end = q.end + by).takeIf { it.start >= 0 } }

    // ---- editing ----------------------------------------------------------------------------------------------

    fun setTidy(on: Boolean) {
        tidyOn = on
        store.tidyOn = on
        render()
    }

    fun toggleRaw() {
        if (mode != EditorMode.Idle || draft.raw == draft.text) return
        showingRaw = !showingRaw
        render()
    }

    /** Answer the question on the card; null = keep as is. The next card shows. */
    fun answer(option: String?) {
        val q = draft.questions.firstOrNull() ?: return
        if (mode != EditorMode.Idle || showingRaw) return
        setDraft(draft.answer(q, option), pushUndo = option != null)
        render()
    }

    /** The card's ×: same as keep as is. */
    fun dismissQuestion() = answer(null)

    fun undo() {
        if (mode != EditorMode.Idle) return
        val prev = undo.removeLastOrNull() ?: return
        setDraft(prev, pushUndo = false)
        showingRaw = false
        render()
    }

    // ---- spoken edits -----------------------------------------------------------------------------------------

    fun onInstructDown() {
        if (mode != EditorMode.Idle) return
        cancelInstructHold()
        cancelHold = host.schedule(QuickVoiceController.HOLD_MS) {
            cancelHold = null
            if (mode != EditorMode.Idle || !canRecord()) return@schedule render()
            if (engine.start(VoiceStart(private = host.isPrivateField()))) {
                mode = EditorMode.InstructListening
                clearMessage()
                host.haptic()
            }
            render()
        }
    }

    fun onInstructUp() {
        if (cancelHold != null) {
            cancelInstructHold()
            return showMessage(HINT_INSTRUCT)
        }
        if (mode != EditorMode.InstructListening) return
        mode = EditorMode.InstructFinishing
        engine.stop()
        render()
    }

    fun onInstructCancelled() {
        cancelInstructHold()
        if (mode == EditorMode.InstructListening) { engine.cancel(); mode = EditorMode.Idle; render() }
    }

    private fun cancelInstructHold() {
        cancelHold?.invoke()
        cancelHold = null
    }

    private fun onInstructionDone(said: String) {
        val instruction = said.trim()
        if (instruction.isEmpty()) {
            mode = EditorMode.Idle
            return showMessage(DIDNT_CATCH)
        }
        mode = EditorMode.Instructing
        render()
        val current = shownText()
        val private = host.isPrivateField()
        job = scope.launch {
            val r = text.instruct(instruction, current, private)
            job = null
            mode = EditorMode.Idle
            if (r is TextResult.Ok) {
                setDraft(VoiceDraft(r.value.trim(), draft.raw), pushUndo = true)
                showingRaw = false
            } else showMessage(TextAction.Instruct.errorMessage(r))
            render()
        }
    }

    // ---- engine -----------------------------------------------------------------------------------------------

    private fun onEngineState(s: VoiceState) {
        live = s
        when (s) {
            is VoiceState.Connecting, is VoiceState.Listening, is VoiceState.Finishing -> if (isListening()) render()
            is VoiceState.Done -> {
                engine.reset()
                live = VoiceState.Idle
                when (mode) {
                    EditorMode.Recording, EditorMode.Finishing -> onDictationDone(s.text)
                    EditorMode.InstructListening, EditorMode.InstructFinishing -> onInstructionDone(s.text)
                    else -> Unit
                }
            }
            is VoiceState.Error -> {
                engine.reset()
                live = VoiceState.Idle
                val wasDictating = mode == EditorMode.Recording || mode == EditorMode.Finishing
                val wasInstructing = mode == EditorMode.InstructListening || mode == EditorMode.InstructFinishing
                if (wasDictating || wasInstructing) mode = EditorMode.Idle
                // keep the words that arrived before the failure
                if (wasDictating && s.text.isNotBlank()) appendChunk(s.text.trim(), s.text.trim(), emptyList())
                if (wasDictating || wasInstructing || open) showMessage(s.kind.userMessage) else render()
            }
            VoiceState.Idle -> if (isListening()) { mode = EditorMode.Idle; render() }
        }
    }

    private fun isListening() = mode == EditorMode.Recording || mode == EditorMode.Finishing ||
        mode == EditorMode.InstructListening || mode == EditorMode.InstructFinishing

    fun destroy() {
        cancelInstructHold()
        job?.cancel()
        if (engine.state.value.isActive) engine.cancel()
        clearMessage()
        collector.cancel()
        engine.close()
    }

    // ---- state ------------------------------------------------------------------------------------------------

    private fun setDraft(d: VoiceDraft, pushUndo: Boolean) {
        if (pushUndo && d != draft) {
            undo.addLast(draft)
            while (undo.size > MAX_UNDO) undo.removeFirst()
        }
        draft = d
        store.save(d)
    }

    private fun shownText() = if (showingRaw) draft.raw else draft.text

    private fun buildUi(): EditorUi {
        val dictating = mode == EditorMode.Recording || mode == EditorMode.Finishing
        var shown = shownText()
        var partial = ""
        if (dictating) {
            val liveText = live.liveText
            val p = live.partialText.takeIf { it.isNotEmpty() && liveText.endsWith(it) } ?: ""
            val settled = liveText.dropLast(p.length).trimEnd()
            shown = shown.trimEnd() + VoiceDraft.separator(shown, liveText).takeIf { settled.isNotEmpty() || p.isNotEmpty() }.orEmpty() + settled
            partial = if (p.isEmpty()) "" else (if (settled.isNotEmpty()) " " else "") + p
        }
        val elapsed = when (val s = live) {
            is VoiceState.Connecting -> s.elapsedMs
            is VoiceState.Listening -> s.elapsedMs
            else -> 0L
        }
        val instruction = if (mode == EditorMode.InstructListening || mode == EditorMode.InstructFinishing) live.liveText else ""
        val question = if (showingRaw || mode != EditorMode.Idle) null else draft.questions.firstOrNull()
        return EditorUi(
            open = open, mode = mode, text = shown, partial = partial, elapsedMs = elapsed, tidyOn = tidyOn,
            showingRaw = showingRaw, canShowRaw = draft.raw.isNotBlank() && draft.raw != draft.text,
            canUndo = undo.isNotEmpty(), question = question,
            questionsLeft = if (question == null) 0 else draft.questions.size,
            instruction = instruction, message = message, hasDraft = !draft.isEmpty,
        )
    }

    private fun render() = host.render(buildUi())

    private fun showMessage(text: String) {
        clearMessage()
        message = text
        cancelMessage = host.schedule(MESSAGE_MS) {
            cancelMessage = null
            message = null
            render()
        }
        render()
    }

    private fun clearMessage() {
        cancelMessage?.invoke()
        cancelMessage = null
        message = null
    }

    companion object {
        const val MESSAGE_MS = 3_000L
        const val MAX_UNDO = 50
        const val HINT_INSTRUCT = "Hold and say an edit"
        const val DIDNT_CATCH = "Didn't catch that"
    }
}
