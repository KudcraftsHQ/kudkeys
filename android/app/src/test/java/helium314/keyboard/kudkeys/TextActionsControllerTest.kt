// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.kudcrafts.kudkeys.voice.Fixed
import com.kudcrafts.kudkeys.voice.TextResult
import com.kudcrafts.kudkeys.voice.VoiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ✦ Fix and Instruct (spec items 2, 3) against a real InputConnection: an EditText's, under Robolectric. Replace in
 * place, undo to the exact text and cursor, error mapping, cancel on another field.
 */
@RunWith(RobolectricTestRunner::class)
class TextActionsControllerTest {
    private val edit = EditText(ApplicationProvider.getApplicationContext())
    private val ic: InputConnection = edit.onCreateInputConnection(EditorInfo())!!
    private val engine = FakeVoiceEngine()
    private val text = FakeTextService()
    private val scheduler = FakeScheduler()
    private var signedIn = true
    private var private = false
    private val states = mutableListOf<TextBarState>()
    private val last get() = states.last()

    private val host = object : TextActionsController.Host {
        override fun isSignedIn() = signedIn
        override fun isPrivateField() = private
        override fun hasMicPermission() = true
        override fun requestMicPermission() = Unit
        override fun haptic() = Unit
        override fun render(state: TextBarState) { states += state }
        override fun schedule(delayMs: Long, block: () -> Unit) = scheduler.schedule(delayMs, block)
    }
    private fun controllerOn(field: FieldEditor) = TextActionsController(engine, text, field, host, CoroutineScope(Dispatchers.Unconfined))
    private val controller = controllerOn(InputConnectionFieldEditor({ ic }))

    private fun field(s: String, selStart: Int = s.length, selEnd: Int = selStart) {
        edit.setText(s)
        edit.setSelection(selStart, selEnd)
    }
    private val fieldText get() = edit.text.toString()

    @Test fun fixWholeField_thenUndoRestoresTextAndCursor() {
        field("halo apa kabr", selStart = 5)
        text.fixResult = { TextResult.Ok(Fixed("Halo, apa kabar?", true)) }
        controller.onFix()
        assertEquals(listOf("fix:halo apa kabr"), text.calls)
        assertEquals("Halo, apa kabar?", fieldText)
        assertEquals(TextBarState.Message(TextActionsController.FIXED, undo = true), last)
        assertTrue(scheduler.pending.any { it.first == TextActionsController.UNDO_MS })

        controller.onMessageTapped()
        assertEquals("halo apa kabr", fieldText)
        assertEquals(5, edit.selectionStart)
        assertEquals(5, edit.selectionEnd)
        assertEquals(TextBarState.Idle, last)
    }

    @Test fun fixSelectionOnly() {
        field("Pagi. ini salah ketk. Oke.", selStart = 6, selEnd = 21)
        text.fixResult = { TextResult.Ok(Fixed("Ini salah ketik.", true)) }
        controller.onFix()
        assertEquals("fix:ini salah ketk.", text.calls.single())
        assertEquals("Pagi. Ini salah ketik. Oke.", fieldText)
        assertEquals(6, edit.selectionStart)
        assertEquals(22, edit.selectionEnd) // the fixed text stays selected
        controller.undo()
        assertEquals("Pagi. ini salah ketk. Oke.", fieldText)
        assertEquals(6 to 21, edit.selectionStart to edit.selectionEnd)
    }

    @Test fun fixUnchanged_looksGood() {
        field("Sudah benar.")
        controller.onFix()
        assertEquals("Sudah benar.", fieldText)
        assertEquals(TextBarState.Message(TextActionsController.LOOKS_GOOD), last)
    }

    @Test fun fixReadsAtMost4000AroundTheCursor() {
        val long = "a".repeat(5000) + "b".repeat(5000)
        field(long, selStart = 5000)
        controller.onFix()
        val sent = text.calls.single().removePrefix("fix:")
        assertEquals(4000, sent.length)
        assertEquals("a".repeat(2000) + "b".repeat(2000), sent)
    }

    @Test fun spinnerWhileWaiting_andTextChangedMeanwhile() {
        field("satu")
        text.hold = CompletableDeferred()
        text.fixResult = { TextResult.Ok(Fixed("Satu.", true)) }
        controller.onFix()
        assertEquals(TextBarState.Busy(TextAction.Fix), last)
        controller.onFix() // ignored while busy
        assertEquals(1, text.calls.size)
        field("sapu") // edited while waiting
        text.release()
        assertEquals("sapu", fieldText)
        assertEquals(TextBarState.Message(TextActionsController.TEXT_CHANGED), last)
    }

