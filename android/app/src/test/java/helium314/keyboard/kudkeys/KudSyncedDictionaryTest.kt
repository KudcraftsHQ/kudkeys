// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.kudkeys.sync.KudSyncLocales
import helium314.keyboard.kudkeys.sync.KudSyncedDictionary
import helium314.keyboard.kudkeys.sync.SyncedDictFiles
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.WordComposer
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionaryFactory
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A real `synced_user.dict`, written from made-up words with HeliBoard's dictionary writer and loaded through
 * `DictionaryFactory` by the native dictionary code (built for the host by the `hostJni` Gradle task): its words
 * are valid and come back as suggestions for what was typed on the real keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class, ShadowOwnSandboxForNativeCode::class])
class KudSyncedDictionaryTest {
    @Before fun loadNativeCode() {
        val lib = File(System.getProperty("kudkeys.hostJni") ?: "")
        assumeTrue("host build of the native dictionary code missing (needs g++): $lib", lib.isFile)
        if (!loaded) { System.load(lib.absolutePath); loaded = true }
    }

    private fun suggestions(dict: Dictionary, typed: String): List<String> {
        val keyboard = KeyboardSwitcher.getInstance().keyboard!!
        val codePoints = typed.codePoints().toArray()
        val composer = WordComposer().apply { setComposingWord(codePoints, keyboard.getCoordinates(codePoints)) }
        return dict.getSuggestions(
            composer.composedDataSnapshot, NgramContext.EMPTY_PREV_WORDS_INFO, keyboard.proximityInfo.nativeProximityInfo,
            SettingsValuesForSuggestion(false, false), 0, 1f, floatArrayOf(Dictionary.NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL),
        )?.map { it.mWord }.orEmpty()
    }

    @Test fun syncedWordsLoadThroughDictionaryFactory_andAreSuggested() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        ime.prefs().edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ime)
        KeyboardSwitcher.getInstance().apply { onCreateInputView(ime, true); reloadMainKeyboard() }
        val locale = KudSyncLocales.enabled().first { KudSyncLocales.lang(it) == "en" }

        val words = mapOf("glorbixate" to 12, "vandelopia" to 1, "snorfleplex" to 3, "two words" to 5)
        SyncedDictFiles(ime).rebuild(setOf("en"), mapOf("en" to words))

        val file = KudSyncedDictionary.file(ime, locale)!!
        assertEquals("synced_user.dict", file.name)
        assertTrue(file.isFile)
        val dicts = DictionaryFactory.getMainDictsForLocale(ime, locale, false)
        val synced = dicts.single { it.mDictType == KudSyncedDictionary.TYPE }
        assertTrue(dicts.any { it.mDictType == Dictionary.TYPE_MAIN } || dicts.size == 1)
        assertTrue(synced.isValidWord("glorbixate"))
        assertTrue(synced.isValidWord("vandelopia"))
        assertFalse(synced.isValidWord("two words"))
        assertFalse(synced.isValidWord("glorbixat"))
        assertTrue(synced.getFrequency("glorbixate") > synced.getFrequency("vandelopia"))

        // typing the start of a synced word on the keyboard suggests it, from the synced add-on and the collection
        assertTrue("glorbixate" in suggestions(synced, "glorbix"), "suggestions: ${suggestions(synced, "glorbix")}")
        val collection = DictionaryFactory.createMainDictionaryCollection(ime, locale, false)
        assertTrue("snorfleplex" in suggestions(collection, "snorflep"))
        collection.close()

        // a removed word leaves the file on the next rebuild; no words left → no file
        dicts.forEach { it.close() }
        SyncedDictFiles(ime).rebuild(setOf("en"), mapOf("en" to words - "glorbixate"))
        val again = DictionaryFactory.getDictionary(file, locale)!!
        assertFalse(again.isValidWord("glorbixate"))
        assertTrue(again.isValidWord("vandelopia"))
        again.close()
        SyncedDictFiles(ime).rebuild(setOf("en"), emptyMap())
        assertFalse(file.exists())
    }

    companion object {
        /** Per Robolectric sandbox: the native library can only be loaded once per class loader. */
        private var loaded = false
    }
}

/**
 * No behaviour: an extra shadow gives [KudSyncedDictionaryTest] its own Robolectric sandbox (class loader), so the
 * native library it loads never reaches the other tests, which run without it.
 */
@Implements(KudSyncedDictionary::class)
class ShadowOwnSandboxForNativeCode
