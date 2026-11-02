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
import helium314.keyboard.latin.NgramContext.WordInfo
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.internal.KeyboardParams
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

/**
 * The prediction fixes that live in the typing flow: learning clears the prediction cache, and a word kept as typed
 * that autocorrect would have fixed predicts from the fix without changing the text or being learned in a pair.
 * Made-up words only.
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
class KudPredictionInputTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val inputLogic get() = latinIME.mInputLogic
    private val text get() = ShadowInputMethodService.text

    @BeforeTest fun reset() {
        currentScript = ScriptUtils.SCRIPT_LATIN
        ShadowInputMethodService.reset()
        ShadowKudFacilitator.reset()
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

    /** The predictions after the current text, as Suggest computes them (through its cache). */
    private fun predictions(): List<String> {
        val ngramContext = inputLogic.withTypoCorrected(inputLogic.mConnection.getNgramContextFromNthPreviousWord(
            Settings.getValues().mSpacingAndPunctuations, 1))
        val params = KeyboardParams().apply {
            GRID_HEIGHT = 1
            GRID_WIDTH = 1
            mId = KeyboardLayoutSet.getFakeKeyboardId(KeyboardElement.ALPHABET)
        }
        val composer = WordComposer().apply { adviseCapitalizedModeBeforeFetchingSuggestions(CapsMode.OFF) }
        return inputLogic.mSuggest.getSuggestedWords(composer, ngramContext, Keyboard(params),
            Settings.getValues().mSettingsValuesForSuggestion, false, SuggestedWords.INPUT_STYLE_TYPING, 0)
            .mSuggestedWordInfoList.map { it.mWord }
    }

    private fun ngramAfterText() = inputLogic.withTypoCorrected(inputLogic.mConnection.getNgramContextFromNthPreviousWord(
        Settings.getValues().mSpacingAndPunctuations, 1))

    @Test fun aWordPairLearnedInThisFieldShowsUpNextTime() {
        start()
        type("makan ")
        ShadowKudFacilitator.next = suggestionResults(listOf(suggestion("malam", 160)))
        assertEquals("malam", predictions().first())
        // "makan vorn" gets learned; the facilitator now predicts it
        type("vorn. makan")
        ShadowKudFacilitator.next = suggestionResults(listOf(suggestion("vorn", 242), suggestion("malam", 160)))
        type(" ")
        assertEquals(listOf("vorn", "malam"), predictions().take(2), "the cached 'malam' prediction is gone")
        assertEquals("makan", ShadowKudFacilitator.learned.last().first)
    }

    /** The strip while [typed] is being typed, with the correction autocorrect would have made. */
    private fun offerContextCorrection(typed: String, correction: String) {
        val typedInfo = SuggestedWordInfo(typed, "", SuggestedWordInfo.MAX_SCORE, SuggestedWordInfo.KIND_TYPED, null, 0, 0)
        val info = SuggestedWordInfo(correction, "", 0, SuggestedWordInfo.KIND_CORRECTION, null, 0, 0)
        val words = SuggestedWords(ArrayList(listOf(typedInfo, info)), null, typedInfo, false, false, false, 0, 0)
        words.mKudContextCorrection = correction
        inputLogic.setSuggestedWords(words)
    }

    @Test fun aTypoKeptAsTypedPredictsFromItsCorrection() {
        start()
        type("plim zorbp")
        offerContextCorrection("zorbp", "zorbo")
        type(" ")
        assertEquals("plim zorbp ", text, "space keeps the word exactly as typed")
        assertEquals(NgramContext(WordInfo("zorbo"), WordInfo("plim"), WordInfo.BEGINNING_OF_SENTENCE_WORD_INFO), ngramAfterText())
    }

    @Test fun aTypoIsNeverLearnedInAWordPair() {
        start()
        type("plim zorbp")
        offerContextCorrection("zorbp", "zorbo")
        type(" vorn tesk ")
        val learned = ShadowKudFacilitator.learned.associate { it.first to it.second }
        assertEquals(NgramContext.EMPTY_PREV_WORDS_INFO, learned["zorbp"], "not 'plim zorbp'")
        assertEquals(NgramContext.EMPTY_PREV_WORDS_INFO, learned["vorn"], "not 'zorbp vorn'")
        assertEquals("vorn", learned["tesk"]!!.getNthPrevWord(1).toString(), "normal learning again after that")
        // and once the typo has scrolled out of the context, predictions use the text again
        assertEquals("tesk", ngramAfterText().getNthPrevWord(1).toString())
    }

    @Test fun aKnownWordKeepsItsContext() {
        start()
        type("plim vorn ")
        val learned = ShadowKudFacilitator.learned.associate { it.first to it.second }
        assertEquals("plim", learned["vorn"]!!.getNthPrevWord(1).toString())
        assertEquals("vorn", ngramAfterText().getNthPrevWord(1).toString())
    }
}
