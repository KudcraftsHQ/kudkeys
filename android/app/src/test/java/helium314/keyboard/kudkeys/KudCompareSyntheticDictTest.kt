// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import helium314.keyboard.latin.makedict.DictIO
import helium314.keyboard.latin.makedict.DictionaryHeader
import helium314.keyboard.latin.makedict.FormatSpec
import helium314.keyboard.latin.makedict.FusionDictionary
import helium314.keyboard.latin.makedict.ProbabilityInfo
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test

/**
 * Research only (SwiftKey comparison, branch tools/swiftkey-compare): writes a synthetic add-on dictionary shaped
 * like a SwiftKey wordlist import (every word at 140, sentence-start capitalised twins next to lowercase words,
 * a few capitalised-only words) to $SYNTH_DICT_OUT. Common words only, never anyone's personal list.
 */
@RunWith(RobolectricTestRunner::class)
class KudCompareSyntheticDictTest {
    @Test fun writeSyntheticSwiftKeyStyleDict() {
        val out = System.getenv("SYNTH_DICT_OUT")
        assumeTrue("SYNTH_DICT_OUT not set", !out.isNullOrBlank())
        val twins = listOf("aku", "kamu", "mau", "makan", "udah", "banget", "kalo", "gak", "yang", "sama", "nanti",
            "lagi", "jadi", "tapi", "terus", "kita", "besok", "meeting", "the", "and", "thanks", "okay", "kantor")
        val capitalisedOnly = listOf("Kemarin", "Sekarang", "Jakarta", "Bandung", "Andi")
        val words = twins + twins.map { it.replaceFirstChar(Char::uppercase) } + capitalisedOnly
        val attrs = hashMapOf(
            DictionaryHeader.DICTIONARY_ID_KEY to "swiftkey:id",
            DictionaryHeader.DICTIONARY_LOCALE_KEY to "id",
            DictionaryHeader.DICTIONARY_VERSION_KEY to "1",
            DictionaryHeader.DICTIONARY_DATE_KEY to (System.currentTimeMillis() / 1000).toString(),
            DictionaryHeader.DICTIONARY_DESCRIPTION_KEY to "synthetic SwiftKey-style import",
        )
        val dict = FusionDictionary(FusionDictionary.PtNodeArray(), FormatSpec.DictionaryOptions(attrs))
        for (w in words.sorted()) dict.add(w, ProbabilityInfo(140), null, false, false)
        File(out!!).parentFile?.mkdirs()
        DictIO.writeBinaryDictionary(out, dict)
        println("wrote ${words.size} words to $out")
    }
}
