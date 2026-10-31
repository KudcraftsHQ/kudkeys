// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

/** Voice keywords as the web app edits them: comma-separated adds, no case duplicates, ≤ 100 of ≤ 100 chars. */
object KudKeywords {
    const val MAX = 100
    const val MAX_LENGTH = 100

    enum class Hint { TooLong, TooMany }

    /** [raw] may hold several, comma-separated. Returns the new list and why something was left out. */
    fun add(list: List<String>, raw: String): Pair<List<String>, Hint?> {
        val seen = list.mapTo(HashSet()) { it.lowercase() }
        val next = list.toMutableList()
        var hint: Hint? = null
        for (part in raw.split(',')) {
            val k = part.replace(Regex("[<>\r\n]"), " ").trim()
            if (k.isEmpty() || k.lowercase() in seen) continue
            if (k.length > MAX_LENGTH) { hint = Hint.TooLong; continue }
            if (next.size >= MAX) { hint = Hint.TooMany; break }
            seen += k.lowercase()
            next += k
        }
        return next to hint
    }
}
