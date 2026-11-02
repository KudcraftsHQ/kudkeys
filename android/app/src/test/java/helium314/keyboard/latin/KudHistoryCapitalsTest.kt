// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionaryCollection
import helium314.keyboard.latin.dictionary.ExpandableBinaryDictionary
import helium314.keyboard.latin.personalization.UserHistoryDictionary
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A sentence-start word is learned lowercase unless the main dictionary only knows it capitalised. An add-on (the
 * SwiftKey import, the synced words) full of capitalised sentence starts must not make it look like a name.
 * Made-up words only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowLocaleManagerCompat::class,
    ShadowInputMethodManager2::class,
    ShadowUserHistoryAdd::class,
])
class KudHistoryCapitalsTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java) // loads the settings
    private val locale = Locale.forLanguageTag("id")
    private val main = FakeDict(Dictionary.TYPE_MAIN, locale, mapOf("zorbo" to 150, "Kelvara" to 120))
    private val addOn = FakeDict("swiftkey", locale, mapOf("Zorbo" to 140, "Vornak" to 140, "Kelvara" to 140))
    private val collection = DictionaryCollection(Dictionary.TYPE_MAIN, locale, listOf(main, addOn), floatArrayOf(1f, 1f))

    private val facilitator = DictionaryFacilitatorImpl().also { f ->
        val groupClass = Class.forName("helium314.keyboard.latin.DictionaryGroup")
        val constructor = groupClass.getDeclaredConstructor(Locale::class.java, Dictionary::class.java, Map::class.java,
            android.content.Context::class.java).apply { isAccessible = true }
        val history = Mockito.mock(ExpandableBinaryDictionary::class.java)
        val group = constructor.newInstance(locale, collection, mapOf(Dictionary.TYPE_USER_HISTORY to history), null)
        DictionaryFacilitatorImpl::class.java.getDeclaredField("dictionaryGroups").apply { isAccessible = true }.set(f, listOf(group))
    }

    @BeforeTest fun reset() = ShadowUserHistoryAdd.added.clear()

    private fun learnAtSentenceStart(word: String): String {
        facilitator.addToUserHistory(word, true, NgramContext.BEGINNING_OF_SENTENCE, 0, false)
        return ShadowUserHistoryAdd.added.last()
    }

    @Test fun theAddOnsDontCountWhenJudgingACapitalOnlyWord() {
        assertTrue(collection.isValidWord("Vornak"), "the add-on knows it")
        assertFalse(collection.isValidWordInDictsOfType("Vornak", Dictionary.TYPE_MAIN), "the main dictionary doesn't")
        assertEquals("vornak", learnAtSentenceStart("Vornak"))
    }

    @Test fun aWordTheMainDictionaryKnowsLowercaseIsLearnedLowercase() {
        assertEquals("zorbo", learnAtSentenceStart("Zorbo"))
    }

    @Test fun aNameInTheMainDictionaryKeepsItsCapital() {
        assertEquals("Kelvara", learnAtSentenceStart("Kelvara"))
    }

    @Suppress("unused") private val keep = latinIME
}

@Implements(UserHistoryDictionary::class)
object ShadowUserHistoryAdd {
    val added = mutableListOf<String>()

    @Implementation
    @JvmStatic
    fun addToDictionary(userHistoryDictionary: ExpandableBinaryDictionary, ngramContext: NgramContext, word: String,
                        isValid: Boolean, timestamp: Int) {
        added += word
    }
}

class FakeDict(type: String, locale: Locale, private val words: Map<String, Int>) : Dictionary(type, locale) {
    override fun getSuggestions(composedData: ComposedData?, ngramContext: NgramContext?, proximityInfoHandle: Long,
        settingsValuesForSuggestion: SettingsValuesForSuggestion?, sessionId: Int, weightForLocale: Float,
        inOutWeightOfLangModelVsSpatialModel: FloatArray?): ArrayList<SuggestedWordInfo> = ArrayList()
    override fun isInDictionary(word: String): Boolean = word in words
    override fun getFrequency(word: String): Int = words[word] ?: NOT_A_PROBABILITY
}
