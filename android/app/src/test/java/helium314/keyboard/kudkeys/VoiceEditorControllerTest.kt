// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import com.kudcrafts.kudkeys.voice.Fixed
import com.kudcrafts.kudkeys.voice.Segment
import com.kudcrafts.kudkeys.voice.TextResult
import com.kudcrafts.kudkeys.voice.Tidied
import com.kudcrafts.kudkeys.voice.TidyQuestion
import com.kudcrafts.kudkeys.voice.VoiceErrorKind
import com.kudcrafts.kudkeys.voice.VoiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Full voice mode's state machine (spec item 1), with a fake voice client, fake text endpoints and a memory store. */
class VoiceEditorControllerTest {
    private val engine = FakeVoiceEngine()
    private val text = FakeTextService()
    private val store = MemoryDraftStore()
    private val scheduler = FakeScheduler()
    private val inserted = mutableListOf<String>()
    private var private = false
    private var token = true
    private var mic = true
    private var permissionAsks = 0
    private var last = EditorUi()

    private inner class Host : VoiceEditorController.Host {
        override fun hasVoiceToken() = token
        override fun hasMicPermission() = mic
        override fun requestMicPermission() { permissionAsks++ }
        override fun isPrivateField() = private
        override fun insert(text: String) { inserted += text }
        override fun render(ui: EditorUi) { last = ui }
        override fun haptic() = Unit
        override fun schedule(delayMs: Long, block: () -> Unit) = scheduler.schedule(delayMs, block)
    }

    private fun newController() =
        VoiceEditorController(engine, text, store, Host(), CoroutineScope(Dispatchers.Unconfined))

    private var editor = newController()

    private val ramble = "pertama cek feno kedua kirim invoice"
    private val tidied = "Pertama, cek Feno.\n\n1. Cek Feno\n2. Kirim invoice"
    private val feno = tidied.indexOf("Feno")

    private fun tidyWithQuestion() {
        text.tidyResult = {
            TextResult.Ok(Tidied(tidied, listOf(
                TidyQuestion("q1", "Feno", feno, feno + 4, listOf("Fenno", "Feno")),
                TidyQuestion("q2", "invoice", tidied.indexOf("invoice"), tidied.indexOf("invoice") + 7, listOf("faktur")),
            )))
        }
    }

    private fun holdInstruct(said: String) {
        editor.onInstructDown()
        scheduler.fire(QuickVoiceController.HOLD_MS)
        engine.state.value = VoiceState.Listening(500, said, 0.4f)
        editor.onInstructUp()
        engine.state.value = VoiceState.Done(said)
    }

    @Test fun recordStopTidyQuestionInstructUndoInsert() {
        tidyWithQuestion()
        editor.open()
        assertEquals(listOf("start"), engine.calls) // recording starts at once
        assertEquals(EditorMode.Recording, last.mode)
        assertTrue(last.open)

        // live text: settled words plus the dimmed partial
        engine.state.value = VoiceState.Listening(4200, "pertama cek feno kedua", 0.4f, partial = "kedua")
        assertEquals("pertama cek feno", last.text)
        assertEquals(" kedua", last.partial)
        assertEquals(4200, last.elapsedMs)

        editor.onMicButton() // stop
        assertEquals("stop", engine.calls.last())
        engine.state.value = VoiceState.Done(ramble)
        assertEquals(listOf("tidy:$ramble"), text.calls)
        assertEquals(EditorMode.Idle, last.mode)
        assertEquals(tidied, last.text)
        assertTrue(last.canShowRaw)

        // one card at a time
        assertEquals("q1", last.question?.id)
        assertEquals(2, last.questionsLeft)
        editor.answer("Fenno")
        assertEquals(tidied.replaceFirst("Feno", "Fenno"), last.text)
        assertEquals("q2", last.question?.id) // offsets shifted by one
        assertEquals("invoice", last.text.substring(last.question!!.start, last.question!!.end))
        editor.answer(null) // keep as is
        assertNull(last.question)
        val answered = last.text

        // raw view shows the dictation and hides cards
        editor.toggleRaw()
        assertTrue(last.showingRaw)
        assertEquals(ramble, last.text)
        editor.toggleRaw()
        assertEquals(answered, last.text)

        // spoken edit
        text.instructResult = { _, t -> TextResult.Ok(t.substringBefore("\n\n")) }
        holdInstruct("hapus list-nya")
        assertEquals("instruct:hapus list-nya|$answered", text.calls.last())
        assertEquals("Pertama, cek Fenno.", last.text)

        // undo steps back: the instruct, then the answer
        editor.undo()
        assertEquals(answered, last.text)
        editor.undo()
        assertEquals(tidied, last.text)
        editor.answer("Fenno")
        editor.insert()
        assertEquals(listOf(tidied.replaceFirst("Feno", "Fenno")), inserted)
        assertFalse(last.open)
        assertFalse(last.hasDraft)
        assertNull(store.json)
    }

