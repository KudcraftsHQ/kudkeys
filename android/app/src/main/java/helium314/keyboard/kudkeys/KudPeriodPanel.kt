// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.PopupKeysPanel
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.CoordinateUtils
import helium314.keyboard.latin.settings.Settings
import kotlin.math.roundToInt

/**
 * SwiftKey's "." panel: opens on touch-down of the bottom-row "." (no long-press wait) with `! , . ?` in tall
 * cells, placed so the "." cell sits right over the "." key and the finger. The finger is already inside the
 * panel, so selection is by absolute position: sideways picks the cell under the finger (clamped to the end
 * cells), release commits it, release without moving types ".", sliding off the bottom or far up cancels.
 *
 * It plugs into HeliBoard's [PopupKeysPanel] flow, so [helium314.keyboard.keyboard.PointerTracker] routes the
 * finger to it exactly like a long-press popup.
 */
@SuppressLint("ViewConstructor")
class KudPeriodPanel(context: Context) : View(context), PopupKeysPanel {
    private var controller: PopupKeysPanel.Controller = PopupKeysPanel.EMPTY_CONTROLLER
    private var listener: KeyboardActionListener? = null
    private val coordinates = CoordinateUtils.newInstance()

    // geometry, in keyboard-view pixels
    private var originX = 0
    private var originY = 0
    private var panelWidth = 0
    private var panelHeight = 0
    private var padding = 0f
    private var cellWidth = 0f
    private var cancelAbove = 0
    private var cancelBelow = 0
    private var parentCode = Constants.NOT_A_CODE
    private var activePointerId = -1

    var labels: List<String> = emptyList(); private set
    var codes: List<Int> = emptyList(); private set
    var defaultIndex = 0; private set
    /** The cell under the finger, -1 once cancelled. */
    var selectedIndex = -1; private set

    /** Where the panel sits in keyboard-view pixels (left, top, right, bottom), and a cell's centre x there. */
    val bounds get() = intArrayOf(originX, originY, originX + panelWidth, originY + panelHeight)
    fun cellCenterX(index: Int) = (originX + padding + cellWidth * (index + 0.5f)).roundToInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
    private val rect = RectF()