    @Test fun anotherFieldCancels() {
        controller.onField("a")
        field("satu")
        text.hold = CompletableDeferred()
        text.fixResult = { TextResult.Ok(Fixed("Satu.", true)) }
        controller.onFix()
        controller.onField("b")
        text.release()
        assertEquals("satu", fieldText)
        assertEquals(TextBarState.Idle, last)
    }

    @Test fun errorMapping() {
        field("x")
        val cases = listOf(
            TextResult.SignedOut to "Sign in to use Fix",
            TextResult.RateLimited(5) to "Busy — try again in 5 s",
            TextResult.RateLimited(null) to "Slow down a moment",
            TextResult.TooLong to "Too long to fix",
            TextResult.ChangedTooMuch to "Fix changed too much — kept yours",
            TextResult.Unavailable to "Fix unavailable",
            TextResult.NoNetwork to "Fix unavailable",
            TextResult.NotSignedIn to "Sign in from settings",
        )
        for ((r, msg) in cases) {
            text.fixResult = { r }
            controller.onFix()
            assertEquals(TextBarState.Message(msg), last, "for $r")
        }
        assertEquals("Instruct unavailable", TextAction.Instruct.errorMessage(TextResult.Unavailable))
        assertEquals("Sign in to use Instruct", TextAction.Instruct.errorMessage(TextResult.SignedOut))
    }

    @Test fun signedOut_nothingSent() {
        signedIn = false
        field("x")
        controller.onFix()
        assertEquals(emptyList(), text.calls)
        assertEquals(TextBarState.Message("Sign in from settings"), last)
    }

    private fun holdInstruct(said: String) {
        controller.onInstructDown()
        scheduler.fire(QuickVoiceController.HOLD_MS)
        engine.state.value = VoiceState.Listening(800, said, 0.4f)
        controller.onInstructUp()
        engine.state.value = VoiceState.Done(said)
    }

    @Test fun instructReplacesWithUndo_privateFlag() {
        private = true
        field("aku telat ya")
        text.instructResult = { _, t -> TextResult.Ok("Maaf, $t.") }
        controller.onInstructDown()
        scheduler.fire(QuickVoiceController.HOLD_MS)
        assertEquals(true, engine.lastStart?.private)
        engine.state.value = VoiceState.Listening(800, "lebih sopan", 0.4f)
        assertEquals(TextBarState.Listening(800, "lebih sopan"), last)
        controller.onInstructUp()
        assertEquals(TextBarState.Busy(TextAction.Instruct), last)
        engine.state.value = VoiceState.Done("lebih sopan")
        assertEquals("instruct:lebih sopan|aku telat ya", text.calls.single())
        assertEquals(listOf(true), text.privates)
        assertEquals("Maaf, aku telat ya.", fieldText)
        assertEquals(TextBarState.Message(TextActionsController.DONE, undo = true), last)
        controller.onMessageTapped()
        assertEquals("aku telat ya", fieldText)
    }

    @Test fun instructOnEmptyFieldWrites() {
        field("")
        text.instructResult = { _, _ -> TextResult.Ok("Maaf, aku telat 10 menit ya.") }
        holdInstruct("tulis aku telat sepuluh menit")
        assertEquals("instruct:tulis aku telat sepuluh menit|", text.calls.single())
        assertEquals("Maaf, aku telat 10 menit ya.", fieldText)
        assertEquals(fieldText.length, edit.selectionStart)
    }

    @Test fun instructShortTapHints() {
        controller.onInstructDown()
        controller.onInstructUp()
        assertEquals(emptyList(), engine.calls)
        assertEquals(TextBarState.Message(TextActionsController.HINT_INSTRUCT), last)
    }

    @Test fun undoRefusesWhenTextChanged() {
        field("satu")
        text.fixResult = { TextResult.Ok(Fixed("Satu.", true)) }
        controller.onFix()
        field("lain sama sekali")
        controller.onMessageTapped()
        assertEquals("lain sama sekali", fieldText)
        assertEquals(TextBarState.Message(TextActionsController.CANT_UNDO), last)
    }

    @Test fun undoExpiresAfter10s() {
        field("satu")
        text.fixResult = { TextResult.Ok(Fixed("Satu.", true)) }
        controller.onFix()
        scheduler.fire(TextActionsController.UNDO_MS)
        assertEquals(TextBarState.Idle, last)
        assertEquals(false, controller.undo())
        assertEquals("Satu.", fieldText)
    }

    // ---- v0.3 review fixes ---------------------------------------------------------------------------------