    @Test fun tidyOff_keepsRawAndIsRemembered() {
        editor.setTidy(false)
        assertFalse(store.tidyOn)
        editor.open()
        engine.say(ramble)
        assertEquals(emptyList(), text.calls)
        assertEquals(ramble, last.text)
        assertFalse(last.canShowRaw)
        assertFalse(newController().ui.tidyOn)
    }

    @Test fun continueDictatingAppends() {
        editor.setTidy(false)
        editor.open()
        engine.say("satu dua")
        editor.onMicButton() // continue
        assertEquals(EditorMode.Recording, last.mode)
        engine.state.value = VoiceState.Listening(100, "tiga", 0.4f)
        assertEquals("satu dua tiga", last.text)
        editor.onMicButton()
        engine.state.value = VoiceState.Done("tiga")
        assertEquals("satu dua tiga", last.text)
        editor.undo()
        assertEquals("satu dua", last.text)
    }

    @Test fun appendedQuestionsAreOffset() {
        editor.setTidy(false)
        editor.open()
        engine.say("Halo.")
        editor.setTidy(true)
        text.tidyResult = { TextResult.Ok(Tidied("Ini Feno.", listOf(TidyQuestion("q", "Feno", 4, 8, listOf("Fenno"))))) }
        editor.onMicButton()
        engine.say("ini feno")
        assertEquals("Halo. Ini Feno.", last.text)
        assertEquals("Feno", last.text.substring(last.question!!.start, last.question!!.end))
    }

    @Test fun draftSurvivesKeyboardRestart() {
        tidyWithQuestion()
        editor.open()
        engine.say(ramble)
        editor.answer("Fenno")
        editor.close()
        assertFalse(last.open)
        assertTrue(last.hasDraft) // the Mic shows its dot
        editor.destroy()

        // the IME is recreated: the draft is loaded from the store
        engine.calls.clear()
        editor = newController()
        assertTrue(editor.hasDraft)
        editor.open()
        assertEquals(emptyList(), engine.calls) // reopening shows the draft, it doesn't record
        assertEquals(tidied.replaceFirst("Feno", "Fenno"), last.text)
        assertEquals("q2", last.question?.id)
        editor.toggleRaw()
        assertEquals(ramble, last.text)
    }

    @Test fun discardClears() {
        editor.setTidy(false)
        editor.open()
        engine.say("hapus aku")
        editor.discard()
        assertFalse(last.open)
        assertFalse(editor.hasDraft)
        assertNull(store.json)
        assertEquals(emptyList(), inserted)
    }

    @Test fun closeWhileRecordingKeepsTheWords() {
        editor.setTidy(false)
        editor.open()
        engine.state.value = VoiceState.Listening(100, "jangan hilang", 0.4f)
        editor.onHidden()
        assertEquals("stop", engine.calls.last())
        engine.state.value = VoiceState.Done("jangan hilang")
        assertFalse(last.open)
        assertEquals("jangan hilang", editor.currentDraft.text)
    }

    @Test fun privateFieldSendsPrivate() {
        private = true
        editor.open()
        assertEquals(true, engine.lastStart?.private)
        engine.say("rahasia")
        assertEquals(listOf(true), text.privates)
    }

    @Test fun tidyFailureKeepsRawWithMessage() {
        text.tidyResult = { TextResult.Unavailable }
        editor.open()
        engine.say("kata kata")
        assertEquals("kata kata", last.text)
        assertEquals("Tidy unavailable — kept as said", last.message)
        scheduler.fire(VoiceEditorController.MESSAGE_MS)
        assertNull(last.message)
    }

    @Test fun tidyingShowsBusyAndInsertWaits() {
        text.hold = CompletableDeferred()
        editor.open()
        engine.say("tunggu")
        assertEquals(EditorMode.Tidying, last.mode)
        assertFalse(last.canInsert)
        editor.insert()
        assertEquals(emptyList(), inserted)
        text.release()
        assertEquals(EditorMode.Idle, last.mode)
        assertTrue(last.canInsert)
    }

    @Test fun voiceErrorKeepsWhatArrived() {
        editor.setTidy(false)
        editor.open()
        engine.state.value = VoiceState.Error(VoiceErrorKind.NoConnection, "x", "sampai sini")
        assertEquals("sampai sini", last.text)
        assertEquals("Voice: no connection", last.message)
    }

    @Test fun shortInstructTapHints_emptyInstructionIgnored() {
        editor.setTidy(false)
        editor.open()
        engine.say("teks")
        editor.onInstructDown()
        editor.onInstructUp()
        assertEquals(VoiceEditorController.HINT_INSTRUCT, last.message)
        holdInstruct("")
        assertEquals(VoiceEditorController.DIDNT_CATCH, last.message)
        assertEquals("teks", last.text)
        assertFalse(text.calls.any { it.startsWith("instruct") })
    }

