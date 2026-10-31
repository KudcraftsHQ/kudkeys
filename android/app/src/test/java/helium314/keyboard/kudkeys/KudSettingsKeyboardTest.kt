// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the new switches change on the keyboard itself: emoji key, emoji on enter, voice typing, sound, incognito. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class], qualifiers = "w411dp-h891dp-port-xxhdpi")
class KudSettingsKeyboardTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val switcher = KeyboardSwitcher.getInstance()
    private val inputView: View

    init {
        latinIME.prefs().edit().clear().commit()
        load(EditorInfo())
        inputView = switcher.onCreateInputView(latinIME, true)
        latinIME.setInputView(inputView)
        switcher.reloadMainKeyboard()
    }

    private fun load(ei: EditorInfo) {
        Settings.getInstance().loadSettings(latinIME, Locale.US, InputAttributes(ei, false, latinIME.packageName))
    }

    /** Applies changed prefs like the IME does on the next field: reload settings, rebuild the keyboard. */
    private fun apply(ei: EditorInfo = EditorInfo(), change: (android.content.SharedPreferences.Editor) -> Unit = {}) {
        latinIME.prefs().edit { change(this) }
        load(ei)
        KeyboardLayoutSet.onKeyboardThemeChanged()
        switcher.reloadMainKeyboard()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }

    private fun popupsOf(code: Int) = switcher.keyboard!!.getKey(code)!!.popupKeys?.map { it.mCode } ?: emptyList()

    @Test fun dedicatedEmojiKeyCanBeHidden() {
        apply()
        assertNotNull(switcher.keyboard!!.getKey(KeyCode.EMOJI))
        apply { it.putBoolean(Settings.PREF_SHOW_EMOJI_KEY, false) }
        assertNull(switcher.keyboard!!.getKey(KeyCode.EMOJI))
    }

    @Test fun emojiOnEnterOnlyWhenTheEmojiKeyIsHidden() {
        apply()
        assertFalse(KeyCode.EMOJI in popupsOf(Constants.CODE_ENTER), "emoji key shown: no emoji on enter")
        apply { it.putBoolean(Settings.PREF_SHOW_EMOJI_KEY, false) }
        assertFalse(KeyCode.EMOJI in popupsOf(Constants.CODE_ENTER), "Emoji access on enter key is off by default")
        apply { it.putBoolean(KudSettings.PREF_EMOJI_ON_ENTER, true) }
        assertTrue(KeyCode.EMOJI in popupsOf(Constants.CODE_ENTER))
    }

    @Test fun voiceTypingOffRemovesTheVoiceKeyAndTheMic() {
        apply()
        val strip = inputView.findViewById<SuggestionStripView>(R.id.suggestion_strip_view)
        assertTrue(KeyCode.VOICE_INPUT in popupsOf(','.code))
        assertTrue(strip.isMicShown)
        apply { it.putBoolean(KudSettings.PREF_VOICE_TYPING, false) }
        assertFalse(Settings.getValues().mKudVoiceTyping)
        assertFalse(KeyCode.VOICE_INPUT in popupsOf(','.code))
        strip.updatePrivacy() // the IME calls this after reloading settings for a field
        assertFalse(strip.isMicShown)
    }

    @Test fun classicSoundProfile() {
        apply()
        assertFalse(Settings.getValues().mKudClassicSound)
        apply { it.putString(KudSettings.PREF_SOUND_PROFILE, KudSettings.SOUND_PROFILE_CLASSIC) }
        assertTrue(Settings.getValues().mKudClassicSound)
    }

    @Test fun privateFieldsGetTheIncognitoLook() {
        apply()
        assertFalse(Settings.getValues().mKudIncognitoLook)
        val normalTheme = KeyboardTheme.getKeyboardTheme(latinIME)
        apply(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH })
        assertTrue(Settings.getValues().mKudIncognitoLook)
        assertEquals(KeyboardTheme.KUD_INCOGNITO_BACKGROUND, Settings.getValues().mColors.get(ColorType.MAIN_BACKGROUND))
        assertTrue(Settings.getValues().mColors.hasKeyBorders)
        assertFalse(normalTheme == KeyboardTheme.getKeyboardTheme(latinIME), "private fields draw key shapes")
    }
}
