// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.getEnabledToolbarKeys
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.hiddenToolbarKeys
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pins the Kud Keys defaults (see docs/v0.1-spec.md): en-US + id together, dark, no borders, no number row. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class KudKeysDefaultsTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)

    @Test fun defaultSubtypeIsEnglishWithIndonesianSecondary() {
        val enUs = SubtypeSettings.getResourceSubtypesForLocale("en-US".constructLocale()).first()
        val expected = enUs.toSettingsSubtype().with(ExtraValue.SECONDARY_LOCALES, "id")
        assertEquals(expected, Defaults.KUDKEYS_DEFAULT_SUBTYPE.toSettingsSubtype())
    }

    @Test fun freshInstallEnablesOnlyMultilingualSubtype() {
        // SubtypeSettings is a singleton shared with other tests: start from empty prefs, as on a fresh install
        latinIME.prefs().edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(latinIME)
        val enabled = SubtypeSettings.getEnabledSubtypes()
        assertEquals(1, enabled.size)
        val subtype = enabled.single()
        assertEquals("en-US", subtype.locale().toLanguageTag())
        assertEquals(listOf("id".constructLocale()), getSecondaryLocales(subtype.extraValue))
        assertEquals(subtype, SubtypeSettings.getSelectedSubtype(latinIME.prefs()))
    }

    @Test fun indonesianDictionaryIsBundled() {
        val assets = DictionaryInfoUtils.getAssetsDictionaryList(latinIME)!!.toList()
        assertTrue("main_id.dict" in assets, "assets: $assets")
        assertTrue("main_en-US.dict" in assets, "assets: $assets")
        // Java may report Indonesian as the legacy "in" code; both must resolve to the same locale
        assertEquals("id".constructLocale(), DictionaryInfoUtils.extractLocaleFromAssetsDictionaryFile("main_id.dict"))
        assertEquals("in".constructLocale(), DictionaryInfoUtils.extractLocaleFromAssetsDictionaryFile("main_id.dict"))
    }

    @Test fun appearanceDefaults() {
        assertEquals(KeyboardTheme.THEME_DARK, Defaults.PREF_THEME_COLORS)
        assertFalse(Defaults.PREF_THEME_DAY_NIGHT)
        assertFalse(Defaults.PREF_THEME_KEY_BORDERS)
        assertFalse(Defaults.PREF_SHOW_NUMBER_ROW)
        assertNotEquals(ToolbarMode.HIDDEN.name, Defaults.PREF_TOOLBAR_MODE)
    }

    @Test fun noGestureTypingAndNoClipboardToolbarKeys() {
        assertFalse(Settings.getValues().mGestureInputEnabled)
        val keys = getEnabledToolbarKeys(latinIME.prefs())
        assertTrue(keys.none { it in hiddenToolbarKeys }, "toolbar keys: $keys")
    }
}