    @Test fun noTokenOrMic() {
        token = false
        editor.open()
        assertEquals(emptyList(), engine.calls)
        assertEquals(VoiceErrorKind.NoToken.userMessage, last.message)
        token = true
        mic = false
        editor.onMicButton()
        assertEquals(1, permissionAsks)
    }

    @Test fun draftJsonRoundTrip() {
        val d = VoiceDraft("a\n\nb \"q\"", "a b", listOf(TidyQuestion("x", "b", 3, 4, listOf("B", "bee"))))
        assertEquals(d, VoiceDraft.fromJson(d.toJson()))
        assertNull(VoiceDraft.fromJson("{broken"))
    }

    // ---- v0.3 review fixes ---------------------------------------------------------------------------------

    /** H1: the words are in the store the moment the dictation ends, before tidy answers; tidy then swaps them. */
    @Test fun rawIsSavedAtStop_beforeTidyAnswers_thenSwapped() {
        tidyWithQuestion()
        text.hold = CompletableDeferred()
        editor.open()
        engine.say(ramble)
        assertEquals(EditorMode.Tidying, last.mode)
        val saved = VoiceDraft.fromJson(store.json!!)!!
        assertEquals(ramble, saved.text)
        assertEquals(listOf(TextSpan(0, ramble.length)), saved.untidied)
        text.release()
        assertEquals(tidied, last.text)
        assertEquals(emptyList(), editor.currentDraft.untidied)
        assertEquals(tidied, VoiceDraft.fromJson(store.json!!)!!.text)
        assertFalse(last.canTidy)
        editor.undo() // one Undo steps back over the whole dictation, as before
        assertEquals("", last.text)
    }

    /** H1: the keyboard dies while tidy runs: the next keyboard has the words, and offers Tidy. */
    @Test fun killedWhileTidying_keepsTheWords() {
        text.hold = CompletableDeferred()
        editor.open()
        engine.say("tiga menit ngomong")
        editor.destroy()
        editor = newController()
        editor.open()
        assertEquals("tiga menit ngomong", last.text)
        assertTrue(last.canTidy)
    }

    /** H1: a recording's words are saved every few seconds, and at destroy. */
    @Test fun recordingWordsArePersistedWhileRecording_andAtDestroy() {
        editor.setTidy(false)
        editor.open()
        engine.state.value = VoiceState.Listening(2000, "satu dua", 0.4f)
        scheduler.fire(VoiceEditorController.PERSIST_MS)
        assertEquals("satu dua", VoiceDraft.fromJson(store.json!!)!!.text)
        assertEquals("", editor.currentDraft.text) // only stored: the draft gets them at Done
        engine.state.value = VoiceState.Listening(4000, "satu dua tiga", 0.4f)
        editor.destroy()
        val d = VoiceDraft.fromJson(store.json!!)!!
        assertEquals("satu dua tiga", d.text)
        assertEquals(listOf(TextSpan(0, 13)), d.untidied)
    }

    /** H4: a failed or busy tidy leaves a Tidy button; the message says when to try again. */
    @Test fun tidyBusy_offersTidy_withRetryAfter() {
        text.tidyResult = { TextResult.RateLimited(20) }
        editor.open()
        engine.say("kata kata")
        assertEquals("kata kata", last.text)
        assertEquals("Tidy busy — try again in 20 s", last.message)
        assertTrue(last.canTidy)
        text.tidyResult = { TextResult.Ok(Tidied("Kata-kata.", emptyList())) }
        editor.tidyNow()
        assertEquals("Kata-kata.", last.text)
        assertFalse(last.canTidy)
        editor.undo() // the Tidy button's tidy is one Undo
        assertEquals("kata kata", last.text)
        assertTrue(last.canTidy)
    }

    @Test fun tidyTooLong_keptAsIs() {
        text.tidyResult = { TextResult.TooLong }
        editor.open()
        engine.say("panjang")
        assertEquals(TextAction.TOO_LONG_TO_TIDY, last.message)
        assertEquals("panjang", last.text)
    }

    /** H4: tidy off keeps the words as said, and Tidy tidies just that part of the draft. */
    @Test fun tidyOff_thenTidyButton_tidiesOnlyTheUntidiedPart() {
        text.tidyResult = { TextResult.Ok(Tidied("Halo semua.", emptyList())) }
        editor.open()
        engine.say("halo semua")
        editor.setTidy(false)
        editor.onMicButton()
        engine.say("ini mentah")
        assertEquals("Halo semua. ini mentah", last.text)
        assertTrue(last.canTidy)
        text.tidyResult = { TextResult.Ok(Tidied("Ini mentah.", emptyList())) }
        editor.tidyNow()
        assertEquals(listOf("tidy:halo semua", "tidy:ini mentah"), text.calls)
        assertEquals("Halo semua. Ini mentah.", last.text)
    }

