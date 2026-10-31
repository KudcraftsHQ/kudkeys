// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PopupKeysKeyboardView
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SwiftKey's long-press popup: once the popup is open, sliding sideways on the key row picks by x,
 * without first moving up into the panel. Driven by touch events on the real keyboard view.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowInputMethodManager2::class], qualifiers = "w411dp-h891dp-port-xxhdpi")
class KudPopupSlideTest {
    /** A key with a long-press popup ("." opens SwiftKey's instant panel instead, see KudPeriodPanelTest). */
    private val LONG_PRESS_KEY = 'e'

    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val switcher = KeyboardSwitcher.getInstance()
    private val view get() = switcher.mainKeyboardView
    private val committed = mutableListOf<Int>()
    private var downTime = 0L

    @BeforeTest fun setUp() {
        latinIME.prefs().edit { clear() }
        switcher.onCreateInputView(latinIME, true)
        switcher.reloadMainKeyboard()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        val width = latinIME.resources.displayMetrics.widthPixels
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.setKeyboardActionListener(object : KeyboardActionListener by KeyboardActionListener.EMPTY_LISTENER {
            override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) { committed += primaryCode }
        })
    }

    private fun touch(action: Int, x: Int, y: Int) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val me = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
        view.onTouchEvent(me)
        me.recycle()
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
    }

    private fun field(owner: Any, cls: Class<*>, name: String) = cls.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private val panel get() = field(view, MainKeyboardView::class.java, "mPopupKeysPanel") as PopupKeysKeyboardView?
    private val highlighted get() = panel?.let { field(it, PopupKeysKeyboardView::class.java, "mCurrentKey") as Key? }

    /** Long-press [code] at [heightFraction] of its height (0.5 = centre); returns the finger position. */
    private fun longPress(code: Int, heightFraction: Float = 0.5f): Pair<Int, Int> {
        val key = switcher.keyboard!!.getKey(code)!!
        val x = key.x + key.width / 2
        val y = key.y + (key.height * heightFraction).toInt()
        touch(MotionEvent.ACTION_DOWN, x, y)
        ShadowLooper.idleMainLooper(Settings.getValues().mKeyLongpressTimeout + 100L, TimeUnit.MILLISECONDS)
        assertNotNull(panel, "popup did not open")
        return x to y
    }

    private fun highlightedLabelAtStart(): String? {
        val (x, y) = longPress(LONG_PRESS_KEY.code)
        val label = highlighted!!.label
        touch(MotionEvent.ACTION_MOVE, x, y + view.height)
        touch(MotionEvent.ACTION_UP, x, y + view.height)
        committed.clear()
        return label
    }

    /** Where the finger has to be (in keyboard-view x) to select popup key [k]: relative to where it started. */
    private fun screenXOf(k: Key): Int {
        val p = panel!!
        val originX = field(p, PopupKeysKeyboardView::class.java, "mOriginX") as Int
        val slideOffsetX = field(p, PopupKeysKeyboardView::class.java, "mSlideOffsetX") as Int
        return originX + slideOffsetX + p.paddingLeft + k.x + k.width / 2
    }

    private fun popupRows() = panel!!.keyboard!!.sortedKeys.filterNot { it.isSpacer }
        .groupBy { it.y }.toSortedMap().values.map { row -> row.sortedBy { it.x } }

    @Test fun slideSidewaysOnTheKeyRowSelectsByX() = slideSideways(0.5f)

    /** A thumb usually lands low on the bottom row's keys. */
    @Test fun slideSidewaysFromLowOnTheKey() = slideSideways(0.95f)

    private fun slideSideways(heightFraction: Float) {
        val (x, y) = longPress(LONG_PRESS_KEY.code, heightFraction)
        val start = highlighted
        assertNotNull(start, "the default popup key is highlighted when the popup opens")
        val row = popupRows().last()
        assertTrue(row.size >= 2, "popup: ${row.map { it.label }}")
        // slide to every other key in the bottom row, keeping the finger at the same height
        for (k in row) {
            touch(MotionEvent.ACTION_MOVE, screenXOf(k), y)
            assertEquals(k.label, highlighted?.label, "finger at y=$y over ${k.label}")
        }
        // release commits what is highlighted
        val target = row.first { it !== start }
        touch(MotionEvent.ACTION_MOVE, screenXOf(target), y)
        touch(MotionEvent.ACTION_UP, screenXOf(target), y)
        assertEquals(listOf(target.code), committed.filter { it > 0 })
        assertNull(panel)
        assertTrue(x > 0)
    }

    @Test fun releasingWithoutMovingCommitsTheDefault() {
        val (x, y) = longPress(LONG_PRESS_KEY.code)
        val default = highlighted!!
        touch(MotionEvent.ACTION_UP, x, y)
        assertEquals(listOf(default.code), committed.filter { it > 0 })
    }

    /** HeliBoard's cancel, unchanged: sliding well away closes the popup and nothing from it is typed. */
    @Test fun slidingFarDownCancels() {
        val (x, y) = longPress(LONG_PRESS_KEY.code)
        val popupCodes = panel!!.keyboard!!.sortedKeys.map { it.code }.toSet()
        touch(MotionEvent.ACTION_MOVE, x, y + view.height)
        assertNull(panel)
        touch(MotionEvent.ACTION_UP, x, y + view.height)
        assertTrue(committed.none { it in popupCodes }, "committed $committed")
    }

    /** From the bottom keyboard row there's little room below the finger: about one key height down must cancel. */
    @Test fun slidingDownOneKeyHeightCancels() {
        val (x, y) = longPress(LONG_PRESS_KEY.code)
        val popupCodes = panel!!.keyboard!!.sortedKeys.map { it.code }.toSet()
        val keyHeight = switcher.keyboard!!.getKey(LONG_PRESS_KEY.code)!!.height
        touch(MotionEvent.ACTION_MOVE, x, y + keyHeight)
        assertNull(panel)
        touch(MotionEvent.ACTION_UP, x, y + keyHeight)
        assertTrue(committed.none { it in popupCodes }, "committed $committed")
    }

    // ---- v0.4.5: sliding is relative to where the finger started ----

    /** Long-press the test key, slide [steps] popup-key widths sideways (negative = left), release: what is typed. */
    private fun slideAndRelease(steps: Int): String {
        committed.clear()
        val (x, y) = longPress(LONG_PRESS_KEY.code)
        val row = popupRows().last()
        val column = row[1].x - row[0].x
        // move in small steps like a real finger, so no step skips the panel
        val targetX = x + steps * column
        val moves = 6
        for (m in 1..moves) touch(MotionEvent.ACTION_MOVE, x + (targetX - x) * m / moves, y)
        assertNotNull(panel, "sliding sideways never cancels")
        touch(MotionEvent.ACTION_UP, targetX, y)
        assertNull(panel)
        return committed.filter { it > 0 }.joinToString("") { String(Character.toChars(it)) }
    }

    @Test fun slidingSidewaysMovesOneKeyPerKeyWidthFromTheStart() {
        longPress(LONG_PRESS_KEY.code)
        val labels = popupRows().last().map { it.label }
        touch(MotionEvent.ACTION_UP, 0, 0)
        val start = labels.indexOf(highlightedLabelAtStart())
        for (steps in listOf(1, 2, 3, -1, -2, -3)) {
            val expected = labels[(start + steps).coerceIn(0, labels.lastIndex)]
            assertEquals(expected, slideAndRelease(steps), "slide $steps key widths in $labels")
        }
    }

    @Test fun slidingPastEitherEndKeepsTheEndKey() {
        longPress(LONG_PRESS_KEY.code)
        val labels = popupRows().last().map { it.label }
        touch(MotionEvent.ACTION_UP, 0, 0)
        assertEquals(labels.first(), slideAndRelease(-15)) // past the screen's left edge
        assertEquals(labels.last(), slideAndRelease(15)) // past the screen's right edge
    }

    @Test fun screenshotOfTheOpenPopup() {
        val (x, y) = longPress(LONG_PRESS_KEY.code)
        val row = popupRows().last()
        val column = row[1].x - row[0].x
        touch(MotionEvent.ACTION_MOVE, x - column, y) // one key left: the highlight moves one key
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        val p = panel!!
        val container = p.containerView
        if (container.width == 0) {
            container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            container.layout(0, 0, container.measuredWidth, container.measuredHeight)
        }
        canvas.save()
        canvas.translate(container.x, container.y)
        container.draw(canvas)
        canvas.restore()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 6f }
        canvas.drawCircle((x - column).toFloat(), y.toFloat(), 28f, paint)
        paint.color = Color.YELLOW
        canvas.drawCircle(x.toFloat(), y.toFloat(), 14f, paint) // where the long-press started
        val dir = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"), "kudkeys-popup").apply { mkdirs() }
        File(dir, "e-popup-slid-one-left.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        touch(MotionEvent.ACTION_UP, x - column, y)
    }

    @Test fun multiRowPopupSlidesUpThroughRows() {
        // "e" with all accented letters has more than one row
        latinIME.prefs().edit { putString(Settings.PREF_MORE_POPUP_KEYS, "all") }
        switcher.reloadMainKeyboard()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        val (_, y) = longPress('e'.code)
        val rows = popupRows()
        assertTrue(rows.size >= 2, "e popup rows: ${rows.map { r -> r.map { it.label } }}")
        val bottom = rows.last()
        val above = rows[rows.size - 2]
        // sideways at the starting height stays in the bottom row
        for (k in bottom) {
            touch(MotionEvent.ACTION_MOVE, screenXOf(k), y)
            assertEquals(k.label, highlighted?.label)
        }
        // one popup row up reaches the row above, same column
        val rowHeight = bottom.first().y - above.first().y
        val target = above.first()
        touch(MotionEvent.ACTION_MOVE, screenXOf(target), y - rowHeight)
        assertEquals(target.label, highlighted?.label)
        touch(MotionEvent.ACTION_UP, screenXOf(target), y - rowHeight)
        assertEquals(target.code, committed.last())
    }
}
