// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys.sync

import android.content.Context
import android.content.Intent
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.latin.makedict.DictIO
import helium314.keyboard.latin.makedict.DictionaryHeader
import helium314.keyboard.latin.makedict.FormatSpec
import helium314.keyboard.latin.makedict.FusionDictionary
import helium314.keyboard.latin.makedict.ProbabilityInfo
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import java.io.File
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The synced words of one locale as a HeliBoard add-on dictionary: type `synced`, file `synced_user.dict` in the
 * locale's dictionary cache dir, which `DictionaryFactory` loads next to the main dictionary (a non-`main` type in
 * that dir is an add-on, like a user-added dictionary). Written with HeliBoard's own dictionary writer. Never the
 * shared Android personal dictionary.
 */
object KudSyncedDictionary {
    const val TYPE = "synced"
    const val FILE_NAME = "${TYPE}_${DictionaryInfoUtils.USER_DICTIONARY_SUFFIX}" // synced_user.dict

    fun file(context: Context, locale: Locale): File? =
        DictionaryInfoUtils.getCacheDirectoryForLocale(locale, context)?.let { File(it, FILE_NAME) }

    /** Unigram probability (0..255) from a use count: 1 use → 130, 10 → 172, 100+ → 210. */
    fun probability(count: Int): Int = (130 + 17.4 * ln(count.coerceAtLeast(1).toDouble())).roundToInt().coerceIn(130, 210)

    /** (Re)writes [file] with [words] (word → count); deletes it when there are none. Atomic: temp file + rename. */
    fun write(file: File, locale: Locale, words: Map<String, Int>) {
        val usable = words.filterKeys { it.isNotBlank() && it.length <= 48 && !it.any(Char::isWhitespace) }
        if (usable.isEmpty()) {
            file.delete()
            return
        }
        val attrs = hashMapOf(
            DictionaryHeader.DICTIONARY_ID_KEY to "$TYPE:${locale.toLanguageTag()}",
            DictionaryHeader.DICTIONARY_LOCALE_KEY to locale.toLanguageTag(),
            DictionaryHeader.DICTIONARY_VERSION_KEY to "1",
            DictionaryHeader.DICTIONARY_DATE_KEY to (System.currentTimeMillis() / 1000).toString(),
            DictionaryHeader.DICTIONARY_DESCRIPTION_KEY to "Kud Keys synced words",
        )
        val dict = FusionDictionary(FusionDictionary.PtNodeArray(), FormatSpec.DictionaryOptions(attrs))
        for ((word, count) in usable.toSortedMap()) dict.add(word, ProbabilityInfo(probability(count)), null, false, false)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".$FILE_NAME.tmp")
        tmp.delete()
        DictIO.writeBinaryDictionary(tmp.absolutePath, dict)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Tells the keyboard to reload its dictionaries (the same broadcast a newly added dictionary sends). */
    fun notifyChanged(context: Context) {
        context.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION).setPackage(context.packageName))
    }
}