    /** M3: dictating more while Raw is shown keeps the tidied draft and its answers. */
    @Test fun continueWhileRawShown_keepsTheTidiedDraft() {
        tidyWithQuestion()
        editor.open()
        engine.say(ramble)
        editor.answer("Fenno")
        editor.toggleRaw()
        assertTrue(last.showingRaw)
        editor.setTidy(false)
        editor.onMicButton()
        assertFalse(last.showingRaw)
        engine.say("tambah lagi")
        assertEquals(tidied.replaceFirst("Feno", "Fenno") + "\n\ntambah lagi", last.text)
    }

    /** L2: tidied text that starts with whitespace still highlights the right word. */
    @Test fun leadingWhitespaceInTidyKeepsQuestionOffsets() {
        text.tidyResult = { TextResult.Ok(Tidied("  Cek Feno.", listOf(TidyQuestion("q1", "Feno", 6, 10, listOf("Fenno"))))) }
        editor.open()
        engine.say("cek feno")
        assertEquals("Cek Feno.", last.text)
        assertEquals("Feno", last.text.substring(last.question!!.start, last.question!!.end))
    }

    /** L6: privateness is the field's when recording started, not when tidy is sent. */
    @Test fun privateIsCapturedAtStart() {
        private = true
        text.hold = CompletableDeferred()
        editor.open()
        engine.state.value = VoiceState.Listening(100, "rahasia", 0.4f)
        private = false
        editor.onMicButton()
        engine.state.value = VoiceState.Done("rahasia")
        text.release()
        assertEquals(listOf(true), text.privates)
    }

    /** L5: the recogniser's confidence goes with tidy. */
    @Test fun segmentsGoToTidy() {
        editor.open()
        engine.state.value = VoiceState.Done("cek feno", listOf(Segment("cek feno", 0.4f)))
        assertEquals(listOf(listOf(Segment("cek feno", 0.4f))), text.segments)
    }

    /** V3: the top bar's ✦ Fix works on the editor's text. */
    @Test fun fixDraft_replacesTheTextWithUndo_andRefusesARewrite() {
        editor.setTidy(false)
        editor.open()
        engine.say("halo apa kabr")
        text.fixResult = { TextResult.Ok(Fixed("Halo, apa kabar?", true)) }
        editor.fixDraft()
        assertEquals(listOf("fix:halo apa kabr"), text.calls)
        assertEquals("Halo, apa kabar?", last.text)
        assertEquals(VoiceEditorController.FIXED, last.message)
        editor.undo()
        assertEquals("halo apa kabr", last.text)

        text.fixResult = { TextResult.ChangedTooMuch }
        editor.fixDraft()
        assertEquals(TextAction.FIX_CHANGED_TOO_MUCH, last.message)
        assertEquals("halo apa kabr", last.text)
        text.fixResult = { TextResult.Ok(Fixed(it, false)) }
        editor.fixDraft()
        assertEquals(TextActionsController.LOOKS_GOOD, last.message)
    }

    /** L3: a spoken edit of a draft over 20,000 chars is refused, not truncated. */
    @Test fun instructOnHugeDraftIsRefused() {
        editor.setTidy(false)
        editor.open()
        engine.say("x".repeat(20_001))
        holdInstruct("ringkas")
        assertEquals(TextAction.TOO_LONG_TO_EDIT, last.message)
        assertFalse(text.calls.any { it.startsWith("instruct") })
        assertEquals(20_001, last.text.length)
    }

    @Test fun draftSpans_shiftWithAnswers_andRoundTrip() {
        val d = VoiceDraft("Cek Feno. mentah sini", "cek feno mentah sini",
            listOf(TidyQuestion("q1", "Feno", 4, 8, listOf("Fenno"))), listOf(TextSpan(10, 21)))
        val a = d.answer(d.questions[0], "Fenno")
        assertEquals(listOf(TextSpan(11, 22)), a.untidied)
        assertEquals("mentah sini", a.textOf(a.untidied[0]))
        assertEquals(a, VoiceDraft.fromJson(a.toJson()))
        // a stale span (the text changed) is not replaced
        assertNull(a.tidySpan(TextSpan(11, 22), "other text!", "X", emptyList()))
        val t = a.tidySpan(TextSpan(11, 22), "mentah sini", "Mentah sini:\n\n- satu", emptyList())!!
        assertEquals("Cek Fenno.\n\nMentah sini:\n\n- satu", t.text) // a list after it: a blank line before it
    }
}
