// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys.sync

import java.util.Locale

/** One word for a synced dictionary: its use count, and whether it only came from the SwiftKey import. */
data class KudSyncedEntry(val count: Int, val imported: Boolean)

/**
 * What the synced dictionaries are built from, by lang: the server's words (word → count), which of them came from
 * the SwiftKey import, and imported words still waiting to go to the server ([KudSyncStore.pendingImport]).
 */
data class KudSyncedWords(
    val mirror: Map<String, Map<String, Int>>,
    val imported: Map<String, Set<String>> = emptyMap(),
    val pending: Map<String, Set<String>> = emptyMap(),
) {
    val langs: Set<String> get() = mirror.keys + pending.keys

    fun hasWords(lang: String) = !mirror[lang].isNullOrEmpty() || !pending[lang].isNullOrEmpty()

    fun forLang(lang: String): Map<String, KudSyncedEntry> {
        val out = HashMap<String, KudSyncedEntry>()
        val imp = imported[lang].orEmpty()
        mirror[lang]?.forEach { (word, count) -> out[word] = KudSyncedEntry(count, word in imp) }
        pending[lang]?.forEach { word -> out.putIfAbsent(word, KudSyncedEntry(1, true)) }
        return out
    }
}

/** The main dictionary of a locale, as the filter asks it: a word's frequency, or [NOT_A_WORD]. */
fun interface KudMainWords {
    fun frequency(word: String): Int

    companion object {
        const val NOT_A_WORD = -1 // Dictionary.NOT_A_PROBABILITY
    }
}

/**
 * Decides which synced words go into a locale's add-on dictionary, against its main dictionary. The server keeps
 * every row; only the phone's dictionary is filtered. Mostly about the SwiftKey import, whose ~17k words come with a
 * flat weight and many sentence-start capitals ("Kalau"):
 * - **Capital twins** ("Aku" next to "aku"): only the lowercase one, with the higher count, unless the main
 *   dictionary prefers the capital ("Jakarta"), which then wins.
 * - **Capital only**: lowercase when the main dictionary has the lowercase word and doesn't prefer the capital;
 *   otherwise kept as it is (probably a name).
 * - **ALL CAPS**: folded to the main dictionary's form when it has the word in lowercase or with a first capital;
 *   otherwise kept (an acronym).
 * - **Already in the main dictionary**: an imported word whose result the main dictionary has is left out, so its
 *   flat import weight can't outrank the main dictionary.
 *
 * Words typed on a phone or added by hand are always kept, in their own case: they carry real use. Only imported
 * words are folded or dropped. Without a main dictionary nothing is filtered.
 */
object KudSyncedWordFilter {
    fun filter(words: Map<String, KudSyncedEntry>, main: KudMainWords?, locale: Locale): Map<String, Int> {
        if (main == null) return words.mapValues { it.value.count }
        val freq = HashMap<String, Int>()
        fun frequency(word: String) = freq.getOrPut(word) { main.frequency(word) }
        fun has(word: String) = frequency(word) != KudMainWords.NOT_A_WORD
        fun prefersCapital(capital: String, lower: String) = has(capital) && frequency(capital) > frequency(lower)

        val counts = HashMap<String, Int>()
        val importedOnly = HashMap<String, Boolean>()
        fun put(word: String, entry: KudSyncedEntry) {
            counts.merge(word, entry.count, ::maxOf)
            importedOnly[word] = (importedOnly[word] ?: true) && entry.imported
        }

        for ((lower, group) in words.entries.groupBy { it.key.lowercase(locale) }) {
            val firstCapital = lower.replaceFirstChar { it.titlecase(locale) }
            // where an imported capital folds to: the lowercase word, or the capital when main prefers it ("Jakarta")
            val folded = if (firstCapital != lower && prefersCapital(firstCapital, lower)) firstCapital else lower
            val hasLowercase = group.any { it.key == lower }
            for ((word, entry) in group) {
                val target = when {
                    !entry.imported -> word
                    word == lower -> folded
                    word == firstCapital -> if (hasLowercase || has(lower)) folded else word
                    word.length > 1 && word == lower.uppercase(locale) -> if (has(lower) || has(firstCapital)) folded else word
                    else -> word // mixed case ("iPhone") stays
                }
                put(target, entry)
            }
        }
        return counts.filterKeys { word -> importedOnly[word] != true || !(has(word) || has(word.lowercase(locale))) }
    }
}
