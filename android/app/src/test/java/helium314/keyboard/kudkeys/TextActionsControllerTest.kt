// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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

    private val controller = TextActionsController(engine, text, InputConnectionFieldEditor({ ic }), object : TextActionsController.Host {
        override fun isSignedIn() = signedIn
        override fun isPrivateField() = private
        override fun hasMicPermission() = true
        override fun requestMicPermission() = Unit
        override fun haptic() = Unit
        override fun render(state: TextBarState) { states += state }
        override fun schedule(delayMs: Long, block: () -> Unit) = scheduler.schedule(delayMs, block)
    }, CoroutineScope(Dispatchers.Unconfined))

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
            TextResult.RateLimited(5) to "Slow down a moment",
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
}
