// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.kudkeys.KudHoldDelete
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Typing → Delete words when held: character repeats first, then a word per repeat. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowLocaleManagerCompat::class,
    ShadowInputMethodManager2::class,
    ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class,
    ShadowHandler::class,
    ShadowFacilitator2::class,
])
class KudHoldDeleteTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val text get() = ShadowInputMethodService.text

    @BeforeTest fun reset() {
        currentScript = ScriptUtils.SCRIPT_LATIN
        ShadowInputMethodService.reset()
        latinIME.prefs().edit { clear() }
    }

    // ---- the word rule (pure) ----

    private fun deleteWord(s: String) = s.dropLast(KudHoldDelete.charCountToDelete(s))

    @Test fun wordRule() {
        assertEquals("saya mau ", deleteWord("saya mau makan"))
        assertEquals("saya ", deleteWord("saya mau "))
        assertEquals("saya ", deleteWord("saya mau   "))
        assertEquals("ini ", deleteWord("ini anak-anak"))
        assertEquals("I ", deleteWord("I don't"))
        assertEquals("I ", deleteWord("I don’t"))
        assertEquals("halo", deleteWord("halo, "))
        assertEquals("halo", deleteWord("halo!!!"))
        assertEquals("wah ", deleteWord("wah 😀😀"))
        assertEquals("wah ", deleteWord("wah 👍🏽"))
        assertEquals("wah ", deleteWord("wah 👨‍👩‍👧"))
        assertEquals("ok ", deleteWord("ok ❤️"))
        assertEquals("kopi ", deleteWord("kopi café"))
        assertEquals("kopi ", deleteWord("kopi café"))
        assertEquals("tahun ", deleteWord("tahun 2026"))
        assertEquals("baris\n", deleteWord("baris\nbaru"))
        assertEquals("baris", deleteWord("baris\n"))
        assertEquals("baris\n", deleteWord("baris\n  "))
        assertEquals("", deleteWord("makan"))
        assertEquals(0, KudHoldDelete.charCountToDelete(""))
    }

    @Test fun timing() {
        val start = 400; val interval = 50
        val delays = (1..11).map { KudHoldDelete.delayBeforeRepeat(true, KeyCode.DELETE, it, start, interval) }
        // a longer wait than a normal repeat, then words slowly, speeding up to a top speed
        assertEquals(listOf(550, 320, 272, 231, 197, 167, 142, 121, 110, 110, 110), delays)
        assertTrue(delays.zipWithNext().all { (a, b) -> b <= a })
        // every repeat of a held backspace is a word
        assertEquals(KeyCode.DELETE_WORD, KudHoldDelete.codeForRepeat(true, KeyCode.DELETE, 1))
        assertEquals(KeyCode.DELETE_WORD, KudHoldDelete.codeForRepeat(true, KeyCode.DELETE, 30))
        // off, or another repeating key: HeliBoard's timing and codes
        assertEquals(400, KudHoldDelete.delayBeforeRepeat(false, KeyCode.DELETE, 1, start, interval))
        assertEquals(50, KudHoldDelete.delayBeforeRepeat(false, KeyCode.DELETE, 20, start, interval))
        assertEquals(KeyCode.DELETE, KudHoldDelete.codeForRepeat(false, KeyCode.DELETE, 20))
        assertEquals(50, KudHoldDelete.delayBeforeRepeat(true, KeyCode.ARROW_LEFT, 20, start, interval))
        assertEquals(KeyCode.ARROW_LEFT, KudHoldDelete.codeForRepeat(true, KeyCode.ARROW_LEFT, 20))
    }

    // ---- through the IME, the way PointerTracker sends a hold ----

    private fun start(initial: String, selStart: Int = initial.length, selEnd: Int = selStart, inputType: Int = InputType.TYPE_CLASS_TEXT) {
        ShadowInputMethodService.currentInputType = inputType
        ShadowInputMethodService.text = initial
        ShadowInputMethodService.selectionStart = selStart
        ShadowInputMethodService.selectionEnd = selEnd
        val ei = EditorInfo().apply { this.inputType = inputType; initialSelStart = selStart; initialSelEnd = selEnd }
        latinIME.mHandler.onStartInput(ei, false)
        latinIME.mHandler.onStartInputView(ei, false)
        handleMessages()
    }

    private fun handleMessages() {
        while (true) {
            if (messages.isNotEmpty()) latinIME.mHandler.handleMessage(messages.removeAt(0))
            else if (delayedMessages.isNotEmpty()) {
                val msg = delayedMessages.removeAt(0)
                if (msg.what != 2) latinIME.mHandler.handleMessage(msg) // 2 = suggestion strip update
            } else break
        }
    }

    private fun send(code: Int, repeat: Boolean) {
        latinIME.onEvent(Event.createSoftwareKeypressEvent(Event.NOT_A_CODE_POINT, code, 0,
            Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, repeat))
        handleMessages()
    }

    /** Press backspace and hold it for [repeats] key repeats; returns the text after each step (press first). */
    private fun hold(repeats: Int): List<String> {
        val enabled = latinIME.prefs().getBoolean(KudHoldDelete.PREF_DELETE_WORDS_WHEN_HELD, KudHoldDelete.DEFAULT_DELETE_WORDS_WHEN_HELD)
        val steps = mutableListOf<String>()
        send(KeyCode.DELETE, false)
        steps += text
        for (r in 1..repeats) {
            send(KudHoldDelete.codeForRepeat(enabled, KeyCode.DELETE, r), true)
            steps += text
        }
        return steps
    }

    private val sentence = "Aku mau makan nasi goreng, terus minum es teh 😀 "

    @Test fun tapDeletesACharacterThenEveryRepeatAWord() {
        start(sentence)
        val steps = hold(5)
        // the press itself takes one character (the trailing space)
        assertEquals("Aku mau makan nasi goreng, terus minum es teh 😀", steps[0])
        // then a whole word per repeat, never characters
        assertEquals("Aku mau makan nasi goreng, terus minum es teh ", steps[1])
        assertEquals("Aku mau makan nasi goreng, terus minum es ", steps[2])
        assertEquals("Aku mau makan nasi goreng, terus minum ", steps[3])
        assertEquals("Aku mau makan nasi goreng, terus ", steps[4])
        assertEquals("Aku mau makan nasi goreng, ", steps[5])
    }

    @Test fun holdingToTheStartEmptiesTheField() {
        start("Halo, apa kabar?")
        // the press takes "?", then "kabar", "apa", ", " and "Halo"
        val steps = hold(5)
        assertEquals("Halo, apa kabar", steps[0])
        assertEquals("Halo, apa ", steps[1])
        assertEquals("Halo, ", steps[2])
        assertEquals("Halo", steps[3])
        assertEquals("", steps.last())
    }

    @Test fun settingOffDeletesCharactersOnly() {
        latinIME.prefs().edit { putBoolean(KudHoldDelete.PREF_DELETE_WORDS_WHEN_HELD, false) }
        start(sentence)
        val steps = hold(8 + 3)
        assertEquals("Aku mau makan nasi goreng, terus minum ", steps[8])
        assertEquals("Aku mau makan nasi goreng, terus minum", steps[8 + 1])
        assertEquals("Aku mau makan nasi goreng, terus min", steps[8 + 3])
    }

    @Test fun selectionGoesFirst() {
        // "goreng, terus minum" selected
        val selStart = sentence.indexOf("goreng")
        val selEnd = sentence.indexOf(" es")
        start(sentence, selStart, selEnd)
        val steps = hold(3)
        assertEquals("Aku mau makan nasi  es teh 😀 ", steps[0])
        // then a word before the cursor per repeat
        assertEquals("Aku mau makan  es teh 😀 ", steps[1])
        assertEquals("Aku mau  es teh 😀 ", steps[2])
        assertEquals("Aku  es teh 😀 ", steps[3])
    }

    @Test fun unreadableFieldFallsBackToCharacters() {
        // TYPE_NULL fields get key events: a word repeat must send one backspace, not delete a word
        start("hello world", inputType = InputType.TYPE_NULL)
        send(KeyCode.DELETE_WORD, true)
        assertEquals("hello worl", text)
    }
}
