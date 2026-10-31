// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.settings.Settings
import kotlin.math.roundToInt

/**
 * The full voice editor (spec item 1). It sits over the key area in `main_keyboard_frame.xml` and takes exactly the
 * keyboard's height (so the user's resize applies); the top bar stays above it. Draws [EditorUi], sends taps to
 * [controller]; keeps no state of its own. Colours come from the current theme, so it follows the incognito look.
 */
class KudVoiceEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var controller: VoiceEditorController? = null

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Int) = dp(v.toFloat()).roundToInt()

    private val statusDot = View(context)
    private val statusTime = TextView(context)
    private val statusLabel = TextView(context)
    private val tidyToggle = ImageButton(context)
    private val closeButton = ImageButton(context)

    private val card = LinearLayout(context)
    private val cardLabel = TextView(context)
    private val cardChips = LinearLayout(context)
    private val cardClose = ImageButton(context)

    private val scroll = ScrollView(context)
    private val body = TextView(context)

    private val discardButton = ImageButton(context)
    private val undoButton = ImageButton(context)
    private val rawButton = TextView(context)
    private val instructButton = LinearLayout(context)
    private val instructIcon = ImageView(context)
    private val instructLabel = TextView(context)
    private val insertButton = TextView(context)
    private val micButton = ImageButton(context)

    private var colors: Colors? = null
    private var textColor = Color.WHITE
    private var mutedColor = Color.GRAY
    private var surfaceColor = Color.DKGRAY
    private var backgroundColor = Color.BLACK
    private var incognito = false
    private var watermark: Drawable? = null
    private var ui = EditorUi()
    private var lastChipsFor: String? = null

    init {
        isClickable = true // the keys underneath never get a touch
        setWillNotDraw(false)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(12), dpi(4), dpi(8), dpi(8))
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // header: status · tidy toggle · close
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(RED) }
        header.addView(statusDot, LinearLayout.LayoutParams(dpi(8), dpi(8)).apply { marginEnd = dpi(8) })
        statusTime.apply { textSize = 13f; fontFeatureSettings = "tnum"; setTypeface(typeface, Typeface.BOLD) }
        header.addView(statusTime, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(8) })
        statusLabel.apply { textSize = 13f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        header.addView(statusLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        iconButton(tidyToggle, R.drawable.kud_ic_tidy, "Tidy on stop")
        header.addView(tidyToggle, LinearLayout.LayoutParams(dpi(40), dpi(40)))
        iconButton(closeButton, R.drawable.kud_ic_close, "Close, keep draft")
        header.addView(closeButton, LinearLayout.LayoutParams(dpi(40), dpi(40)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(40)))

        // question card: one at a time, a single row
        card.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(10), 0, dpi(2), 0)
        }
        cardLabel.apply { textSize = 13f; isSingleLine = true; text = "Did you say" }
        card.addView(cardLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(6) })
        val chipsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        cardChips.apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chipsScroll.addView(cardChips, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(chipsScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        iconButton(cardClose, R.drawable.kud_ic_close, "Dismiss question")
        cardClose.setPadding(dpi(8), dpi(8), dpi(8), dpi(8))
        cardClose.scaleType = ImageView.ScaleType.FIT_CENTER
        card.addView(cardClose, LinearLayout.LayoutParams(dpi(34), dpi(34)))
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(42)).apply { bottomMargin = dpi(6); marginEnd = dpi(4) })

        // the text
        body.apply {
            textSize = 17f
            setLineSpacing(dp(3f), 1f)
            setPadding(0, dpi(2), dpi(4), dpi(6))
        }
        scroll.isVerticalScrollBarEnabled = false
        scroll.isFillViewport = true
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // actions: discard undo raw · · instruct insert mic
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        iconButton(discardButton, R.drawable.kud_ic_trash, "Discard draft")
        actions.addView(discardButton, LinearLayout.LayoutParams(dpi(40), dpi(40)))
        iconButton(undoButton, R.drawable.kud_ic_undo, "Undo")
        actions.addView(undoButton, LinearLayout.LayoutParams(dpi(40), dpi(40)))
        rawButton.apply {
            textSize = 13f; gravity = Gravity.CENTER; setPadding(dpi(10), 0, dpi(10), 0)
            setTypeface(typeface, Typeface.BOLD)
        }
        actions.addView(rawButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(30)).apply { marginStart = dpi(2) })
        actions.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        instructButton.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(10), 0, dpi(12), 0)
            contentDescription = "Hold and say an edit"
        }
        instructIcon.setImageResource(R.drawable.kud_ic_instruct)
        instructButton.addView(instructIcon, LinearLayout.LayoutParams(dpi(18), dpi(18)).apply { marginEnd = dpi(6) })
        instructLabel.apply { textSize = 13f; isSingleLine = true; text = "Hold to edit" }
        instructButton.addView(instructLabel)
        actions.addView(instructButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(36)).apply { marginEnd = dpi(8) })
        insertButton.apply {
            text = "Insert"; textSize = 14f; gravity = Gravity.CENTER; setPadding(dpi(16), 0, dpi(16), 0)
            setTypeface(typeface, Typeface.BOLD)
        }
        actions.addView(insertButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(36)).apply { marginEnd = dpi(8) })
        micButton.scaleType = ImageView.ScaleType.CENTER
        actions.addView(micButton, LinearLayout.LayoutParams(dpi(52), dpi(52)))
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(56)))

        tidyToggle.setOnClickListener { controller?.let { it.setTidy(!it.ui.tidyOn) } }
        closeButton.setOnClickListener { controller?.close() }
        cardClose.setOnClickListener { controller?.dismissQuestion() }
        discardButton.setOnClickListener { controller?.discard() }
        undoButton.setOnClickListener { controller?.undo() }
        rawButton.setOnClickListener { controller?.toggleRaw() }
        insertButton.setOnClickListener { controller?.insert() }
        micButton.setOnClickListener { controller?.onMicButton() }
        @SuppressLint("ClickableViewAccessibility")
        instructButton.setOnTouchListener { v, e ->
            val c = controller ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; v.parent?.requestDisallowInterceptTouchEvent(true); c.onInstructDown() }
                MotionEvent.ACTION_UP -> { v.isPressed = false; c.onInstructUp() }
                MotionEvent.ACTION_CANCEL -> { v.isPressed = false; c.onInstructCancelled() }
            }
            true
        }
        render(EditorUi())
    }

    /** The key area's height: the tallest other child of the keyboard wrapper (keys or emoji), never more. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val parent = parent as? ViewGroup
        var h = 0
        if (parent != null) for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            if (c !== this && c.visibility != GONE) h = maxOf(h, c.measuredHeight)
        }
        if (h == 0) h = dpi(213)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!incognito) return
        val w = watermark ?: ContextCompat.getDrawable(context, R.drawable.kud_incognito_watermark)?.mutate()?.also {
            it.alpha = 22
            watermark = it
        } ?: return
        val width = (getWidth() * 0.31f).toInt()
        val height = width * w.intrinsicHeight / maxOf(1, w.intrinsicWidth)
        val left = (getWidth() - width) / 2
        val top = (getHeight() * 0.5f).toInt() - height / 2
        w.setBounds(left, top, left + width, top + height)
        w.draw(canvas)
    }

    // ---- drawing --------------------------------------------------------------------------------------------

    fun render(next: EditorUi) {
        ui = next
        applyColors()
        visibility = if (ui.open) VISIBLE else GONE
        if (!ui.open) return

        // header
        val recording = ui.mode == EditorMode.Recording || ui.mode == EditorMode.InstructListening
        statusDot.isVisible = recording
        statusTime.isVisible = ui.mode == EditorMode.Recording
        statusTime.text = VoiceText.elapsed(ui.elapsedMs)
        statusLabel.setTextColor(if (ui.message != null) AMBER else mutedColor)
        statusLabel.text = ui.message ?: when (ui.mode) {
            EditorMode.Recording -> "Listening"
            EditorMode.Finishing -> "Finishing…"
            EditorMode.Tidying -> "Tidying…"
            EditorMode.InstructListening -> if (ui.instruction.isBlank()) "Say an edit…" else "“${ui.instruction}”"
            EditorMode.InstructFinishing, EditorMode.Instructing -> "Editing…"
            EditorMode.Idle -> idleLabel()
        }
        tidyToggle.setImageResource(if (ui.tidyOn) R.drawable.kud_ic_tidy else R.drawable.kud_ic_tidy_off)
        tidyToggle.imageTintList = ColorStateList.valueOf(if (ui.tidyOn) textColor else mutedColor)
        tidyToggle.background = toggleBackground(ui.tidyOn)
        tidyToggle.contentDescription = if (ui.tidyOn) "Tidy on stop: on" else "Tidy on stop: off"

        // question card
        val q = ui.question
        card.isVisible = q != null
        if (q != null && lastChipsFor != q.id + q.options) {
            lastChipsFor = q.id + q.options
            cardChips.removeAllViews()
            for (o in q.options) cardChips.addView(chip(o, primary = true) { controller?.answer(o) }, chipParams())
            cardChips.addView(chip("Keep as is", primary = false) { controller?.answer(null) }, chipParams())
        }
        cardLabel.text = if (q != null && ui.questionsLeft > 1) "Did you say (${ui.questionsLeft})" else "Did you say"

        // text
        val sb = SpannableStringBuilder(ui.text)
        if (q != null && q.end <= sb.length) {
            sb.setSpan(BackgroundColorSpan(AMBER_WASH), q.start, q.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(UnderlineSpan(), q.start, q.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (ui.partial.isNotEmpty()) {
            val s = sb.length
            sb.append(ui.partial)
            sb.setSpan(ForegroundColorSpan(mutedColor), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (sb.isEmpty()) {
            body.setTextColor(mutedColor)
            body.text = if (ui.mode == EditorMode.Recording) "Start talking…" else "Tap the mic to dictate"
        } else {
            body.setTextColor(textColor)
            body.text = sb
        }
        if (ui.mode == EditorMode.Recording) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        // actions
        val idle = ui.mode == EditorMode.Idle
        enable(discardButton, ui.hasDraft || ui.mode == EditorMode.Recording)
        enable(undoButton, idle && ui.canUndo)
        rawButton.isVisible = ui.canShowRaw
        rawButton.text = if (ui.showingRaw) "Tidied" else "Raw"
        rawButton.background = pill(if (ui.showingRaw) surfaceColor else Color.TRANSPARENT, stroke = mutedColor.withAlpha(0x66))
        rawButton.setTextColor(if (ui.showingRaw) textColor else mutedColor)
        enable(rawButton, idle)

        val instructing = ui.mode == EditorMode.InstructListening
        instructButton.background = pill(if (instructing) RED else surfaceColor)
        instructIcon.imageTintList = ColorStateList.valueOf(if (instructing) Color.WHITE else textColor)
        instructLabel.setTextColor(if (instructing) Color.WHITE else textColor)
        instructLabel.text = if (instructing) "Listening…" else "Hold to edit"
        enable(instructButton, idle && ui.text.isNotBlank() || instructing)

        insertButton.background = pill(textColor)
        insertButton.setTextColor(backgroundColor)
        enable(insertButton, ui.canInsert)

        val stop = ui.mode == EditorMode.Recording
        micButton.background = circle(if (stop) RED else surfaceColor)
        micButton.setImageResource(if (stop) R.drawable.kud_ic_stop_square else R.drawable.kud_ic_mic)
        micButton.imageTintList = ColorStateList.valueOf(if (stop) Color.WHITE else textColor)
        micButton.contentDescription = if (stop) "Stop" else "Continue dictating"
        enable(micButton, stop || idle)
    }

    private fun idleLabel(): String {
        val words = ui.text.split(Regex("\\s+")).count { it.isNotEmpty() }
        if (words == 0) return "Voice draft"
        val kind = if (ui.showingRaw) "Raw" else if (ui.canShowRaw) "Tidied" else "Draft"
        return "$kind · $words ${if (words == 1) "word" else "words"}"
    }

    private fun applyColors() {
        val c = Settings.getValues().mColors
        val inc = Settings.getValues().mKudIncognitoLook
        if (c === colors && inc == incognito) return
        colors = c
        incognito = inc
        c.setBackground(this, ColorType.MAIN_BACKGROUND)
        backgroundColor = c.get(ColorType.MAIN_BACKGROUND).let { if (Color.alpha(it) == 0) Color.BLACK else it }
        textColor = c.get(ColorType.KEY_TEXT)
        mutedColor = c.get(ColorType.KEY_HINT_TEXT)
        surfaceColor = c.get(ColorType.KEY_BACKGROUND).let { if (Color.alpha(it) < 0x40) blend(backgroundColor, textColor, 0.12f) else it }
        statusTime.setTextColor(textColor)
        cardLabel.setTextColor(mutedColor)
        card.background = rounded(surfaceColor, dp(12f))
        for (b in listOf(closeButton, discardButton, undoButton, cardClose)) b.imageTintList = ColorStateList.valueOf(textColor)
        for (b in listOf(closeButton, discardButton, undoButton, cardClose, tidyToggle)) if (b !== tidyToggle) b.background = pressable()
        lastChipsFor = null
        invalidate()
    }

    // ---- small builders -------------------------------------------------------------------------------------

    private fun iconButton(b: ImageButton, icon: Int, desc: String) {
        b.setImageResource(icon)
        b.scaleType = ImageView.ScaleType.CENTER
        b.contentDescription = desc
        b.background = null
    }

    private fun chip(label: String, primary: Boolean, onTap: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dpi(12), 0, dpi(12), 0)
        if (primary) {
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            background = pill(blend(surfaceColor, textColor, 0.14f))
        } else {
            setTextColor(mutedColor)
            background = pill(Color.TRANSPARENT, stroke = mutedColor.withAlpha(0x66))
        }
        setOnClickListener { onTap() }
    }

    private fun chipParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(30)).apply { marginEnd = dpi(6) }

    private fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.35f
    }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
        if (stroke != null) setStroke(dpi(1), stroke)
    }

    private fun pill(color: Int, stroke: Int? = null): Drawable = pressed(rounded(color, dp(100f), stroke), rounded(blend(color, textColor, 0.18f), dp(100f), stroke))

    private fun circle(color: Int): Drawable {
        fun oval(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
        return pressed(oval(color), oval(blend(color, textColor, 0.18f)))
    }

    private fun toggleBackground(on: Boolean): Drawable {
        fun oval(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
        val base = if (on) surfaceColor else Color.TRANSPARENT
        return android.graphics.drawable.InsetDrawable(pressed(oval(base), oval(blend(surfaceColor, textColor, 0.15f))), dpi(4))
    }

    private fun pressable(): Drawable {
        val p = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(textColor.withAlpha(0x22)) }
        return StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_pressed), p) }
    }

    private fun pressed(normal: Drawable, down: Drawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), down)
        addState(intArrayOf(), normal)
    }

    private fun Int.withAlpha(a: Int) = (this and 0x00FFFFFF) or (a shl 24)

    private fun blend(a: Int, b: Int, t: Float): Int {
        if (Color.alpha(a) == 0) return b.withAlpha((255 * t).toInt())
        fun ch(x: Int, y: Int) = (x + (y - x) * t).roundToInt()
        return Color.argb(255, ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    /** For tests: the views a screenshot test taps. */
    internal val insertView: View get() = insertButton
    internal val bodyText: CharSequence get() = body.text

    companion object {
        private val RED = Color.parseColor("#E5484D")
        private val AMBER = Color.parseColor("#F5B83D")
        private val AMBER_WASH = Color.parseColor("#4DF5B83D")
    }
}
