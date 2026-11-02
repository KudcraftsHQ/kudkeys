// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.Suggest
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.WordComposer
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.locale
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The apostrophe fixes against the real English and Indonesian dictionaries and the native suggestion code (built
 * for the host by the `hostJni` Gradle task), English with Indonesian as the second language and autocorrect on.
 * The English dictionary's own whitelist ("id" → "I'd", "lets" → "let's", "hid" → "his") must not fire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class, ShadowOwnSandboxForNativeCode::class])
class KudApostropheDictionaryTest {
    @Before fun loadNativeCode() {
        val lib = File(System.getProperty("kudkeys.hostJni") ?: "")
        assumeTrue("host build of the native dictionary code missing (needs g++): $lib", lib.isFile)
        if (!loaded) { System.load(lib.absolutePath); loaded = true }
    }

    /** What space would commit for each typed word: the autocorrection, or the word as typed. */
    private fun commits(words: Collection<String>, englishConfidence: Int? = null, locale: Locale? = null): Map<String, String> {
        val ime = Robolectric.setupService(LatinIME::class.java)
        ime.prefs().edit().clear().putBoolean(Settings.PREF_AUTO_CORRECTION, true).commit()
        SubtypeSettings.reloadEnabledSubtypes(ime) // en-US with id as the secondary language
        val editor = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT; packageName = "com.whatsapp" }
        Settings.getInstance().loadSettings(ime, Locale.US, InputAttributes(editor, false, "com.whatsapp"))
        KeyboardSwitcher.getInstance().apply { onCreateInputView(ime, true); reloadMainKeyboard() }
        val keyboard = KeyboardSwitcher.getInstance().keyboard!!

        val facilitator = DictionaryFacilitatorImpl()
        facilitator.resetDictionaries(ime, locale ?: SubtypeSettings.getSelectedSubtype(ime.prefs()).locale(), false, false, false, true, "", null)
        facilitator.waitForLoadingMainDictionaries(20, TimeUnit.SECONDS)
        if (englishConfidence != null) { // as after a run of Indonesian words
            @Suppress("UNCHECKED_CAST")
            val groups = DictionaryFacilitatorImpl::class.java.getDeclaredField("dictionaryGroups").apply { isAccessible = true }.get(facilitator) as List<Any>
            for (g in groups) {
                val loc = g.javaClass.getMethod("getLocale").apply { isAccessible = true }.invoke(g) as Locale
                g.javaClass.getDeclaredField("confidence").apply { isAccessible = true }.setInt(g, if (loc.language == "en") englishConfidence else 2)
            }
        }
        val suggest = Suggest(facilitator).apply { setAutoCorrectionThreshold(Settings.getValues().mAutoCorrectionThreshold) }
        val out = words.associateWith { typed ->
            val composer = WordComposer()
            for (cp in typed.codePoints().toArray()) {
                val key = keyboard.getKey(Character.toLowerCase(cp))!! // capitals: the same key, shifted
                composer.applyProcessedEvent(composer.processEvent(Event.createSoftwareKeypressEvent(
                    cp, 0, 0, key.x + key.width / 2, key.y + key.height / 2, false)))
            }
            val result = suggest.getSuggestedWords(composer, NgramContext.EMPTY_PREV_WORDS_INFO, keyboard,
                Settings.getValues().mSettingsValuesForSuggestion, true, SuggestedWords.INPUT_STYLE_TYPING, 0)
            if (result.mWillAutoCorrect) result.getWord(SuggestedWords.INDEX_OF_AUTO_CORRECTION) else typed
        }
        facilitator.closeDictionaries()
        return out
    }

    private val fixed = mapOf("im" to "I'm", "dont" to "don't", "ill" to "I'll", "cant" to "can't", "wont" to "won't",
        "youre" to "you're", "doesnt" to "doesn't", "didnt" to "didn't", "isnt" to "isn't", "thats" to "that's",
        "theyre" to "they're", "ive" to "I've", "hes" to "he's", "Dont" to "Don't", "IM" to "I'M")
    private val untouched = listOf("well", "were", "its", "id", "lets", "hell", "shell", "wed", "shed", "hed", "hid", "bot")

    @Test fun englishWithIndonesian() {
        assertEquals(fixed + untouched.associateWith { it }, commits(fixed.keys + untouched))
    }

    @Test fun afterARunOfIndonesian() {
        assertEquals(fixed + untouched.associateWith { it }, commits(fixed.keys + untouched, englishConfidence = 0))
    }

    @Test fun indonesianOnly() {
        // no apostrophe fix without English (Indonesian's own autocorrect may still change a word: "dont" → "dong")
        val result = commits(listOf("dont", "im", "youre", "ill", "cant"), locale = Locale.forLanguageTag("id"))
        assertTrue(result.values.none { '\'' in it }, "$result")
    }

    companion object {
        private var loaded = false
    }
}
