// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.kudkeys.sync.KudSettingsSync
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
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

/** v0.4.5: existing installs get autocorrect off and the 300 ms long-press once; later choices stay. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class KudMigrationsTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val prefs = latinIME.prefs()

    @BeforeTest fun clear() = prefs.edit { clear() }

    @Test fun newDefaults() {
        assertFalse(Defaults.PREF_AUTO_CORRECTION)
        assertEquals(300, Defaults.PREF_KEY_LONGPRESS_TIMEOUT)
    }

    @Test fun oldInstallGetsTheNewTypingDefaultsOnce() {
        prefs.edit {
            putInt(Settings.PREF_VERSION_CODE, 1)
            putBoolean(Settings.PREF_AUTO_CORRECTION, true)
            putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 450)
        }
        checkVersionUpgrade(latinIME) // what App.onCreate runs
        assertFalse(prefs.getBoolean(Settings.PREF_AUTO_CORRECTION, true))
        assertEquals(300, prefs.getInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 0))
        assertTrue(prefs.getBoolean(KudMigrations.PREF_DONE_V045_TYPING, false))

        // he turns autocorrect back on and picks 450 again: the next start leaves both alone
        prefs.edit {
            putBoolean(Settings.PREF_AUTO_CORRECTION, true)
            putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 450)
        }
        checkVersionUpgrade(latinIME)
        assertTrue(prefs.getBoolean(Settings.PREF_AUTO_CORRECTION, false))
        assertEquals(450, prefs.getInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 0))
    }

    @Test fun storedDefaultsAreMigratedToo() {
        // a pref never written means the old default (450, autocorrect on) is what the phone used
        prefs.edit { putInt(Settings.PREF_VERSION_CODE, 1) }
        KudMigrations.run(prefs)
        assertEquals(300, prefs.getInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 0))
        assertFalse(prefs.getBoolean(Settings.PREF_AUTO_CORRECTION, true))
    }

    @Test fun aChosenLongPressDurationStays() {
        prefs.edit { putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 600) }
        KudMigrations.run(prefs)
        assertEquals(600, prefs.getInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 0))
    }

    @Test fun settingsReadTheMigratedValues() {
        prefs.edit {
            putBoolean(Settings.PREF_AUTO_CORRECTION, true)
            putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 450)
        }
        KudMigrations.run(prefs)
        Settings.getInstance().loadSettings(latinIME, java.util.Locale.US,
            helium314.keyboard.latin.InputAttributes(android.view.inputmethod.EditorInfo(), false, latinIME.packageName))
        assertFalse(Settings.getValues().mAutoCorrectEnabled)
        assertEquals(300, Settings.getValues().mKeyLongpressTimeout)
    }

    @Test fun theFlagIsNeverSynced() {
        assertFalse(KudSettingsSync.isSynced(KudMigrations.PREF_DONE_V045_TYPING))
    }
}
