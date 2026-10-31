// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.ShadowHandler
import helium314.keyboard.latin.ShadowKeyboardSwitcher
import helium314.keyboard.latin.currentScript
import helium314.keyboard.latin.delayedMessages
import helium314.keyboard.latin.messages
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
import kotlin.test.assertTrue

/** Private fields learn nothing: typed words never reach the user history. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowLocaleManagerCompat::class,
    ShadowInputMethodManager2::class,
    ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class,
    ShadowHandler::class,
    ShadowFacilitator2::class,
])
class KudPrivacyLearningTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)

    @BeforeTest fun reset() {
        currentScript = ScriptUtils.SCRIPT_LATIN
        ShadowInputMethodService.reset()
        ShadowFacilitator2.lastAddedWord = ""
        latinIME.prefs().edit { clear() }
    }

    private fun startField(type: Int, options: Int = 0, pkg: String? = "com.whatsapp") {
        ShadowInputMethodService.currentInputType = type
        ShadowInputMethodService.currentImeOptions = options
        ShadowInputMethodService.currentPackageName = pkg
        ShadowInputMethodService.text = ""
        ShadowInputMethodService.selectionStart = 0
        ShadowInputMethodService.selectionEnd = 0
        val ei = EditorInfo().apply { inputType = type; imeOptions = options; packageName = pkg }
        latinIME.mHandler.onStartInput(ei, false)
        latinIME.mHandler.onStartInputView(ei, false)
        handleMessages()
    }

    private fun type(text: String) {
        text.forEach { latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(it.code)); handleMessages() }
    }

    private fun handleMessages() {
        while (true) {
            if (messages.isNotEmpty()) {
                latinIME.mHandler.handleMessage(messages.removeAt(0))
            } else if (delayedMessages.isNotEmpty()) {
                val msg = delayedMessages.removeAt(0)
                if (msg.what != 2) latinIME.mHandler.handleMessage(msg) // 2 = suggestion strip update, irrelevant here
            } else break
        }
    }

    private fun learnedAfterTyping(type: Int, options: Int = 0, pkg: String? = "com.whatsapp"): String {
        startField(type, options, pkg)
        ShadowFacilitator2.lastAddedWord = ""
        type("hello there ")
        return ShadowFacilitator2.lastAddedWord
    }

    @Test fun normalFieldLearns() {
        assertEquals("there", learnedAfterTyping(InputType.TYPE_CLASS_TEXT))
        assertFalse(Settings.getValues().mIncognitoModeEnabled)
    }

    @Test fun incognitoFieldLearnsNothing() {
        assertEquals("", learnedAfterTyping(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertTrue(Settings.getValues().mIncognitoModeEnabled)
    }

    @Test fun searchFieldLearnsNothing() {
        assertEquals("", learnedAfterTyping(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH))
        assertEquals("", learnedAfterTyping(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER))
        assertTrue(Settings.getValues().mInputAttributes.mIsPrivate)
    }

    @Test fun launcherLearnsNothing() {
        assertEquals("", learnedAfterTyping(InputType.TYPE_CLASS_TEXT, pkg = "com.teslacoilsw.launcher"))
        assertTrue(Settings.getValues().mIncognitoModeEnabled)
    }

    @Test fun passwordAndUrlLearnNothing() {
        assertEquals("", learnedAfterTyping(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertEquals("", learnedAfterTyping(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))
    }
}
