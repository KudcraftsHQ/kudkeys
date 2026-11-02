// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import java.util.Locale

/**
 * English contractions typed without the apostrophe, fixed on space like SwiftKey: "dont" → "don't", "im" → "I'm".
 *
 * HeliBoard's autocorrect already finds most of these (the native code treats a missing apostrophe as an
 * intentional omission, and the English dictionary whitelists a few). Two things go wrong in mixed Indonesian and
 * English typing, and this fixes both:
 * - The dictionary's whitelist also turns real words into something else: `id` → "I'd" (an Indonesian word), `lets`
 *   → "let's", `hid` → "his", `bot` → "not". [blocksAutoCorrection] stops those.
 * - The fix can lose against a typed word the user history learned while autocorrect was off ("dont" typed many
 *   times), or against Indonesian when that language dominates. [fixFor] makes the listed contractions win.
 *
 * Only with autocorrect on, English among the active languages, and in a field for everyday words (see
 * [KudPrivacy.isForEverydayText]); Suggest checks all three. Backspace right after reverts the fix (InputLogic).
 */
object KudApostrophes {
    /** Typed (lowercase) → its contraction, for words that are never anything else on their own. */
    private val FIXES: Map<String, String> = listOf(
        "I'm", "I'll", "I've", // "ill" is deliberate: SwiftKey does it, and "ill" alone is rare in chat
        "don't", "can't", "won't", "doesn't", "didn't", "isn't", "aren't", "wasn't", "weren't", "haven't", "hasn't",
        "hadn't", "couldn't", "wouldn't", "shouldn't", "mustn't",
        "you're", "you've", "you'll", "you'd", "they're", "they've", "they'll", "we've",
        "that's", "what's", "where's", "there's", "here's", "he's", "she's", "it'll",
    ).associateBy { it.replace("'", "").lowercase(Locale.ENGLISH) }

    /** Real words an apostrophe would make wrong. Never corrected to anything but their own capitalisation. */
    private val NEVER = setOf("well", "were", "its", "id", "hell", "shell", "wed", "lets", "shed", "hed")

    /**
     * The contraction for [typed] in [typed]'s case ("Dont" → "Don't", "IM" → "I'M", "im" → "I'm"), or null when it
     * isn't one of the listed words or is in mixed case ("dONT" is typed on purpose).
     */
    @JvmStatic
    fun fixFor(typed: String): String? {
        val target = FIXES[typed.lowercase(Locale.ENGLISH)] ?: return null
        val rest = typed.drop(1)
        return when {
            typed.length > 1 && typed.none { it.isLowerCase() } -> target.uppercase(Locale.ENGLISH)
            rest.any { it.isUpperCase() } -> null
            typed[0].isUpperCase() -> target.replaceFirstChar { it.uppercaseChar() }
            else -> target
        }
    }

    /**
     * Whether autocorrect must not turn [typed] into [correction]: one of the [NEVER] words, or a dictionary
     * whitelist entry that would replace a real word ([isRealWord]) with a different one. A change of case alone
     * ("i" → "I") is always fine.
     */
    @JvmStatic
    fun blocksAutoCorrection(typed: String, correction: SuggestedWordInfo, isRealWord: (String) -> Boolean): Boolean {
        if (correction.mWord.equals(typed, ignoreCase = true)) return false
        val lower = typed.lowercase(Locale.ENGLISH)
        if (lower in NEVER) return true
        if (lower in FIXES) return false
        return correction.isKindOf(SuggestedWordInfo.KIND_WHITELIST) && (isRealWord(typed) || isRealWord(lower))
    }

    /** Whether [committed] is [typed] with apostrophes added: an apostrophe fix, which backspace reverts. */
    @JvmStatic
    fun isFix(typed: CharSequence, committed: CharSequence): Boolean {
        val c = committed.toString()
        return c.contains('\'') && c.replace("'", "").equals(typed.toString(), ignoreCase = true)
    }
}
