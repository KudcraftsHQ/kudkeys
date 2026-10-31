// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

/**
 * One-time changes to existing installs when a Kud Keys default changes on purpose. Each runs once, marked by its
 * own flag (not the version code: debug builds all have code 1). The flags are never synced.
 */
object KudMigrations {
    /** v0.4.5: space keeps the word as typed (autocorrect off) and long-press opens popups after 300 ms. */
    const val PREF_DONE_V045_TYPING = "kudkeys_migrated_v045_typing"
    const val OLD_LONGPRESS_TIMEOUT = 450

    val FLAGS = setOf(PREF_DONE_V045_TYPING)

    fun run(prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_DONE_V045_TYPING, false)) return
        prefs.edit {
            // Hammas asked for autocorrect off on his phone too; he can turn it back on in Typing.
            putBoolean(Settings.PREF_AUTO_CORRECTION, Defaults.PREF_AUTO_CORRECTION)
            // Only the old default moves; a duration somebody picked stays.
            if (prefs.getInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, OLD_LONGPRESS_TIMEOUT) == OLD_LONGPRESS_TIMEOUT)
                putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, Defaults.PREF_KEY_LONGPRESS_TIMEOUT)
            putBoolean(PREF_DONE_V045_TYPING, true)
        }
    }
}
