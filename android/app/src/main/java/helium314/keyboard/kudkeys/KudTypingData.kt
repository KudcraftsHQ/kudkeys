// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.content.Context
import helium314.keyboard.latin.personalization.PersonalizationHelper

/**
 * Typing → Clear typing data: forget what Kud Keys learned from typing (its user-history dictionaries, the
 * next-word and frequency data behind its predictions).
 *
 * Never touches the Android personal dictionary (UserDictionary): it is shared with Settings and every other
 * keyboard on the phone, and Kud Keys does not own it.
 */
object KudTypingData {
    fun clear(context: Context) {
        // closes and clears the open user-history dictionaries, then deletes their files
        PersonalizationHelper.removeAllUserHistoryDictionaries(context)
    }
}
