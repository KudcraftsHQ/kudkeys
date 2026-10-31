// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

object VoiceText {
    /** [text] with a space in front when [before] (the text before the cursor) ends in a non-space character. */
    @JvmStatic
    fun withLeadingSpace(before: CharSequence?, text: String): String {
        if (text.isEmpty() || text.first().isWhitespace()) return text
        val last = before?.lastOrNull() ?: return text
        return if (last.isWhitespace()) text else " $text"
    }

    /** 0:07, 1:23 */
    fun elapsed(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
