// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.kudcrafts.kudkeys.voice.TextClient

/**
 * The text Fix and Instruct work on: the selection, or the field around the cursor. [start] is the absolute offset
 * of [text] in the field; [selStart]/[selEnd] the selection (equal = cursor) when it was read. [truncated]: a selection
 * longer than the limit, of which [text] is the start.
 */
data class FieldSnapshot(
    val start: Int,
    val text: String,
    val selStart: Int,
    val selEnd: Int,
    val isSelection: Boolean,
    val truncated: Boolean = false,
) {
    val end get() = start + text.length
}

/** Reads and edits the current field. The app's is [InputConnectionFieldEditor]; tests can fake it. */
interface FieldEditor {
    /**
     * The selection, or up to [max] chars around the cursor; null without a field. A window that doesn't reach the
     * field's edges starts and ends at a paragraph or sentence boundary (else a space), never inside a word or an
     * emoji, so the model never sees a cut-off word it would "fix".
     */
    fun snapshot(max: Int): FieldSnapshot?
    /** The text now at [start]..[end], or null when it can't be read. */
    fun read(start: Int, end: Int): String?
    /**
     * In one batch edit: [start]..[end] becomes [text], then the selection is [selStart]..[selEnd]. Before anything
     * is written, the range is selected and checked to hold [expected] (an empty range: the field must be empty);
     * false, with nothing changed, when it doesn't: the offsets were wrong (an app without extracted text whose
     * cursor the IME misjudged).
     */
    fun replace(start: Int, end: Int, expected: String, text: String, selStart: Int, selEnd: Int): Boolean
}

/**
 * [FieldEditor] over an [InputConnection]. The absolute selection comes from extracted text when the app gives
 * it, else from [fallbackSelection] (the IME's own tracking). [beforeEdit] lets the IME finish a composing word.
 */
class InputConnectionFieldEditor(
    private val connection: () -> InputConnection?,
    private val fallbackSelection: () -> Pair<Int, Int>? = { null },
    private val beforeEdit: () -> Unit = {},
) : FieldEditor {

    private fun selection(ic: InputConnection): Pair<Int, Int>? {
        val et = runCatching { ic.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()
        if (et != null && et.selectionStart >= 0) {
            val a = et.startOffset + et.selectionStart
            val b = et.startOffset + et.selectionEnd
            return minOf(a, b) to maxOf(a, b)
        }
        return fallbackSelection()?.takeIf { it.first >= 0 && it.second >= 0 }?.let { minOf(it.first, it.second) to maxOf(it.first, it.second) }
    }

    override fun snapshot(max: Int): FieldSnapshot? {
        val ic = connection() ?: return null
        val (s, e) = selection(ic) ?: return null
        if (e > s) {
            val selected = ic.getSelectedText(0)?.toString() ?: return null
            val text = TextClient.safeTake(selected, max)
            return FieldSnapshot(s, text, s, s + text.length, isSelection = true, truncated = text.length < selected.length)
        }
        val before = ic.getTextBeforeCursor(max, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(max, 0)?.toString() ?: ""
        val half = max / 2
        val takeBefore = minOf(before.length, maxOf(half, max - after.length))
        val takeAfter = minOf(after.length, max - takeBefore)
        // More text beyond what we take (getText…Cursor(max) returning max chars may mean more): cut at a boundary.
        val b = TextClient.safeTakeLast(before, takeBefore).let { if (takeBefore < before.length || before.length >= max) snapStart(it) else it }
        val a = TextClient.safeTake(after, takeAfter).let { if (takeAfter < after.length || after.length >= max) snapEnd(it) else it }
        return FieldSnapshot(s - b.length, b + a, s, s, isSelection = false)
    }

    override fun read(start: Int, end: Int): String? {
        val ic = connection() ?: return null
        val (s, e) = selection(ic) ?: return null
        val span = end - start
        val before = ic.getTextBeforeCursor(span + (s - start).coerceAtLeast(0) + 1, 0)?.toString() ?: ""
        val selected = if (e > s) ic.getSelectedText(0)?.toString() ?: return null else ""
        val after = ic.getTextAfterCursor(span + (end - e).coerceAtLeast(0) + 1, 0)?.toString() ?: ""
        val bufStart = s - before.length
        val buf = before + selected + after
        val a = start - bufStart
        val b = end - bufStart
        return if (a < 0 || b > buf.length || a > b) null else buf.substring(a, b)
    }

    override fun replace(start: Int, end: Int, expected: String, text: String, selStart: Int, selEnd: Int): Boolean {
        val ic = connection() ?: return false
        beforeEdit()
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            val was = selection(ic)
            ic.setSelection(start, end)
            val there = if (start == end) {
                (ic.getTextBeforeCursor(1, 0)?.toString() ?: "") + (ic.getTextAfterCursor(1, 0)?.toString() ?: "")
            } else ic.getSelectedText(0)?.toString()
            if (there != expected) {
                was?.let { ic.setSelection(it.first, it.second) }
                return false
            }
            ic.commitText(text, 1)
            ic.setSelection(selStart, selEnd)
        } finally {
            ic.endBatchEdit()
        }
        return true
    }

    companion object {
        private val SENTENCE_END = Regex("""[.!?…]["')\]]?\s+|\n+""")
        private val SPACE = Regex("""\s+""")

        /** [s] (text before the cursor, cut at its start) from just after its first boundary in the first half. */
        fun snapStart(s: String): String {
            val limit = s.length / 2
            val m = SENTENCE_END.find(s)?.takeIf { it.range.last < limit } ?: SPACE.find(s)?.takeIf { it.range.last < limit }
            return if (m != null) s.substring(m.range.last + 1) else s
        }

        /** [s] (text after the cursor, cut at its end) up to its last boundary in the second half. */
        fun snapEnd(s: String): String {
            val limit = s.length / 2
            val m = SENTENCE_END.findAll(s).lastOrNull()?.takeIf { it.range.first >= limit }
            if (m != null) return s.substring(0, m.range.first + m.value.trimEnd().length)
            val sp = SPACE.findAll(s).lastOrNull()?.takeIf { it.range.first >= limit }
            return if (sp != null) s.substring(0, sp.range.first) else s
        }
    }
}
