// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.text.SpannableString
import androidx.core.content.edit
import helium314.keyboard.ShadowBinaryDictionaryUtils
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.kudkeys.KudStarterWords
import helium314.keyboard.latin.NgramContext.WordInfo
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.common.StringUtils
import helium314.keyboard.latin.makedict.ProbabilityInfo
import helium314.keyboard.latin.makedict.WordProperty
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.NgramContextUtils
import helium314.keyboard.latin.utils.SuggestionResults
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kud Keys' prediction and capitalisation fixes, on the suggestion pipeline (Suggest) with a fake dictionary
 * facilitator. All words are made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowLocaleManagerCompat::class,
    ShadowInputMethodManager2::class,
    ShadowBinaryDictionaryUtils::class,
    ShadowKudFacilitator::class,
])
class KudPredictionFixesTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val suggest get() = latinIME.mInputLogic.mSuggest

    private val midSentence = NgramContext(WordInfo("plim"))
    private val sentenceStart = NgramContext.BEGINNING_OF_SENTENCE

    @BeforeTest fun reset() {
        latinIME.prefs().edit { clear() }
        ShadowKudFacilitator.reset()
        suggest.clearNextWordSuggestionsCache()
    }

    private fun words(result: SuggestedWords) = result.mSuggestedWordInfoList.map { it.mWord }

    // ---- 1. the prediction cache ----

    @Test fun aRealContextHasARealHash() {
        val a = NgramContext(WordInfo("plim"), WordInfo("vorn"))
        val b = NgramContext(WordInfo("plim"), WordInfo("vorn"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(0, a.hashCode(), "a real context used to always hash to 0")
        assertNotEquals(a.hashCode(), NgramContext(WordInfo("tesk"), WordInfo("vorn")).hashCode())
        assertNotEquals(a.hashCode(), NgramContext(WordInfo("vorn"), WordInfo("plim")).hashCode())
        // trailing empty words don't change equality, so they mustn't change the hash either
        val padded = NgramContext(WordInfo("plim"), WordInfo("vorn"), WordInfo.EMPTY_WORD_INFO)
        assertEquals(a, padded)
        assertEquals(a.hashCode(), padded.hashCode())
        // equal text in a different CharSequence class
        assertEquals(NgramContext(WordInfo("plim")).hashCode(), NgramContext(WordInfo(SpannableString("plim"))).hashCode())
    }

    @Test fun predictionsAreCachedUntilTheCacheIsCleared() {
        ShadowKudFacilitator.next = suggestionResults(listOf(suggestion("vorn", 200)))
        assertEquals(listOf("vorn"), words(get("", midSentence)).take(1))
        ShadowKudFacilitator.next = suggestionResults(listOf(suggestion("tesk", 220)))
        assertEquals(listOf("vorn"), words(get("", midSentence)).take(1), "cached")
        suggest.clearNextWordSuggestionsCache() // InputLogic does this after every learned word, see KudPredictionInputTest
        assertEquals(listOf("tesk"), words(get("", midSentence)).take(1))
    }

    // ---- 2. never an empty strip ----

    @Test fun shortPredictionsAreFilledToThree() {
        ShadowKudFacilitator.fallback = listOf("tesk", "mipo", "lark", "dorn")
        assertEquals(listOf("tesk", "mipo", "lark"), words(get("", midSentence)))
        ShadowKudFacilitator.next = suggestionResults(listOf(suggestion("vorn", 200)))
        suggest.clearNextWordSuggestionsCache()
        assertEquals(listOf("vorn", "tesk", "mipo"), words(get("", midSentence)))
    }

    @Test fun fillersNeverRepeatAWordOnTheStrip() {
        ShadowKudFacilitator.fallback = listOf("vorn", "Tesk", "tesk", "mipo", "lark")
        ShadowKudFacilitator.next = suggestionResults(listOf(suggestion("Vorn", 200)))
        assertEquals(listOf("Vorn", "Tesk", "mipo"), words(get("", midSentence)))
    }

    @Test fun threePredictionsNeedNoFillers() {
        ShadowKudFacilitator.fallback = listOf("tesk")
        ShadowKudFacilitator.next = suggestionResults(listOf(suggestion("vorn", 200), suggestion("dorn", 190), suggestion("lark", 180)))
        assertEquals(listOf("vorn", "dorn", "lark"), words(get("", midSentence)))
    }

    @Test fun fillersAreCapitalisedAtASentenceStart() {
        ShadowKudFacilitator.fallback = listOf("tesk", "mipo", "lark")
        ShadowKudFacilitator.next = suggestionResults(emptyList(), isBeginningOfSentence = true)
        val result = get("", sentenceStart, CapsMode.AUTO) // the keyboard is auto-shifted at a sentence start
        assertEquals(listOf("Tesk", "Mipo", "Lark"), words(result))
        assertEquals(SuggestedWords.INPUT_STYLE_BEGINNING_OF_SENTENCE_PREDICTION, result.mInputStyle)
    }

    @Test fun fillersAreMarkedSoQuickInsertSkipsThem() {
        ShadowKudFacilitator.fallback = listOf("tesk")
        val result = get("", midSentence)
        assertTrue(result.getInfo(0).mSourceDict === Suggest.DICTIONARY_FALLBACK)
        assertNull(helium314.keyboard.kudkeys.KudSettings.quickPredictionToInsert(true, false, false, result))
    }

    @Test fun starterWordsPerLanguage() {
        assertEquals("aku", KudStarterWords.forLocale(Locale.forLanguageTag("id")).first())
        assertEquals(KudStarterWords.forLocale(Locale.forLanguageTag("id")), KudStarterWords.forLocale(Locale("in")))
        assertEquals("I", KudStarterWords.forLocale(Locale.US).first())
        assertEquals(emptyList(), KudStarterWords.forLocale(Locale.GERMAN))
    }

    @Test fun mostUsedHistoryWordsSkipOneOffsAndNonWords() {
        fun word(w: String, count: Int, time: Int = 0, notAWord: Boolean = false) =
            WordProperty(w, ProbabilityInfo(100, time, 0, count), null, null, notAWord, false)
        val words = DictionaryFacilitatorImpl.mostUsedWords(arrayOf(
            word("tesk", 5), word("vorn", 9), word("plimk", 1) /* a one-off, maybe a typo */, word("mipo", 5, time = 50),
            word("dorn", 30, notAWord = true), word("two words", 40),
        ), 3)
        assertEquals(listOf("vorn", "mipo", "tesk"), words)
    }

    // ---- 3. capitals mid-sentence ----

    @Test fun aCapitalisedTwinIsLowercasedMidSentence() {
        ShadowKudFacilitator.valid = setOf("zorbo", "zorbik")
        ShadowKudFacilitator.typing = suggestionResults(listOf(
            suggestion("zorbo", 900_000), suggestion("Zorbo", 880_000), suggestion("zorbik", 700_000)))
        assertEquals(listOf("zor", "zorbo", "zorbik"), words(get("zor", midSentence)))
    }

    @Test fun theCapitalisedTwinNeverTakesTheCentreAfterTheFullWord() {
        ShadowKudFacilitator.valid = setOf("zorbo", "zorbik")
        ShadowKudFacilitator.typing = suggestionResults(listOf(
            suggestion("zorbo", 1_500_000), suggestion("Zorbo", 1_400_000), suggestion("zorbik", 700_000)))
        assertEquals(listOf("zorbo", "zorbik"), words(get("zorbo", midSentence)))
    }

    @Test fun aWordThatOnlyExistsCapitalisedKeepsItsCapital() {
        ShadowKudFacilitator.valid = setOf("Kelvara", "kelvin")
        ShadowKudFacilitator.typing = suggestionResults(listOf(suggestion("Kelvara", 900_000), suggestion("kelvin", 800_000)))
        assertEquals(listOf("kelv", "Kelvara", "kelvin"), words(get("kelv", midSentence)))
    }

    @Test fun aWordTheMainDictionaryPrefersCapitalisedKeepsItsCapital() {
        // "God" vs "god": both are words, but the main dictionary uses the capitalised form more.
        ShadowKudFacilitator.valid = setOf("Zarim", "zarim", "zarik")
        ShadowKudFacilitator.prefersCapitalized = setOf("Zarim")
        ShadowKudFacilitator.typing = suggestionResults(listOf(suggestion("Zarim", 900_000), suggestion("zarik", 800_000)))
        assertEquals(listOf("zar", "Zarim", "zarik"), words(get("zar", midSentence)))
    }

    @Test fun capitalsStayAtASentenceStartAndWhenShifted() {
        ShadowKudFacilitator.valid = setOf("zorbo")
        ShadowKudFacilitator.typing = suggestionResults(listOf(suggestion("Zorbo", 900_000)))
        assertEquals(listOf("zor", "Zorbo"), words(get("zor", sentenceStart)), "a field that doesn't auto-capitalise")
        val shifted = words(get("zor", midSentence, CapsMode.MANUAL))
        assertTrue("Zorbo" in shifted && "zorbo" !in shifted, "$shifted")
    }

    @Test fun predictionsAreLowercasedMidSentenceToo() {
        ShadowKudFacilitator.valid = setOf("zorbo")
        ShadowKudFacilitator.next = suggestionResults(listOf(suggestion("Zorbo", 230), suggestion("vorn", 200), suggestion("dorn", 190)))
        assertEquals(listOf("zorbo", "vorn", "dorn"), words(get("", midSentence)))
    }

    @Test fun singleLettersApostrophesAndAcronymsAreLeftAlone() {
        ShadowKudFacilitator.valid = setOf("i", "i'm", "ma", "zorbo")
        ShadowKudFacilitator.typing = suggestionResults(listOf(
            suggestion("I", 900_000), suggestion("I'm", 890_000), suggestion("MA", 880_000), suggestion("ZoRbo", 870_000)))
        assertEquals(listOf("i", "I", "I'm", "MA", "ZoRbo"), words(get("i", midSentence)))
    }

    @Test fun caseDuplicatesKeepTheHigherScore() {
        // at a sentence start nothing is lowercased, so only the duplicate removal applies
        ShadowKudFacilitator.next = suggestionResults(listOf(suggestion("Plimo", 220), suggestion("plimo", 150), suggestion("vorn", 140)))
        assertEquals(listOf("Plimo", "vorn"), words(get("", sentenceStart)).take(2))
    }

    @Test fun onATieLowercaseComesFirst() {
        val results = suggestionResults(listOf(suggestion("Zorbo", 100), suggestion("zorbo", 100), suggestion("Vorn", 100), suggestion("aaaaa", 100)))
        assertEquals(listOf("Vorn", "aaaaa", "zorbo", "Zorbo"), results.map { it.mWord }) // shorter first, as before
    }

    @Test fun lowercasedFormRule() {
        assertEquals("zorbo", Suggest.lowercasedFormOrNull("Zorbo", Locale.ROOT))
        assertNull(Suggest.lowercasedFormOrNull("zorbo", Locale.ROOT))
        assertNull(Suggest.lowercasedFormOrNull("I", Locale.ROOT))
        assertNull(Suggest.lowercasedFormOrNull("I'll", Locale.ROOT))
        assertNull(Suggest.lowercasedFormOrNull("MA", Locale.ROOT))
        assertNull(Suggest.lowercasedFormOrNull("McVorn", Locale.ROOT))
    }

    // ---- 4. context across a comma, and from a typo ----

    @Test fun aCommaKeepsTheContext() {
        val sp = Settings.getValues().mSpacingAndPunctuations
        fun ctx(text: String) = NgramContextUtils.getNgramContextFromNthPreviousWord(text, sp, 1)
        assertEquals(NgramContext(WordInfo("vorn"), WordInfo("plim"), WordInfo.BEGINNING_OF_SENTENCE_WORD_INFO), ctx("plim vorn, "))
        assertEquals("vorn", ctx("plim vorn; ").getNthPrevWord(1).toString())
        assertEquals("vorn", ctx("plim vorn,").getNthPrevWord(1).toString())
        assertEquals("plim", ctx("plim, vorn ").getNthPrevWord(2).toString(), "a comma further back keeps it too")
        assertTrue(ctx("plim vorn. ").isBeginningOfSentenceContext, "a full stop still starts a new sentence")
        assertTrue(ctx("plim vorn? ").isBeginningOfSentenceContext)
        assertEquals(NgramContext.EMPTY_PREV_WORDS_INFO, ctx("plim , "), "a lone comma is still no context")
    }

    @Test fun aConfidentCorrectionOfAnUnknownWordIsRemembered() {
        ShadowKudFacilitator.valid = setOf("zorbo")
        ShadowKudFacilitator.typing = suggestionResults(listOf(suggestion("zorbo", 1_500_000), suggestion("vorn", 300_000)))
        val result = get("zorbp", midSentence)
        assertEquals("zorbo", result.mKudContextCorrection)
        assertTrue(!result.mWillAutoCorrect, "autocorrect is still off, space keeps the text")
    }

    @Test fun noCorrectionContextWhenUnsureOrTheWordIsKnown() {
        ShadowKudFacilitator.valid = setOf("zorbo")
        ShadowKudFacilitator.typing = suggestionResults(listOf(suggestion("zorbo", 100_000)))
        assertNull(get("zorbp", midSentence).mKudContextCorrection, "too weak for autocorrect")
        ShadowKudFacilitator.valid = setOf("zorbo", "zorbp")
        ShadowKudFacilitator.typing = suggestionResults(listOf(suggestion("zorbo", 1_500_000)))
        assertNull(get("zorbp", midSentence).mKudContextCorrection, "the typed word is a word")
    }

    private fun get(typedWord: String, ngramContext: NgramContext, capsMode: CapsMode = CapsMode.OFF): SuggestedWords {
        val wc = WordComposer()
        StringUtils.toCodePointArray(typedWord).forEach {
            val e = Event.createEventForCodePointFromAlreadyTypedText(it, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE)
            wc.applyProcessedEvent(wc.processEvent(e))
        }
        wc.adviseCapitalizedModeBeforeFetchingSuggestions(capsMode)
        val params = KeyboardParams()
        params.GRID_HEIGHT = 1
        params.GRID_WIDTH = 1
        params.mId = KeyboardLayoutSet.getFakeKeyboardId(when (capsMode) {
            CapsMode.MANUAL_LOCKED -> KeyboardElement.ALPHABET_SHIFT_LOCKED
            CapsMode.MANUAL -> KeyboardElement.ALPHABET_MANUAL_SHIFTED
            CapsMode.AUTO -> KeyboardElement.ALPHABET_AUTOMATIC_SHIFTED
            else -> KeyboardElement.ALPHABET
        })
        return suggest.getSuggestedWords(wc, ngramContext, Keyboard(params), Settings.getValues().mSettingsValuesForSuggestion,
            Settings.getValues().mAutoCorrectEnabled, 0, 0)
    }
}

/** A dictionary facilitator whose suggestions, valid words, fillers and learning are set by the test. */
@Implements(DictionaryFacilitatorImpl::class)
class ShadowKudFacilitator {
    companion object {
        var typing = suggestionResults(emptyList())
        var next = suggestionResults(emptyList())
        var valid = emptySet<String>()
        var fallback = emptyList<String>()
        var prefersCapitalized = emptySet<String>()
        val learned = mutableListOf<Pair<String, NgramContext>>()

        fun reset() {
            typing = suggestionResults(emptyList())
            next = suggestionResults(emptyList())
            valid = emptySet()
            fallback = emptyList()
            prefersCapitalized = emptySet()
            learned.clear()
        }
    }

    @Implementation fun getCurrentLocale(): Locale = Locale.ENGLISH
    @Implementation fun getMainLocale(): Locale = Locale.ENGLISH
    @Implementation fun hasAtLeastOneInitializedMainDictionary() = true
    @Implementation fun isValidSpellingWord(word: String) = word in valid
    @Implementation fun getFallbackPredictions(): List<String> = fallback
    @Implementation fun mainDictPrefersCapitalized(capitalized: String, lowercase: String) = capitalized in prefersCapitalized
    @Implementation
    fun getSuggestionResults(composedData: ComposedData, ngramContext: NgramContext, keyboard: Keyboard,
                             settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, inputStyle: Int
    ): SuggestionResults = if (composedData.mTypedWord.isEmpty()) next else typing
    @Implementation
    fun addToUserHistory(suggestion: String, wasAutoCapitalized: Boolean, ngramContext: NgramContext,
                         timeStampInSeconds: Long, blockPotentiallyOffensive: Boolean) {
        learned += suggestion to ngramContext
    }
}
