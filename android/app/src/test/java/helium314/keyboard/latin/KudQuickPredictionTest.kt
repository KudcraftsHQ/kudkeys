// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.kudkeys.KudSettings
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Typing → Quick prediction insert: space inserts the middle prediction before the next word is started. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowLocaleManagerCompat::class,
    ShadowInputMethodManager2::class,
    ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class,
    ShadowHandler::class,
    ShadowFacilitator2::class,
])
class KudQuickPredictionTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)

    @BeforeTest fun reset() {
        currentScript = ScriptUtils.SCRIPT_LATIN
        ShadowInputMethodService.reset()
        latinIME.prefs().edit { clear() }
    }

    private fun start() {
        ShadowInputMethodService.currentInputType = InputType.TYPE_CLASS_TEXT
        ShadowInputMethodService.currentImeOptions = 0
        ShadowInputMethodService.currentPackageName = "com.whatsapp"
        ShadowInputMethodService.text = ""
        ShadowInputMethodService.selectionStart = 0
        ShadowInputMethodService.selectionEnd = 0
        val ei = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "com.whatsapp" }
        latinIME.mHandler.onStartInput(ei, false)
        latinIME.mHandler.onStartInputView(ei, false)
        handleMessages()
    }

    private fun type(text: String) {
        text.forEach { latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(it.code)); handleMessages() }
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

    /** What the strip shows after "hello ": next-word predictions, best one in the middle. */
    private fun showPredictions(vararg words: String) {
        val infos = ArrayList(words.mapIndexed { i, w ->
            SuggestedWordInfo(w, "", 100 - i, SuggestedWordInfo.KIND_PREDICTION, Dictionary.DICTIONARY_USER_TYPED, 0, 0)
        })
        latinIME.mInputLogic.mSuggestedWords =
            SuggestedWords(infos, null, null, false, false, false, SuggestedWords.INPUT_STYLE_PREDICTION, 0)
    }

    private fun afterAPause() = ShadowSystemClock.advanceBy(Duration.ofSeconds(2))

    private val text get() = ShadowInputMethodService.text

    @Test fun spaceInsertsTheMiddlePrediction() {
        latinIME.prefs().edit { putBoolean(KudSettings.PREF_QUICK_PREDICTION_INSERT, true) }
        start()
        type("hello ")
        afterAPause()
        showPredictions("world", "there")
        type(" ")
        assertEquals("hello world ", text)
    }

    @Test fun offByDefault() {
        start()
        type("hello ")
        afterAPause()
        showPredictions("world", "there")
        type(" ")
        assertEquals("hello  ", text)
    }

    @Test fun aQuickSecondSpaceIsStillAPeriod() {
        latinIME.prefs().edit { putBoolean(KudSettings.PREF_QUICK_PREDICTION_INSERT, true) }
        start()
        type("hello ")
        showPredictions("world", "there")
        type(" ")
        assertEquals("hello. ", text)
    }

    @Test fun noPredictionWhileTypingAWord() {
        latinIME.prefs().edit { putBoolean(KudSettings.PREF_QUICK_PREDICTION_INSERT, true) }
        start()
        type("hello ")
        afterAPause()
        showPredictions("world")
        type("wo")
        afterAPause()
        type(" ")
        assertEquals("hello wo ", text)
    }
}
