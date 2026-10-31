// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * The text Fix and Instruct work on: the selection, or the field around the cursor. [start] is the absolute offset
 * of [text] in the field; [selStart]/[selEnd] the selection (equal = cursor) when it was read.
 */
data class FieldSnapshot(val start: Int, val text: String, val selStart: Int, val selEnd: Int, val isSelection: Boolean) {
    val end get() = start + text.length
}

/** Reads and edits the current field. The app's is [InputConnectionFieldEditor]; tests can fake it. */
interface FieldEditor {
    /** The selection, or up to [max] chars around the cursor; null without a field. */
    fun snapshot(max: Int): FieldSnapshot?
    /** The text now at [start]..[end], or null when it can't be read. */
    fun read(start: Int, end: Int): String?
    /** In one batch edit: [start]..[end] becomes [text], then the selection is [selStart]..[selEnd]. */
    fun replace(start: Int, end: Int, text: String, selStart: Int, selEnd: Int): Boolean
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
            val text = selected.take(max)
            return FieldSnapshot(s, text, s, s + text.length, isSelection = true)
        }
        val before = ic.getTextBeforeCursor(max, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(max, 0)?.toString() ?: ""
        val half = max / 2
        val takeBefore = minOf(before.length, maxOf(half, max - after.length))
        val takeAfter = minOf(after.length, max - takeBefore)
        val text = before.takeLast(takeBefore) + after.take(takeAfter)
        return FieldSnapshot(s - takeBefore, text, s, s, isSelection = false)
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

    override fun replace(start: Int, end: Int, text: String, selStart: Int, selEnd: Int): Boolean {
        val ic = connection() ?: return false
        beforeEdit()
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            ic.setSelection(start, end)
            ic.commitText(text, 1)
            ic.setSelection(selStart, selEnd)
        } finally {
            ic.endBatchEdit()
        }
        return true
    }
}
