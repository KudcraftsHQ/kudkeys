// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode

/**
 * Typing → Delete words when held (SwiftKey behaviour). A tap deletes one character. Holding waits a little
 * longer than a normal key repeat ([HOLD_START_MS]), then deletes a whole word per repeat — never characters —
 * starting slowly and speeding up ([WORD_INTERVAL_START_MS] × [WORD_INTERVAL_RAMP] per word) to a top speed of one
 * word per [WORD_INTERVAL_MIN_MS].
 *
 * Timeline: 550 ms → first word, then 320, 272, 231, 197, 167, 142, 121, 110, 110… ms between words.
 *
 * The repeat timer lives in `PointerTracker` (`onKeyRepeat` / `startKeyRepeatTimer`); word repeats reach
 * `InputLogic` as [KeyCode.DELETE_WORD], which falls back to a normal backspace when it cannot read the text.
 * With the setting off (or any other repeating key) HeliBoard's own timing and codes apply.
 */
object KudHoldDelete {
    const val PREF_DELETE_WORDS_WHEN_HELD = "kudkeys_delete_words_when_held"
    const val DEFAULT_DELETE_WORDS_WHEN_HELD = true

    /** How long backspace must be held before the first word goes (HeliBoard's normal repeat starts at 400). */
    const val HOLD_START_MS = 550
    /** Gap before the second word; each next gap is [WORD_INTERVAL_RAMP] times the last … */
    const val WORD_INTERVAL_START_MS = 320
    const val WORD_INTERVAL_RAMP = 0.85
    /** … until this top speed. */
    const val WORD_INTERVAL_MIN_MS = 110

    /** How far back to look for the word; a longer run (a URL) goes in pieces of this size. */
    const val LOOK_BEHIND = 128

    /** Whether key repeat number [repeatCount] (1-based) of [code] deletes a word: every repeat of a held backspace. */
    fun isWordRepeat(enabled: Boolean, code: Int, repeatCount: Int) =
        enabled && code == KeyCode.DELETE && repeatCount >= 1

    /** The code to send for key repeat number [repeatCount] of [code]. */
    @JvmStatic
    fun codeForRepeat(enabled: Boolean, code: Int, repeatCount: Int) =
        if (isWordRepeat(enabled, code, repeatCount)) KeyCode.DELETE_WORD else code

    /** Delay before key repeat number [repeatCount] of [code]; HeliBoard's own timing for anything but a word repeat. */
    @JvmStatic
    fun delayBeforeRepeat(enabled: Boolean, code: Int, repeatCount: Int, startTimeout: Int, interval: Int): Int = when {
        !isWordRepeat(enabled, code, repeatCount) -> if (repeatCount == 1) startTimeout else interval
        repeatCount == 1 -> HOLD_START_MS
        else -> maxOf(WORD_INTERVAL_MIN_MS, Math.round(WORD_INTERVAL_START_MS * Math.pow(WORD_INTERVAL_RAMP, (repeatCount - 2).toDouble())).toInt())
    }

    /**
     * How many chars (UTF-16 units) one word-delete removes from the end of [before], like ctrl+backspace:
     * trailing spaces plus the word before them. Letters and digits make a word, with ' ’ - inside it
     * ("don't", "anak-anak"); any other run (punctuation, emoji) counts as a word of its own. Combining marks,
     * variation selectors, ZWJ and skin tones stay with the character they belong to, so emoji and accented
     * letters are never split. A line break stops the space skip and goes on its own. 0 = nothing before.
     */
    @JvmStatic
    fun charCountToDelete(before: CharSequence): Int {
        var i = before.length
        // trailing spaces (not line breaks)
        while (i > 0 && isSpace(before[i - 1])) i--
        if (i < before.length) return before.length - i + wordLengthBefore(before, i)
        if (i == 0) return 0
        if (before[i - 1] == '\n' || before[i - 1] == '\r') {
            // a line break on its own (with its \r)
            return if (i >= 2 && before[i - 1] == '\n' && before[i - 2] == '\r') 2 else 1
        }
        return wordLengthBefore(before, i)
    }

    /** Length of the word or symbol run that ends at [end]; 0 when there is a line break or nothing there. */
    private fun wordLengthBefore(text: CharSequence, end: Int): Int {
        var i = end
        var runIsWord: Boolean? = null
        while (i > 0) {
            val cp = Character.codePointBefore(text, i)
            val size = Character.charCount(cp)
            when {
                isJoiner(cp) -> i -= size // belongs to whatever comes before it
                cp == '\n'.code || cp == '\r'.code || isSpace(cp) -> break
                else -> {
                    val isWord = isWordChar(cp)
                    if (runIsWord == null) runIsWord = isWord
                    else if (runIsWord != isWord) {
                        // ' ’ - between two word chars belong to the word
                        if (!(runIsWord && isInWordPunctuation(cp) && i - size > 0
                                    && isWordChar(Character.codePointBefore(text, i - size)))) break
                    }
                    i -= size
                }
            }
        }
        return end - i
    }

    private fun isSpace(c: Char) = isSpace(c.code)
    private fun isSpace(cp: Int) = cp != '\n'.code && cp != '\r'.code && (Character.isWhitespace(cp) || Character.isSpaceChar(cp))

    private fun isWordChar(cp: Int) = Character.isLetterOrDigit(cp) || cp == '_'.code

    private fun isInWordPunctuation(cp: Int) = cp == '\''.code || cp == '’'.code || cp == '-'.code

    /** Code points that attach to the character before them. */
    private fun isJoiner(cp: Int): Boolean {
        if (cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F) return true
        return when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
    }
}