    /** Shows the panel for [key] (the bottom-row "."), placed over it inside [parentView] (the keyboard view). */
    fun show(parentView: View, controller: PopupKeysPanel.Controller, key: Key, keyboard: Keyboard,
             listener: KeyboardActionListener) {
        this.controller = controller
        this.listener = listener
        val specs = key.popupKeys!!
        labels = specs.map { it.mLabel ?: String(Character.toChars(it.mCode)) }
        codes = specs.map { it.mCode }
        parentCode = key.code
        defaultIndex = codes.indexOf(key.code).coerceAtLeast(0)
        selectedIndex = defaultIndex

        // measured from SwiftKey: cells ~0.91 key wide and the panel ~1.85 rows tall, its bottom at the key's
        val keyWidth = keyboard.mMostCommonKeyWidth.toFloat()
        val rowHeight = key.height + keyboard.mVerticalGap
        cellWidth = keyWidth * 0.91f
        padding = keyWidth * 0.06f
        panelWidth = (cellWidth * labels.size + 2 * padding).roundToInt()
        panelHeight = (rowHeight * 1.85f).roundToInt()
        val keyCenterX = key.x + key.width / 2f
        val wantedX = (keyCenterX - padding - cellWidth * (defaultIndex + 0.5f)).roundToInt()
        originX = wantedX.coerceIn(0, (parentView.width - panelWidth).coerceAtLeast(0))
        originY = key.y + key.height - panelHeight
        cancelBelow = panelHeight + (key.height * 0.4f).roundToInt()
        cancelAbove = -rowHeight

        val colors = Settings.getValues().mColors
        fill.color = colors.get(ColorType.MAIN_BACKGROUND)
        stroke.color = colors.get(ColorType.KEY_HINT_TEXT)
        stroke.strokeWidth = resources.displayMetrics.density * 1.2f
        text.color = colors.get(ColorType.POPUP_KEY_TEXT)
        text.textSize = key.height * 0.6f

        parentView.getLocationInWindow(coordinates)
        x = (originX + CoordinateUtils.x(coordinates)).toFloat()
        y = (originY + CoordinateUtils.y(coordinates)).toFloat()
        invalidate()
        controller.onShowPopupKeysPanel(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(panelWidth, panelHeight)

    override fun onDraw(canvas: Canvas) {
        val s = stroke.strokeWidth
        val radius = resources.displayMetrics.density * 8f
        rect.set(s / 2, s / 2, panelWidth - s / 2, panelHeight - s / 2)
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, stroke)
        val baseline = panelHeight * 0.38f
        for (i in labels.indices) {
            val left = padding + cellWidth * i
            if (i == selectedIndex) {
                val insetX = cellWidth * 0.1f
                val insetY = panelHeight * 0.035f
                rect.set(left + insetX, insetY, left + cellWidth - insetX, panelHeight - insetY)
                canvas.drawRoundRect(rect, radius, radius, stroke)
            }
            canvas.drawText(labels[i], left + cellWidth / 2, baseline + text.textSize * 0.2f, text)
        }
    }

    private fun cellAt(x: Int, y: Int): Int {
        if (y > cancelBelow || y < cancelAbove) return -1
        return ((x - padding) / cellWidth).toInt().coerceIn(0, labels.lastIndex)
    }

    private fun select(index: Int) {
        if (index == selectedIndex) return
        selectedIndex = index
        invalidate()
    }

    override fun onDownEvent(x: Int, y: Int, pointerId: Int, eventTime: Long) {
        activePointerId = pointerId
        select(cellAt(x, y).takeIf { it >= 0 } ?: defaultIndex)
    }

    override fun onMoveEvent(x: Int, y: Int, pointerId: Int, eventTime: Long) {
        if (pointerId != activePointerId || selectedIndex < 0) return
        val index = cellAt(x, y)
        select(index)
        if (index < 0) {
            listener?.onReleaseKey(parentCode, false)
            controller.onCancelPopupKeysPanel()
        }
    }

    override fun onUpEvent(x: Int, y: Int, pointerId: Int, eventTime: Long) {
        if (pointerId != activePointerId || selectedIndex < 0) return
        val index = cellAt(x, y)
        selectedIndex = -1
        val l = listener ?: return
        if (index >= 0) {
            val code = codes[index]
            if (code != KeyCode.NOT_SPECIFIED)
                l.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        }
        l.onReleaseKey(parentCode, false)
    }

    override fun translateX(x: Int) = x - originX
    override fun translateY(y: Int) = y - originY

    override fun dismissPopupKeysPanel() {
        if (isShowingInParent) controller.onDismissPopupKeysPanel()
    }

    override fun getContainerView(): View = this

    // HeliBoard's generic entry points: this panel is only shown through show()
    override fun showPopupKeysPanel(parentView: View, controller: PopupKeysPanel.Controller, pointX: Int, pointY: Int,
                                    listener: KeyboardActionListener) = Unit
    override fun showPopupKeysPanel(parentView: View, controller: PopupKeysPanel.Controller, pointX: Int, pointY: Int,
                                    onReleaseKey: (Key) -> Unit) = Unit

    companion object {
        /** The order SwiftKey shows; "." (the key itself) is the default. */
        val LABELS = listOf("!", ",", ".", "?")

        /** True for the alphabet bottom row's "." when its popup is exactly SwiftKey's `! , . ?`. */
        @JvmStatic
        fun isInstantKey(key: Key?, keyboard: Keyboard?): Boolean {
            if (key == null || keyboard == null || key.code != '.'.code) return false
            if (!keyboard.mId.element.isAlphabet || key.backgroundType != Key.BACKGROUND_TYPE_FUNCTIONAL) return false
            val popups = key.popupKeys ?: return false
            return popups.map { it.mLabel } == LABELS
        }
    }
}
