// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.ShadowBinaryDictionaryUtils
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.kudkeys.KudApostrophes
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.inputlogic.InputLogic
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Apostrophe fixes on space, like SwiftKey ("dont" → "don't", "im" → "I'm"), through the real typing flow: the strip
 * is computed by Suggest from what the fake facilitator offers, then space commits. With autocorrect or Fix
 * apostrophes on, and English active; real words like "well", "were", "id" are never touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowLocaleManagerCompat::class,
    ShadowInputMethodManager2::class,
    ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class,
    ShadowHandler::class,
    ShadowBinaryDictionaryUtils::class,
    ShadowKudFacilitator::class,
])
class KudApostropheInputTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val inputLogic get() = latinIME.mInputLogic
    private val text get() = ShadowInputMethodService.text

    @BeforeTest fun reset() {
        currentScript = ScriptUtils.SCRIPT_LATIN
        ShadowInputMethodService.reset()
        ShadowKudFacilitator.reset()
        latinIME.prefs().edit { clear(); putBoolean(Settings.PREF_AUTO_CORRECTION, true) }
    }

    private fun start(imeOptions: Int = 0) {
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        ShadowInputMethodService.currentInputType = type
        ShadowInputMethodService.currentImeOptions = imeOptions
        ShadowInputMethodService.currentPackageName = "com.whatsapp"
        ShadowInputMethodService.text = ""
        ShadowInputMethodService.selectionStart = 0
        ShadowInputMethodService.selectionEnd = 0
        val ei = EditorInfo().apply { inputType = type; packageName = "com.whatsapp"; this.imeOptions = imeOptions }
        latinIME.mHandler.onStartInput(ei, false)
        latinIME.mHandler.onStartInputView(ei, false)
        handleMessages()
    }

    private fun handleMessages() {
        while (true) {
            if (messages.isNotEmpty()) latinIME.mHandler.handleMessage(messages.removeAt(0))
            else if (delayedMessages.isNotEmpty()) {
                val msg = delayedMessages.removeAt(0)
                if (msg.what != 2) latinIME.mHandler.handleMessage(msg) // 2 = suggestion strip update, see strip()
            } else break
        }
    }

    private val composer: WordComposer
        get() = InputLogic::class.java.getDeclaredField("mWordComposer").apply { isAccessible = true }.get(inputLogic) as WordComposer

    /** What the strip shows for the word being typed, computed by Suggest and handed to the input logic. */
    private fun strip(): SuggestedWords {
        val params = KeyboardParams().apply {
            GRID_HEIGHT = 1
            GRID_WIDTH = 1
            mId = KeyboardLayoutSet.getFakeKeyboardId(KeyboardElement.ALPHABET)
        }
        val ngram = inputLogic.mConnection.getNgramContextFromNthPreviousWord(Settings.getValues().mSpacingAndPunctuations, 2)
        val words = inputLogic.mSuggest.getSuggestedWords(composer.copy(), ngram, Keyboard(params),
            Settings.getValues().mSettingsValuesForSuggestion, Settings.getValues().mAutoCorrectEnabled,
            SuggestedWords.INPUT_STYLE_TYPING, 0)
        inputLogic.setSuggestedWords(words)
        return words
    }

    private fun type(text: String) {
        text.forEach { latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(it.code)); handleMessages() }
    }

    /** Types [word] after "plim " (mid-sentence), lets the strip update, then presses space. */
    private fun typeWordAndSpace(word: String, offered: List<SuggestedWordInfo> = emptyList()): SuggestedWords {
        type("plim $word")
        ShadowKudFacilitator.typing = suggestionResults(offered)
        val shown = strip()
        type(" ")
        return shown
    }

    private fun backspace() {
        latinIME.onEvent(Event.createSoftwareKeypressEvent(Event.NOT_A_CODE_POINT, KeyCode.DELETE, 0, -1, -1, false))
        handleMessages()
    }

    @Test fun theContractionsAreFixedOnSpace() {
        for ((typed, fixed) in listOf("im" to "I'm", "dont" to "don't", "ill" to "I'll", "cant" to "can't",
                "wont" to "won't", "youre" to "you're", "Dont" to "Don't", "IM" to "I'M", "Ill" to "I'll")) {
            reset()
            start()
            val shown = typeWordAndSpace(typed)
            assertEquals("plim $fixed ", text, "typed $typed")
            // the strip showed the fix as the autocorrection, in the centre
            assertTrue(shown.mWillAutoCorrect, "typed $typed")
            assertEquals(fixed, shown.getWord(SuggestedWords.INDEX_OF_AUTO_CORRECTION))
        }
    }

    @Test fun theFixWinsOverATypedWordHistoryLearned() {
        // "dont" typed many times while autocorrect was off: history knows it, the fix still applies
        start()
        ShadowKudFacilitator.valid = setOf("dont")
        typeWordAndSpace("dont", listOf(suggestion("dont", 1_500_000)))
        assertEquals("plim don't ", text)
    }

    @Test fun realWordsAreNeverTouched() {
        for ((typed, offered) in listOf("well" to "we'll", "were" to "we're", "its" to "it's", "hell" to "he'll",
                "shell" to "she'll", "wed" to "we'd", "shed" to "she'd", "hed" to "he'd")) {
            reset()
            start()
            ShadowKudFacilitator.valid = setOf(typed)
            typeWordAndSpace(typed, listOf(suggestion(offered, 1_000_000)))
            assertEquals("plim $typed ", text, "typed $typed")
        }
    }

    @Test fun theDictionarysWhitelistNeverReplacesARealWord() {
        // the English dictionary whitelists "id" → "I'd" and "lets" → "let's"; "id" is Indonesian too
        for ((typed, offered) in listOf("id" to "I'd", "lets" to "let's", "hid" to "his")) {
            reset()
            start()
            ShadowKudFacilitator.valid = setOf(typed)
            typeWordAndSpace(typed, listOf(suggestion(offered, Int.MAX_VALUE)))
            assertEquals("plim $typed ", text, "typed $typed")
        }
        // a whitelisted fix for a word that isn't one still applies, and so does a change of case alone
        reset()
        start()
        typeWordAndSpace("alot", listOf(suggestion("a lot", Int.MAX_VALUE)))
        assertEquals("plim a lot ", text)
    }

    @Test fun backspaceRightAfterRevertsAndTheFixDoesntComeBack() {
        assertFalse(Settings.getValues().mBackspaceRevertsAutocorrect, "Undo autocorrect is off by default")
        start()
        typeWordAndSpace("dont")
        assertEquals("plim don't ", text)
        backspace()
        assertEquals("plim dont", text, "back to what was typed")
        type(" vorn")
        assertEquals("plim dont vorn", text, "not fixed again")
    }

    @Test fun anIndonesianOnlyKeyboardIsUntouched() {
        ShadowKudFacilitator.languages = setOf("id")
        start()
        typeWordAndSpace("dont")
        assertEquals("plim dont ", text)
    }

    @Test fun notInASearchField() {
        start(imeOptions = EditorInfo.IME_ACTION_SEARCH)
        typeWordAndSpace("dont")
        assertEquals("plim dont ", text)
    }

    @Test fun withAutocorrectOffOnlyTheApostropheFixApplies() {
        // Autocorrect is off by default; Fix apostrophes (on by default) still fixes the contractions
        for ((typed, fixed) in listOf("im" to "I'm", "dont" to "don't", "Dont" to "Don't", "ill" to "I'll")) {
            reset()
            latinIME.prefs().edit { putBoolean(Settings.PREF_AUTO_CORRECTION, false) }
            start()
            typeWordAndSpace(typed)
            assertEquals("plim $fixed ", text, "typed $typed")
        }
        // ... but nothing else: a typo stays as typed, and so do real words
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTO_CORRECTION, false) }
        start()
        typeWordAndSpace("alot", listOf(suggestion("a lot", Int.MAX_VALUE)))
        assertEquals("plim alot ", text)
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTO_CORRECTION, false) }
        start()
        ShadowKudFacilitator.valid = setOf("well")
        typeWordAndSpace("well", listOf(suggestion("we'll", 1_000_000)))
        assertEquals("plim well ", text)
        // and backspace still reverts the fix
        reset()
        latinIME.prefs().edit { putBoolean(Settings.PREF_AUTO_CORRECTION, false) }
        start()
        typeWordAndSpace("dont")
        backspace()
        assertEquals("plim dont", text)
    }

    @Test fun notWithAutocorrectAndFixApostrophesOff() {
        latinIME.prefs().edit {
            putBoolean(Settings.PREF_AUTO_CORRECTION, false)
            putBoolean(helium314.keyboard.kudkeys.KudSettings.PREF_FIX_APOSTROPHES, false)
        }
        start()
        typeWordAndSpace("dont")
        assertEquals("plim dont ", text)
    }

    @Test fun theRules() {
        assertEquals("I'm", KudApostrophes.fixFor("im"))
        assertEquals("I'M", KudApostrophes.fixFor("IM"))
        assertEquals("Don't", KudApostrophes.fixFor("Dont"))
        assertEquals("WON'T", KudApostrophes.fixFor("WONT"))
        assertNull(KudApostrophes.fixFor("dONT"), "mixed case is on purpose")
        for (w in listOf("well", "were", "its", "id", "lets", "hell", "shell", "wed", "shed", "hed", "zorbel"))
            assertNull(KudApostrophes.fixFor(w), w)
        assertTrue(KudApostrophes.isFix("dont", "don't"))
        assertTrue(KudApostrophes.isFix("im", "I'm"))
        assertFalse(KudApostrophes.isFix("hullo", "hello"))
        assertFalse(KudApostrophes.isFix("lets", "lets"))
    }
}
