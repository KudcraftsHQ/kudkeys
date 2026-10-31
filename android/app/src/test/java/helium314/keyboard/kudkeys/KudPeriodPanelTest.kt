// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PopupKeysPanel
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SwiftKey's "." panel: opens on touch-down, `! , . ?`, "." over the finger, absolute selection. Real touch events. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowInputMethodManager2::class], qualifiers = "w411dp-h891dp-port-xxhdpi")
class KudPeriodPanelTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val switcher = KeyboardSwitcher.getInstance()
    private val view get() = switcher.mainKeyboardView
    private val typed = StringBuilder()
    private var downTime = 0L

    @BeforeTest fun setUp() {
        latinIME.prefs().edit { clear() }
        // phones report distinct multitouch; without it HeliBoard folds a second finger into single touches
        Shadows.shadowOf(latinIME.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT, true)
        switcher.onCreateInputView(latinIME, true)
        switcher.reloadMainKeyboard()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        val width = latinIME.resources.displayMetrics.widthPixels
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.setKeyboardActionListener(object : KeyboardActionListener by KeyboardActionListener.EMPTY_LISTENER {
            override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
                if (primaryCode > 0) typed.appendCodePoint(primaryCode)
            }
        })
    }

    private fun event(action: Int, x: Int, y: Int, idleMs: Long = 0) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val me = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
        view.onTouchEvent(me)
        me.recycle()
        if (idleMs > 0) ShadowLooper.idleMainLooper(idleMs, TimeUnit.MILLISECONDS)
    }

    private val periodKey get() = switcher.keyboard!!.getKey('.'.code)!!
    private val keyX get() = periodKey.x + periodKey.width / 2
    private val keyY get() = periodKey.y + periodKey.height / 2

    private val panel: KudPeriodPanel?
        get() = (MainKeyboardView::class.java.getDeclaredField("mPopupKeysPanel").apply { isAccessible = true }
            .get(view) as PopupKeysPanel?) as? KudPeriodPanel

    /** Touch ".", slide to [toX],[toY] in small steps, release there; returns what was typed. */
    private fun slide(toX: Int, toY: Int = keyY): String {
        typed.clear()
        event(MotionEvent.ACTION_DOWN, keyX, keyY)
        assertNotNull(panel, "panel opens on touch-down")
        for (m in 1..6) event(MotionEvent.ACTION_MOVE, keyX + (toX - keyX) * m / 6, keyY + (toY - keyY) * m / 6, 10)
        event(MotionEvent.ACTION_UP, toX, toY)
        assertNull(panel, "panel closes on release")
        return typed.toString()
    }

    @Test fun tapTypesPeriod() {
        event(MotionEvent.ACTION_DOWN, keyX, keyY)
        event(MotionEvent.ACTION_UP, keyX, keyY)
        assertEquals(".", typed.toString())
        assertNull(panel)
    }

    @Test fun panelIsShownImmediatelyOnDownWithFourItems() {
        event(MotionEvent.ACTION_DOWN, keyX, keyY) // no looper time at all: no long-press timer involved
        val p = assertNotNull(panel)
        assertTrue(p.isShowingInParent)
        assertEquals(listOf("!", ",", ".", "?"), p.labels)
        assertEquals(2, p.defaultIndex)
        assertEquals(2, p.selectedIndex)
        // the "." cell is centred on the key, and the finger is inside the panel
        assertTrue(kotlin.math.abs(p.cellCenterX(2) - keyX) <= 2, "cell ${p.cellCenterX(2)} key $keyX")
        val (l, t, r, b) = p.bounds.toList()
        assertTrue(keyX in l..r && keyY in t..b, "finger ($keyX,$keyY) in panel ${p.bounds.toList()}")
        assertTrue(r <= view.width, "panel stays on screen")
        assertTrue(b - t > periodKey.height * 3 / 2, "cells are tall: ${b - t} vs key ${periodKey.height}")
        event(MotionEvent.ACTION_UP, keyX, keyY)
    }

    @Test fun holdThenReleaseTypesPeriod() {
        event(MotionEvent.ACTION_DOWN, keyX, keyY, idleMs = 1000)
        assertNotNull(panel)
        event(MotionEvent.ACTION_UP, keyX, keyY)
        assertEquals(".", typed.toString())
    }

    private fun cellX(i: Int): Int {
        event(MotionEvent.ACTION_DOWN, keyX, keyY)
        val x = panel!!.cellCenterX(i)
        event(MotionEvent.ACTION_CANCEL, keyX, keyY)
        typed.clear()
        return x
    }

    @Test fun slideLeftOneCellTypesComma() = assertEquals(",", slide(cellX(1)))
    @Test fun slideLeftTwoCellsTypesExclamation() = assertEquals("!", slide(cellX(0)))
    @Test fun slideRightTypesQuestion() = assertEquals("?", slide(cellX(3)))
    @Test fun slidePastTheEndsKeepsTheEndCells() {
        assertEquals("!", slide(0))
        assertEquals("?", slide(view.width - 1))
    }
    @Test fun slideFarDownTypesNothing() = assertEquals("", slide(keyX, keyY + periodKey.height))
    @Test fun slideFarUpTypesNothing() = assertEquals("", slide(keyX, keyY - 4 * periodKey.height))
    @Test fun slidingUpWithinThePanelStillSelects() = assertEquals(",", slide(cellX(1), panel0Top() + 10))

    private fun panel0Top(): Int {
        event(MotionEvent.ACTION_DOWN, keyX, keyY)
        val t = panel!!.bounds[1]
        event(MotionEvent.ACTION_CANCEL, keyX, keyY)
        typed.clear()
        return t
    }

    /** Fast two-thumb typing: the next key goes down before "." is released. The "." must land first. */
    @Test fun secondFingerCommitsPeriodFirst() {
        val a = switcher.keyboard!!.getKey('a'.code)!!
        event(MotionEvent.ACTION_DOWN, keyX, keyY)
        val down2 = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0 }, MotionEvent.PointerProperties().apply { id = 1 }),
            arrayOf(MotionEvent.PointerCoords().apply { x = keyX.toFloat(); y = keyY.toFloat() },
                MotionEvent.PointerCoords().apply { x = (a.x + a.width / 2).toFloat(); y = (a.y + a.height / 2).toFloat() }),
            0, 0, 1f, 1f, 0, 0, 0, 0)
        view.onTouchEvent(down2)
        assertNull(panel, "the panel closed when the other finger went down")
        val up2 = MotionEvent.obtain(down2).apply { action = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) }
        view.onTouchEvent(up2)
        event(MotionEvent.ACTION_UP, keyX, keyY)
        assertEquals(".a", typed.toString())
    }

    @Test fun commaKeyIsUntouched() {
        val comma = switcher.keyboard!!.getKey(','.code)!!
        event(MotionEvent.ACTION_DOWN, comma.x + comma.width / 2, comma.y + comma.height / 2)
        assertNull(panel, "no instant panel on ','")
        event(MotionEvent.ACTION_UP, comma.x + comma.width / 2, comma.y + comma.height / 2)
        assertEquals(",", typed.toString())
    }

    /** The keyboard with the panel open (finger slid to ","), beside the bottom 45% of SwiftKey's screenshot. */
    @Test fun screenshotBesideSwiftKey() {
        val toX = cellX(2) // finger on "." (the default): what the reference shows
        event(MotionEvent.ACTION_DOWN, keyX, keyY)
        event(MotionEvent.ACTION_MOVE, toX, keyY, 10)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(helium314.keyboard.latin.settings.Settings.getValues().mColors.get(helium314.keyboard.latin.common.ColorType.MAIN_BACKGROUND))
        view.draw(canvas)
        val p = panel!!
        p.measure(0, 0)
        p.layout(0, 0, p.measuredWidth, p.measuredHeight)
        canvas.save()
        canvas.translate(p.bounds[0].toFloat(), p.bounds[1].toFloat())
        p.draw(canvas)
        canvas.restore()
        val finger = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 5f }
        canvas.drawCircle(toX.toFloat(), keyY.toFloat(), 26f, finger)
        event(MotionEvent.ACTION_UP, toX, keyY)

        val dir = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"), "kudkeys-period").apply { mkdirs() }
        File(dir, "panel.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val ref = File(System.getProperty("user.home"),
            ".t3/userdata/attachments/d73f4e9d-6f0d-4518-9f8e-277bbba5fb90-df9fb2d2-6e3c-41ab-8817-bc52349aba41.jpg")
        if (!ref.exists()) return
        val full = BitmapFactory.decodeFile(ref.path) ?: return
        val crop = Bitmap.createBitmap(full, 0, (full.height * 0.55f).toInt(), full.width, full.height - (full.height * 0.55f).toInt())
        // same width for both, side by side
        val scale = bitmap.width.toFloat() / crop.width
        val refH = (crop.height * scale).toInt()
        val h = maxOf(refH, bitmap.height)
        val out = Bitmap.createBitmap(bitmap.width * 2 + 20, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(crop, null, Rect(0, h - refH, bitmap.width, h), null)
            drawBitmap(bitmap, (bitmap.width + 20).toFloat(), (h - bitmap.height).toFloat(), null)
        }
        File(dir, "compare.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
