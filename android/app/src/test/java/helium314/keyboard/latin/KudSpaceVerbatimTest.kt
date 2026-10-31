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
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * SwiftKey: space (or punctuation) commits the word exactly as typed; only tapping a suggestion changes it.
 * The strip offers a correction the old autocorrect would have applied ("hello" for "hullo").
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowLocaleManagerCompat::class,
    ShadowInputMethodManager2::class,
    ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class,
    ShadowHandler::class,
    ShadowFacilitator2::class,
])
class KudSpaceVerbatimTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)

    @BeforeTest fun reset() {
        currentScript = ScriptUtils.SCRIPT_LATIN
        ShadowInputMethodService.reset()
        latinIME.prefs().edit { clear() }
    }

    private fun start() {
        ShadowInputMethodService.currentInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        ShadowInputMethodService.currentImeOptions = 0
        ShadowInputMethodService.currentPackageName = "com.whatsapp"
        ShadowInputMethodService.text = ""
        ShadowInputMethodService.selectionStart = 0
        ShadowInputMethodService.selectionEnd = 0
        val ei = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            packageName = "com.whatsapp"
        }
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

    /** The strip while "hullo" is typed, with "hello" marked as the auto-correction. */
    private fun offerCorrection(typed: String, correction: String) {
        val typedInfo = SuggestedWordInfo(typed, "", 0, SuggestedWordInfo.KIND_TYPED, null, 0, 0)
        val info = SuggestedWordInfo(correction, "", 0, SuggestedWordInfo.KIND_CORRECTION, null, 0, 0)
        latinIME.mInputLogic.setSuggestedWords(
            SuggestedWords(ArrayList(listOf(typedInfo, info)), null, typedInfo, false, true, false, 0, 0))
    }

    private val text get() = ShadowInputMethodService.text

    @Test fun autocorrectAndQuickInsertAreOffByDefault() {
        assertFalse(Defaults.PREF_AUTO_CORRECTION)
        assertFalse(KudSettings.DEFAULT_QUICK_PREDICTION_INSERT)
        assertFalse(Settings.getValues().mAutoCorrectEnabled)
    }

    @Test fun spaceCommitsTheWordAsTyped() {
        start()
        type("hullo")
        offerCorrection("hullo", "hello")
        type(" ")
        assertEquals("hullo ", text)
    }

    @Test fun punctuationCommitsTheWordAsTyped() {
        start()
        type("hullo")
        offerCorrection("hullo", "hello")
        type(".")
        assertEquals("hullo.", text)
        type(" sjdh")
        offerCorrection("sjdh", "such")
        type(",")
        assertEquals("hullo. sjdh,", text)
    }

    @Test fun tappingTheSuggestionStillReplaces() {
        start()
        type("hullo")
        offerCorrection("hullo", "hello")
        latinIME.pickSuggestionManually(latinIME.mInputLogic.mSuggestedWords.getInfo(1))
        handleMessages()
        assertEquals("hello", text.trimEnd())
    }

    /** Turning Autocorrect back on in Typing brings the old behaviour back. */
    @Test fun autocorrectOnStillCorrects() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTO_CORRECTION, true) }
        start()
        type("hullo")
        offerCorrection("hullo", "hello")
        type(" ")
        assertEquals("hello ", text)
    }
}
