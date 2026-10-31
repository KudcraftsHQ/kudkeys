// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.latin.App
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.screens.KudSettingsRules
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules behind the SwiftKey-style settings: defaults, quick prediction, emoji on enter, combined rows, resize. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class KudSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<App>()
    private val prefs = context.prefs()

    @BeforeTest fun reset() = prefs.edit { clear() }

    @Test fun swiftKeyDefaults() {
        assertTrue(Defaults.PREF_AUTO_CORRECTION)
        assertFalse(Defaults.PREF_BACKSPACE_REVERTS_AUTOCORRECT)
        assertFalse(KudSettings.DEFAULT_QUICK_PREDICTION_INSERT)
        assertTrue(Defaults.PREF_KEY_USE_DOUBLE_SPACE_PERIOD)
        assertTrue(Defaults.PREF_AUTO_CAP)
        assertTrue(Defaults.PREF_AUTOSPACE_AFTER_PUNCTUATION)
        assertTrue(KudSettingsRules.isCursorControl(prefs))
        assertTrue(Defaults.PREF_DELETE_SWIPE)
        assertFalse(Defaults.PREF_SUGGEST_EMOJIS)
        assertTrue(Defaults.PREF_SHOW_EMOJI_KEY)
        assertFalse(KudSettings.DEFAULT_EMOJI_ON_ENTER)
        assertTrue(KudSettings.DEFAULT_VOICE_TYPING)
        assertFalse(Defaults.PREF_SHOW_NUMBER_ROW)
        assertTrue(Defaults.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS)
        assertFalse(Defaults.PREF_SHOW_HINTS)
        assertFalse(KudSettingsRules.isAccented(prefs))
        assertFalse(Defaults.PREF_SHOW_DPAD_KEY)
        assertTrue(Defaults.PREF_POPUP_ON)
        assertFalse(KudSettingsRules.isLargeKeyText(prefs))
        assertEquals(450, Defaults.PREF_KEY_LONGPRESS_TIMEOUT)
        assertFalse(Defaults.PREF_SOUND_ON)
        assertTrue(KudSettingsRules.isAndroidVibration(prefs))
        assertEquals(65, KudSettings.DEFAULT_VIBRATION_MS)
    }

    private fun words(style: Int, vararg w: String): SuggestedWords {
        val infos = ArrayList(w.mapIndexed { i, word ->
            SuggestedWordInfo(word, "", 100 - i, SuggestedWordInfo.KIND_PREDICTION, Dictionary.DICTIONARY_USER_TYPED, 0, 0)
        })
        return SuggestedWords(infos, null, null, false, false, false, style, 0)
    }

    @Test fun quickPredictionInsertPicksTheMiddlePrediction() {
        val predictions = words(SuggestedWords.INPUT_STYLE_PREDICTION, "aku", "kamu", "dia")
        assertEquals("aku", KudSettings.quickPredictionToInsert(true, false, false, predictions)?.mWord)
        // off, while typing a word, as the second space of a double-space period, or no predictions: just a space
        assertNull(KudSettings.quickPredictionToInsert(false, false, false, predictions))
        assertNull(KudSettings.quickPredictionToInsert(true, true, false, predictions))
        assertNull(KudSettings.quickPredictionToInsert(true, false, true, predictions))
        assertNull(KudSettings.quickPredictionToInsert(true, false, false, words(SuggestedWords.INPUT_STYLE_TYPING, "aku")))
        assertNull(KudSettings.quickPredictionToInsert(true, false, false, SuggestedWords.getEmptyInstance()))
    }

    @Test fun emojiOnEnterOnlyWithoutTheEmojiKey() {
        assertTrue(KudSettings.emojiOnEnter(emojiKeyShown = false, emojiOnEnterEnabled = true))
        assertFalse(KudSettings.emojiOnEnter(emojiKeyShown = true, emojiOnEnterEnabled = true))
        assertFalse(KudSettings.emojiOnEnter(emojiKeyShown = false, emojiOnEnterEnabled = false))
    }

    @Test fun cursorControlSwitchesTheSpaceBarSwipe() {
        KudSettingsRules.setCursorControl(prefs, false)
        assertEquals(KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE, Settings.readHorizontalSpaceSwipe(prefs))
        assertFalse(KudSettingsRules.isCursorControl(prefs))
        KudSettingsRules.setCursorControl(prefs, true)
        assertEquals(KeyboardActionListener.SwipeAction.MOVE_CURSOR, Settings.readHorizontalSpaceSwipe(prefs))
    }

    @Test fun accentedAndLargeKeyText() {
        KudSettingsRules.setAccented(prefs, true)
        assertEquals("all", prefs.getString(Settings.PREF_MORE_POPUP_KEYS, null))
        KudSettingsRules.setAccented(prefs, false)
        assertEquals(Defaults.PREF_MORE_POPUP_KEYS, prefs.getString(Settings.PREF_MORE_POPUP_KEYS, null))
        KudSettingsRules.setLargeKeyText(prefs, true)
        assertEquals(KudSettings.LARGE_KEY_TEXT_SCALE, prefs.getFloat(Settings.PREF_FONT_SCALE, 0f))
        assertTrue(KudSettingsRules.isLargeKeyText(prefs))
        KudSettingsRules.setLargeKeyText(prefs, false)
        assertFalse(prefs.contains(Settings.PREF_FONT_SCALE))
    }

    @Test fun androidDefaultVibrationKeepsTheCustomLength() {
        KudSettingsRules.setVibrationMs(prefs, 40)
        assertFalse(KudSettingsRules.isAndroidVibration(prefs))
        assertEquals(40, prefs.getInt(Settings.PREF_VIBRATION_DURATION_SETTINGS, 0))
        prefs.edit { putBoolean(Settings.PREF_VIBRATE_ON, false) }
        KudSettingsRules.setAndroidVibration(prefs, true)
        assertTrue(KudSettingsRules.isAndroidVibration(prefs))
        assertTrue(prefs.getBoolean(Settings.PREF_VIBRATE_ON, false)) // the Android default vibrates
        KudSettingsRules.setAndroidVibration(prefs, false)
        assertEquals(40, prefs.getInt(Settings.PREF_VIBRATION_DURATION_SETTINGS, 0))
    }

    @Test fun soundVolumeSteps() {
        assertEquals(2, KudSettingsRules.volumeToSteps(-0.01f))
        assertEquals(0, KudSettingsRules.volumeToSteps(0f))
        assertEquals(7, KudSettingsRules.volumeToSteps(0.7f))
        assertEquals(10, KudSettingsRules.volumeToSteps(1f))
    }

    @Test fun clearTypingDataDeletesUserHistory() {
        val file = File(context.filesDir, "UserHistoryDictionary.en_US.dict").apply { writeText("learned") }
        val other = File(context.filesDir, "kudkeys_keep.txt").apply { writeText("keep") }
        KudTypingData.clear(context)
        assertFalse(file.exists())
        assertTrue(other.exists())
    }

    private val m = KudResize.Metrics(width = 1000f, defaultHeight = 600f, sidePaddingFraction = 0.08f, bottomPaddingFraction = 0.05f)

    @Test fun resizeTopHandleChangesTheHeight() {
        val r = KudResize.DEFAULT.dragTop(-60f, m) // 60 px up
        assertEquals(660f, r.keyboardHeight(m), 0.5f)
        assertEquals(KudResize.DEFAULT.bottomPadding(m), r.bottomPadding(m), 0.5f) // bottom stays in place
        // clamped to HeliBoard's range
        assertEquals(KudResize.HEIGHT.endInclusive, KudResize.DEFAULT.dragTop(-5000f, m).heightScale)
        assertEquals(KudResize.HEIGHT.start, KudResize.DEFAULT.dragTop(5000f, m).heightScale)
    }

    @Test fun resizeBottomHandleKeepsTheTopEdge() {
        val r = KudResize.DEFAULT.dragBottom(-30f, m) // 30 px up: shorter keys, more space below
        assertEquals(KudResize.DEFAULT.keyboardHeight(m), r.keyboardHeight(m), 0.5f)
        assertEquals(KudResize.DEFAULT.bottomPadding(m) + 30f, r.bottomPadding(m), 0.5f)
        assertEquals(0f, KudResize.DEFAULT.dragBottom(500f, m).bottomScale)
    }

    @Test fun resizeSideHandlesNarrowBothSides() {
        val r = KudResize.DEFAULT.dragSide(40f, m)
        assertEquals(40f, r.sidePadding(m), 0.5f)
        assertEquals(0f, r.dragSide(-100f, m).sideScale) // can't go wider than the screen
    }

    @Test fun resizeMoveKeepsTheKeysSize() {
        val start = KudResize.DEFAULT
        val r = start.move(-50f, m) // 50 px up
        assertEquals(start.keysHeight(m), r.keysHeight(m), 0.5f)
        assertEquals(start.bottomPadding(m) + 50f, r.bottomPadding(m), 0.5f)
        val down = r.move(5000f, m) // all the way down: no bottom padding left
        assertEquals(0f, down.bottomPadding(m), 0.5f)
        assertEquals(start.keysHeight(m), down.keysHeight(m), 0.5f)
    }
}
