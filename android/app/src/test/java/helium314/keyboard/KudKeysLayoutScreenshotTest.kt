// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders the main keyboard (letters and symbols) at a 411 dp wide phone (1079 px, xxhdpi) and writes PNGs to
 * `$TMPDIR/kudkeys-layout/`, to compare against the SwiftKey reference screenshot by eye.
 * Also pins the SwiftKey geometry (key widths, bottom row order).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowInputMethodManager2::class], qualifiers = "w411dp-h891dp-port-420dpi")
class KudKeysLayoutScreenshotTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val switcher = KeyboardSwitcher.getInstance()
    private val outDir = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"), "kudkeys-layout")

    init {
        latinIME.prefs().edit().clear().commit()
        switcher.onCreateInputView(latinIME, true)
        switcher.reloadMainKeyboard()
    }

    private fun render(name: String) {
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        val view = switcher.mainKeyboardView
        val width = latinIME.resources.displayMetrics.widthPixels
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // the keyboard background is painted on a parent view
        canvas.drawColor(Settings.getValues().mColors.get(ColorType.MAIN_BACKGROUND))
        view.draw(canvas)
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun touchKey(code: Int, action: Int) {
        val key = switcher.keyboard!!.getKey(code)!!
        val me = MotionEvent.obtain(0L, 0L, action, (key.x + key.width / 2).toFloat(), (key.y + key.height / 2).toFloat(), 0)
        switcher.mainKeyboardView.onTouchEvent(me)
    }

    @Test fun lettersLayout() {
        render("letters")
        val keyboard = switcher.keyboard!!
        val w = keyboard.mOccupiedWidth.toFloat()
        val rows = keyboard.sortedKeys.filterNot { it.isSpacer }.groupBy { it.y }.toSortedMap().values.map { row -> row.sortedBy { it.x } }
        assertEquals(4, rows.size, "rows: ${rows.map { r -> r.map { it.label } }}")
        // row 2 is indented half a key on each side
        val a = rows[1].first()
        assertTrue(a.x / w in 0.04f..0.06f, "A starts at ${a.x / w}")
        // shift and backspace are 1.5 keys wide
        assertEquals(KeyCode.SHIFT, rows[2].first().code)
        assertEquals(KeyCode.DELETE, rows[2].last().code)
        val shiftSlot = (rows[2][1].x - rows[2][0].x) / w
        assertTrue(shiftSlot in 0.14f..0.16f, "shift is $shiftSlot wide")
        // bottom row: 123, emoji, comma, space, period, enter
        assertEquals(6, rows[3].size, "bottom row: ${rows[3].map { it.label ?: it.code }}")
        assertEquals(KeyCode.SYMBOL_ALPHA, rows[3][0].code)
        assertEquals(KeyCode.EMOJI, rows[3][1].code)
        assertEquals(','.code, rows[3][2].code)
        assertEquals(' '.code, rows[3][3].code)
        assertEquals('.'.code, rows[3][4].code)
    }

    /** SwiftKey's screenshot is taken at the start of a message, so the letters are shifted there. */
    @Test fun lettersShifted() {
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_UP)
        assertEquals(KeyboardElement.ALPHABET_MANUAL_SHIFTED, switcher.keyboard?.mId?.element)
        render("letters-shifted")
    }

    @Test fun symbolsLayout() {
        touchKey(KeyCode.SYMBOL_ALPHA, MotionEvent.ACTION_DOWN)
        touchKey(KeyCode.SYMBOL_ALPHA, MotionEvent.ACTION_UP)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(KeyboardElement.SYMBOLS, switcher.keyboard?.mId?.element)
        render("symbols")
    }
}
