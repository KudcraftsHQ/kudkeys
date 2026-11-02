// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import helium314.keyboard.kudkeys.sync.KudMainWords
import helium314.keyboard.kudkeys.sync.KudSyncedEntry
import helium314.keyboard.kudkeys.sync.KudSyncedWordFilter
import helium314.keyboard.kudkeys.sync.KudSyncedWords
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What goes into a synced dictionary, against a fake main dictionary: the SwiftKey import is cleaned up (capital
 * twins folded, words the main dictionary has left out), typed and manual words are always kept. Made-up words.
 */
class KudSyncedWordFilterTest {
    private val locale = Locale.forLanguageTag("id")

    /** A main dictionary with these words and frequencies. */
    private fun main(vararg words: Pair<String, Int>) = words.toMap().let { m -> KudMainWords { m[it] ?: KudMainWords.NOT_A_WORD } }

    private fun imported(count: Int = 1) = KudSyncedEntry(count, imported = true)
    private fun typed(count: Int = 1) = KudSyncedEntry(count, imported = false)

    private fun filter(main: KudMainWords?, vararg words: Pair<String, KudSyncedEntry>) =
        KudSyncedWordFilter.filter(words.toMap(), main, locale)

    @Test fun capitalTwinsFoldToLowercaseWithTheHigherCount() {
        assertEquals(mapOf("zorbel" to 7), filter(main(), "Zorbel" to imported(7), "zorbel" to imported(2)))
    }

    @Test fun aCapitalTheMainDictionaryPrefersIsNeverLoweredByATwin() {
        // main capitalises "Flimbor" (a city, a brand): the lowercase twin folds up into it, and the main dictionary
        // already has that, so nothing from the import competes with it
        val m = main("Flimbor" to 180, "flimbor" to 40)
        assertEquals(emptyMap(), filter(m, "Flimbor" to imported(3), "flimbor" to imported(5)))
        // typed in lowercase, it stays as typed; the imported capital doesn't turn into "flimbor"
        assertEquals(mapOf("flimbor" to 2), filter(m, "Flimbor" to imported(3), "flimbor" to typed(2)))
    }

    @Test fun aCapitalOnlyWordTheMainDictionaryHasInLowercaseIsLoweredAndThenLeftToMain() {
        // a sentence-start capture ("Kalau"-like): main has the lowercase word, so the import adds nothing
        assertEquals(emptyMap(), filter(main("plimko" to 150), "Plimko" to imported(4)))
        // next to a typed lowercase word it only raises that word's count
        assertEquals(mapOf("plimko" to 4), filter(main("plimko" to 150), "Plimko" to imported(4), "plimko" to typed(1)))
    }

    @Test fun aCapitalOnlyWordMainDoesntKnowStays() {
        assertEquals(mapOf("Vandrel" to 1), filter(main("plimko" to 150), "Vandrel" to imported()))
    }

    @Test fun allCapsFoldsToTheMainDictionarysFormOrStaysAnAcronym() {
        val m = main("zorbel" to 150, "Flimbor" to 170)
        assertEquals(mapOf("zorbel" to 6), filter(m, "ZORBEL" to imported(6), "zorbel" to typed(2)))
        assertEquals(mapOf("Flimbor" to 3), filter(m, "FLIMBOR" to imported(), "Flimbor" to typed(3)))
        assertEquals(mapOf("ZQX" to 1), filter(m, "ZQX" to imported()))
        // a single capital letter isn't all caps
        assertEquals(mapOf("Q" to 1), filter(main(), "Q" to imported()))
    }

    @Test fun importedWordsTheMainDictionaryHasAreLeftOut_typedOnesStay() {
        val m = main("zorbel" to 150, "plimko" to 120)
        assertEquals(mapOf("plimko" to 3, "snarvik" to 1),
            filter(m, "zorbel" to imported(9), "plimko" to typed(3), "snarvik" to imported()))
    }

    @Test fun typedAndManualCapitalsAreKeptAsTheyAre() {
        // a name typed with its capital, even though main has the lowercase word
        assertEquals(mapOf("Plimko" to 2), filter(main("plimko" to 150), "Plimko" to typed(2)))
    }

    @Test fun mixedCaseStays() {
        assertEquals(mapOf("zOrbel" to 1), filter(main(), "zOrbel" to imported()))
    }

    @Test fun withoutAMainDictionaryNothingIsFiltered() {
        assertEquals(mapOf("Zorbel" to 2, "zorbel" to 1), filter(null, "Zorbel" to imported(2), "zorbel" to imported(1)))
    }

    @Test fun syncedWordsCombineMirrorSourcesAndPendingImports() {
        val words = KudSyncedWords(
            mirror = mapOf("id" to mapOf("zorbel" to 3, "plimko" to 1)),
            imported = mapOf("id" to setOf("plimko")),
            pending = mapOf("id" to setOf("snarvik", "zorbel"), "en" to setOf("vandrel")),
        )
        assertEquals(setOf("id", "en"), words.langs)
        assertEquals(mapOf(
            "zorbel" to KudSyncedEntry(3, false), // the server's row wins over a pending import
            "plimko" to KudSyncedEntry(1, true),
            "snarvik" to KudSyncedEntry(1, true),
        ), words.forLang("id"))
    }
}
