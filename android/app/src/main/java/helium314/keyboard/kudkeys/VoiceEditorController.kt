// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import com.kudcrafts.kudkeys.voice.Segment
import com.kudcrafts.kudkeys.voice.TextClient
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
    /** Part of the draft is still as dictated (tidy off, failed or too busy): the editor offers "Tidy". */
    val canTidy: Boolean = false,
) {
    val isRecording get() = mode == EditorMode.Recording
    val isBusy get() = mode != EditorMode.Idle
    val canInsert get() = mode == EditorMode.Idle && text.isNotBlank()
}

enum class EditorMode { Idle, Recording, Finishing, Tidying, Fixing, InstructListening, InstructFinishing, Instructing }

/**
 * Full voice mode (spec item 1): the dictation editor that replaces the key area. Pure Kotlin, driven by the view
 * and the IME; [Host] does the Android parts. The draft is saved to [store] after every change and loaded at
 * construction, so it survives the keyboard closing and the process dying: the words of a recording are saved every
 * [PERSIST_MS] while it runs, and a stopped dictation is saved as said the moment it ends, before tidy is asked; the
 * tidied text replaces it when it arrives.
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
    private var cancelPersist: (() -> Unit)? = null
    /** Whether the field was private when the current dictation started (its tidy call says so). */
    private var dictationPrivate = false
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
        dictationPrivate = host.isPrivateField()
        if (engine.start(VoiceStart(private = dictationPrivate))) {
            mode = EditorMode.Recording
            // new words go after the draft as it is; the tidied text is shown again
            showingRaw = false
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

    private fun onDictationDone(said: String, segments: List<Segment>) {
        cancelPersistLive()
        val chunk = said.trim()
        if (chunk.isEmpty()) {
            mode = EditorMode.Idle
            store.save(draft) // drop the words saved while recording, if any
            return render()
        }
        // Saved as said at once: if the keyboard dies while tidy runs, the words are in the draft.
        appendChunk(chunk, chunk, emptyList(), untidied = true)
        val span = draft.untidied.lastOrNull()
        if (!tidyOn || span == null) {
            mode = EditorMode.Idle
            return render()
        }
        tidy(listOf(span), dictationPrivate, segments, pushUndo = false)
    }

    /**
     * Tidies [spans] of the draft (untidied parts), the last first so earlier offsets stay put, each replaced only if
     * it still holds what was sent. [pushUndo]: the untidied draft is one Undo away (the Tidy button); after a
     * dictation it isn't (Undo steps back over the whole dictation, as before).
     */
    private fun tidy(spans: List<TextSpan>, private: Boolean, segments: List<Segment>, pushUndo: Boolean) {
        mode = EditorMode.Tidying
        clearMessage()
        render()
        val before = draft
        job = scope.launch {
            var failure: TextResult<*>? = null
            for (span in spans.sortedByDescending { it.start }) {
                val sent = draft.textOf(span) ?: continue
                val r = text.tidy(sent, private, if (spans.size == 1) segments else emptyList())
                if (r !is TextResult.Ok) { failure = r; break }
                draft.tidySpan(span, sent, r.value.text, shift(r.value.questions, -leadingSpace(r.value.text)))
                    ?.let { setDraft(it, pushUndo = false) }
            }
            job = null
            mode = EditorMode.Idle
            if (pushUndo && draft != before) pushUndoOf(before)
            if (failure != null) showMessage(TextAction.Tidy.errorMessage(failure)) else render()
        }
    }

    /** Tidy (the editor's button): tidies whatever is still as dictated. */
    fun tidyNow() {
        if (mode != EditorMode.Idle || showingRaw || draft.untidied.isEmpty()) return
        tidy(draft.untidied, host.isPrivateField(), emptyList(), pushUndo = true)
    }

    private fun leadingSpace(s: String) = s.length - s.trimStart().length

    private fun appendChunk(tidied: String, raw: String, questions: List<TidyQuestion>, untidied: Boolean = false) {
        // Always onto the draft as it is (also while Raw is shown: its tidied text and answers stay).
        val trimmed = leadingSpace(tidied)
        setDraft(draft.append(tidied.trim(), raw, shift(questions, -trimmed), untidied), pushUndo = true)
        showingRaw = false
    }

    private fun shift(qs: List<TidyQuestion>, by: Int) =
        if (by == 0) qs else qs.mapNotNull { q -> q.copy(start = q.start + by, end = q.end + by).takeIf { it.start >= 0 } }

    /** While recording: save the draft with the words so far every [PERSIST_MS], so a killed keyboard keeps them. */
    private fun schedulePersistLive() {
        if (cancelPersist != null) return
        cancelPersist = host.schedule(PERSIST_MS) {
            cancelPersist = null
            if (mode != EditorMode.Recording && mode != EditorMode.Finishing) return@schedule
            val words = live.liveText.trim()
            if (words.isNotEmpty()) store.save(draft.append(words, words, emptyList(), untidiedChunk = true))
            schedulePersistLive()
        }
    }

    private fun cancelPersistLive() {
        cancelPersist?.invoke()
        cancelPersist = null
    }

    // ---- ✦ Fix on the draft (the top bar's Fix while the editor is open) --------------------------------------

    /** Fixes typos in the editor's text, like ✦ Fix does in a field. Undo puts the text back. */
    fun fixDraft() {
        if (mode != EditorMode.Idle) return
        val current = shownText()
        if (current.isBlank()) return showMessage(TextActionsController.NOTHING_TO_FIX)
        if (current.length > TextClient.MAX_FIX) return showMessage(TextAction.Fix.errorMessage(TextResult.TooLong))
        mode = EditorMode.Fixing
        clearMessage()
        render()
        val private = host.isPrivateField()
        job = scope.launch {
            val r = text.fix(current, private)
            job = null
            mode = EditorMode.Idle
            when {
                r !is TextResult.Ok -> showMessage(TextAction.Fix.errorMessage(r))
                !r.value.changed || r.value.text == current -> showMessage(TextActionsController.LOOKS_GOOD)
                shownText() != current -> showMessage(TextActionsController.TEXT_CHANGED)
                else -> {
                    setDraft(VoiceDraft(r.value.text, draft.raw), pushUndo = true)
                    showingRaw = false
                    showMessage(FIXED)
                }
            }
        }
    }

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
        val current = shownText()
        if (current.length > TextClient.MAX_TIDY) {
            mode = EditorMode.Idle
            return showMessage(TextAction.TOO_LONG_TO_EDIT)
        }
        mode = EditorMode.Instructing
        render()
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
            is VoiceState.Connecting, is VoiceState.Listening, is VoiceState.Finishing -> if (isListening()) {
                if (isDictating()) schedulePersistLive()
                render()
            }
            is VoiceState.Done -> {
                engine.reset()
                live = VoiceState.Idle
                when (mode) {
                    EditorMode.Recording, EditorMode.Finishing -> onDictationDone(s.text, s.segments)
                    EditorMode.InstructListening, EditorMode.InstructFinishing -> onInstructionDone(s.text)
                    else -> Unit
                }
            }
            is VoiceState.Error -> {
                engine.reset()
                live = VoiceState.Idle
                val wasDictating = isDictating()
                val wasInstructing = mode == EditorMode.InstructListening || mode == EditorMode.InstructFinishing
                if (wasDictating || wasInstructing) mode = EditorMode.Idle
                if (wasDictating) cancelPersistLive()
                // keep the words that arrived before the failure (as said: Tidy can tidy them); a cut-off spoken
                // edit is never applied
                if (wasDictating && s.text.isNotBlank()) appendChunk(s.text.trim(), s.text.trim(), emptyList(), untidied = true)
                else if (wasDictating) store.save(draft)
                if (wasDictating || wasInstructing || open) showMessage(s.kind.userMessage) else render()
            }
            VoiceState.Idle -> if (isListening()) { mode = EditorMode.Idle; render() }
        }
    }

    private fun isListening() = mode == EditorMode.Recording || mode == EditorMode.Finishing ||
        mode == EditorMode.InstructListening || mode == EditorMode.InstructFinishing

    private fun isDictating() = mode == EditorMode.Recording || mode == EditorMode.Finishing

    /**
     * The IME is going away. A recording's words so far join the draft as said (the session can't finish any more);
     * a tidy in flight is dropped, but its words were saved when it started.
     */
    fun destroy() {
        cancelInstructHold()
        cancelPersistLive()
        if (isDictating()) {
            val words = live.liveText.trim()
            if (words.isNotEmpty()) appendChunk(words, words, emptyList(), untidied = true)
            mode = EditorMode.Idle
        }
        job?.cancel()
        if (engine.state.value.isActive) engine.cancel()
        clearMessage()
        collector.cancel()
        engine.close()
    }

    // ---- state ------------------------------------------------------------------------------------------------

    private fun setDraft(d: VoiceDraft, pushUndo: Boolean) {
        if (pushUndo && d != draft) pushUndoOf(draft)
        draft = d
        store.save(d)
    }

    private fun pushUndoOf(d: VoiceDraft) {
        undo.addLast(d)
        while (undo.size > MAX_UNDO) undo.removeFirst()
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
            canTidy = mode == EditorMode.Idle && !showingRaw && draft.untidied.isNotEmpty(),
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
        /** How often a recording's words are saved while it runs. */
        const val PERSIST_MS = 2_000L
        const val FIXED = "Fixed"
        const val MAX_UNDO = 50
        const val HINT_INSTRUCT = "Hold and say an edit"
        const val DIDNT_CATCH = "Didn't catch that"
    }
}
