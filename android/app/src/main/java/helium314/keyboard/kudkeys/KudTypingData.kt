// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.content.Context
import android.provider.UserDictionary
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.utils.Log

/** Typing → Clear typing data: forget what was learned (user history) and the personal dictionary. */
object KudTypingData {
    /** Returns how many personal-dictionary words were deleted (-1 if the dictionary could not be reached). */
    fun clear(context: Context): Int {
        // closes and clears the open user-history dictionaries, then deletes their files
        PersonalizationHelper.removeAllUserHistoryDictionaries(context)
        return try {
            context.contentResolver.delete(UserDictionary.Words.CONTENT_URI, null, null)
        } catch (e: Exception) {
            Log.w("KudTypingData", "could not clear the personal dictionary", e)
            -1
        }
    }
}