    /**
     * H2: an app without extracted text, and the IME's idea of the cursor is 7 chars off. The read check passes (it
     * reads around the same wrong cursor), but the replace sees the range doesn't hold the text and writes nothing.
     */
    @Test fun wrongOffsetsWithoutExtractedText_abortInsteadOfOverwriting() {
        field("Halo dunia. ini salah ketk.", selStart = 10)
        val noExtracted = object : InputConnectionWrapper(ic, false) {
            override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
        }
        val drifted = controllerOn(InputConnectionFieldEditor({ noExtracted }, fallbackSelection = { 17 to 17 }))
        text.fixResult = { TextResult.Ok(Fixed("Halo dunia. Ini salah ketik.", true)) }
        drifted.onFix()
        assertEquals("Halo dunia. ini salah ketk.", fieldText)
        assertEquals(TextBarState.Message(TextActionsController.CANT_FIX_FIELD), last)
    }

    /** M1: a window inside a long field starts and ends at sentence boundaries, never mid-word. */
    @Test fun longFieldWindowSnapsToSentences() {
        val sentence = "Ini kalimat yang cukup panjang untuk tes. "
        val long = sentence.repeat(200) // 8,400 chars
        field(long, selStart = 4200)
        controller.onFix()
        val sent = text.calls.single().removePrefix("fix:")
        assertTrue(sent.length <= 4000)
        assertTrue(sent.startsWith("Ini kalimat"), sent.take(20))
        assertTrue(sent.endsWith("untuk tes."), sent.takeLast(20))
    }

    /** M2: the cut never splits an emoji. */
    @Test fun windowNeverSplitsAnEmoji() {
        val emoji = "\uD83D\uDE00"
        // no spaces, so no boundary to snap to: the cut lands right on the emoji
        val long = "a".repeat(2999) + emoji + "b".repeat(5000)
        // the window starts 2,000 before the cursor: at 3,000, between the emoji's two halves
        field(long, selStart = 5000)
        controller.onFix()
        val sent = text.calls.single().removePrefix("fix:")
        assertFalse(sent.any { Character.isSurrogate(it) }, "half an emoji was sent")
        assertTrue(sent.startsWith("b"))
    }

    /** L4: a selection over 4,000 chars: the first 4,000 are fixed, and the message says so. */
    @Test fun longSelection_fixesTheStart_andSaysSo() {
        val sel = "kata ".repeat(1000) // 5,000 chars
        field(sel + "akhir", selStart = 0, selEnd = sel.length)
        text.fixResult = { TextResult.Ok(Fixed(it.replaceFirst("kata", "Kata"), true)) }
        controller.onFix()
        assertEquals(4000, text.calls.single().removePrefix("fix:").length)
        assertEquals(TextBarState.Message(TextActionsController.FIXED_PART, undo = true), last)
        assertTrue(fieldText.startsWith("Kata kata"))
        assertTrue(fieldText.endsWith("kata akhir"))
    }

    @Test fun longSelection_instructRefuses() {
        val sel = "kata ".repeat(1000)
        field(sel, selStart = 0, selEnd = sel.length)
        holdInstruct("ringkas")
        assertFalse(text.calls.any { it.startsWith("instruct") })
        assertEquals(TextBarState.Message(TextActionsController.SELECTION_TOO_LONG), last)
    }

    /** M7: a voice error mid-instruction never applies the cut-off instruction. */
    @Test fun instructVoiceError_isNotApplied() {
        field("satu dua tiga")
        controller.onInstructDown()
        scheduler.fire(QuickVoiceController.HOLD_MS)
        engine.state.value = VoiceState.Listening(800, "hapus semua", 0.4f)
        engine.state.value = VoiceState.Error(com.kudcrafts.kudkeys.voice.VoiceErrorKind.NoConnection, "x", "hapus semua")
        assertEquals(emptyList(), text.calls)
        assertEquals("satu dua tiga", fieldText)
        assertEquals(TextBarState.Message("Voice: no connection"), last)
    }

    /** M5: input restarted (same EditText, another chat): the result of a call in flight is dropped. */
    @Test fun inputRestartedInSameField_dropsTheResult() {
        controller.onField("chat")
        field("")
        text.hold = CompletableDeferred()
        text.instructResult = { _, _ -> TextResult.Ok("Oke, aku otw.") }
        holdInstruct("bilang aku otw")
        controller.onInputStarted()
        text.release()
        assertEquals("", fieldText)
        assertEquals(TextBarState.Idle, last)
    }

    /** M5: an empty field that got text meanwhile is not written into. */
    @Test fun emptyFieldChangedMeanwhile_notWritten() {
        field("")
        text.hold = CompletableDeferred()
        text.instructResult = { _, _ -> TextResult.Ok("Oke.") }
        holdInstruct("bilang oke")
        field("ketik sendiri")
        text.release()
        assertEquals("ketik sendiri", fieldText)
        assertEquals(TextBarState.Message(TextActionsController.TEXT_CHANGED), last)
    }
}
