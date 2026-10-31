/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin.suggestions

import helium314.keyboard.kudkeys.KudSettings
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.kudkeys.EditorUi
import helium314.keyboard.kudkeys.KudSpinnerDrawable
import helium314.keyboard.kudkeys.QuickVoiceController
import helium314.keyboard.kudkeys.TextAction
import helium314.keyboard.kudkeys.TextActionsController
import helium314.keyboard.kudkeys.TextBarState
import helium314.keyboard.kudkeys.VoiceEditorController
import helium314.keyboard.kudkeys.VoiceBarState
import helium314.keyboard.kudkeys.VoiceText
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.removeFirst
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import androidx.core.view.isGone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("InflateParams")
class SuggestionStripView(context: Context, attrs: AttributeSet?, defStyle: Int) :
    RelativeLayout(context, attrs, defStyle), View.OnClickListener, OnLongClickListener, OnSharedPreferenceChangeListener {

    /** Construct a [SuggestionStripView] for showing suggestions to be picked by the user. */
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.suggestionStripViewStyle)

    interface Listener {
        fun pickSuggestionManually(word: SuggestedWordInfo?)
        fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean)
        fun removeSuggestion(word: String?)
        fun removeExternalSuggestions()
        fun onSwipeDownOnToolbar()
    }

    private val moreSuggestionsContainer: View
    private val wordViews = ArrayList<TextView>()
    private val debugInfoViews = ArrayList<TextView>()
    private val dividerViews = ArrayList<View>()

    init {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.suggestions_strip, this)
        moreSuggestionsContainer = inflater.inflate(R.layout.more_suggestions, null)

        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)
        repeat(SuggestedWords.MAX_SUGGESTIONS) {
            val word = TextView(context, null, R.attr.suggestionWordStyle)
            word.contentDescription = resources.getString(R.string.spoken_empty_suggestion)
            word.setOnClickListener(this)
            word.setOnLongClickListener(this)
            colors.setBackground(word, ColorType.STRIP_BACKGROUND)
            wordViews.add(word)
            val divider = inflater.inflate(R.layout.suggestion_divider, null)
            dividerViews.add(divider)
            val info = TextView(context, null, R.attr.suggestionWordStyle)
            info.setTextColor(colors.get(ColorType.KEY_TEXT))
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, DEBUG_INFO_TEXT_SIZE_IN_DIP)
            debugInfoViews.add(info)
        }

        DEBUG_SUGGESTIONS = context.prefs().getBoolean(DebugSettings.PREF_SHOW_SUGGESTION_INFOS, Defaults.PREF_SHOW_SUGGESTION_INFOS)
    }

    // kudkeys: the bar is Fix | middle | Instruct Mic. The icons never move; the middle shows suggestions, or the
    // Private pill, the quick-voice row or a short message on top of them.
    private val suggestionsStrip: ViewGroup = findViewById(R.id.suggestions_strip)
    private val fixKey: ImageButton = findViewById(R.id.kud_fix_key)
    private val instructKey: ImageButton = findViewById(R.id.kud_instruct_key)
    private val micKey: ImageButton = findViewById(R.id.kud_mic_key)
    private val privatePill: TextView = findViewById(R.id.kud_private_pill)
    private val privateTag: TextView = findViewById(R.id.kud_private_tag)
    private val voiceRow: View = findViewById(R.id.kud_voice_row)
    private val voiceDot: View = findViewById(R.id.kud_voice_dot)
    private val voiceTime: TextView = findViewById(R.id.kud_voice_time)
    private val voiceText: TextView = findViewById(R.id.kud_voice_text)
    private val voiceHint: TextView = findViewById(R.id.kud_voice_hint)
    private val barMessage: TextView = findViewById(R.id.kud_bar_message)
    private var direction = 1 // 1 if LTR, -1 if RTL
    private var isPrivateField = false
    private var voiceBarState: VoiceBarState = VoiceBarState.Idle
    private var quickVoice: QuickVoiceController? = null
    private var textActions: TextActionsController? = null
    private var voiceEditor: VoiceEditorController? = null
    private var textBarState: TextBarState = TextBarState.Idle
    private var editorUi = EditorUi()
    private var micIcon = R.drawable.sym_keyboard_voice_lxx
    /** The small dot on Mic while a voice draft exists. */
    private val micDot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFF5B83D.toInt()) }

    init {
        val colors = Settings.getValues().mColors
        for (key in listOf(fixKey, instructKey, micKey)) {
            key.isActivated = true // toolbar key colours are an "activated" state list
            colors.setColor(key, ColorType.TOOL_BAR_KEY)
            colors.setBackground(key, ColorType.STRIP_BACKGROUND)
        }
        val text = colors.get(ColorType.KEY_TEXT)
        val muted = (text and 0x00FFFFFF) or (0x99 shl 24)
        voiceText.setTextColor(text)
        barMessage.setTextColor(text)
        voiceTime.setTextColor(muted)
        voiceHint.setTextColor(muted)
        fixKey.setOnClickListener {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
            textActions?.onFix()
        }
        instructKey.setOnTouchListener { v, event -> onInstructTouch(v, event) }
        micKey.setOnTouchListener { v, event -> onMicTouch(v, event) }
        barMessage.setOnClickListener {
            if (textBarState is TextBarState.Message && voiceBarState !is VoiceBarState.Message) textActions?.onMessageTapped()
            else quickVoice?.onMessageTapped()
        }
        micKey.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> placeMicDot(v) }
    }

    private lateinit var listener: Listener
    private var suggestedWords = SuggestedWords.getEmptyInstance()
    private var startIndexOfMoreSuggestions = 0
    private var isExternalSuggestionVisible = false // Required to disable the more suggestions if other suggestions are visible
    private val layoutHelper = SuggestionStripLayoutHelper(context, attrs, defStyle, wordViews, dividerViews, debugInfoViews)
    private val moreSuggestionsView = moreSuggestionsContainer.findViewById<MoreSuggestionsView>(R.id.more_suggestions_view).apply {
        val slidingListener = object : SimpleOnGestureListener() {
            override fun onScroll(down: MotionEvent?, me: MotionEvent, deltaX: Float, deltaY: Float): Boolean {
                if (down == null) return false
                val dy = me.y - down.y
                val dx = me.x - down.x

                if (Settings.getValues().mToolbarSwipeDownToHide && dy > 50.dpToPx(resources) && abs(dy) > abs(dx)) {
                    listener.onSwipeDownOnToolbar()
                    return true
                }

                return if (!isExternalSuggestionVisible && voiceBarState == VoiceBarState.Idle && deltaY > 0 && dy < (-10).dpToPx(resources)) showMoreSuggestions()
                else false
            }
        }
        gestureDetector = GestureDetector(context, slidingListener)
    }

    // public stuff

    val isShowingMoreSuggestionPanel get() = moreSuggestionsView.isShowingInParent

    /** A connection back to the input method. */
    fun setListener(newListener: Listener, inputView: View) {
        listener = newListener
        moreSuggestionsView.listener = newListener
        moreSuggestionsView.mainKeyboardView = inputView.findViewById(R.id.keyboard_view)
    }

    fun setRtl(isRtlLanguage: Boolean) {
        val newLayoutDirection: Int
        if (!Settings.getValues().mVarToolbarDirection)
            newLayoutDirection = LAYOUT_DIRECTION_LOCALE
        else {
            newLayoutDirection = if (isRtlLanguage) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
            direction = if (isRtlLanguage) -1 else 1
        }
        layoutDirection = newLayoutDirection
        suggestionsStrip.layoutDirection = newLayoutDirection
    }

    fun setSuggestions(suggestions: SuggestedWords, isRtlLanguage: Boolean) {
        clear()
        setRtl(isRtlLanguage)
        suggestedWords = suggestions
        startIndexOfMoreSuggestions = layoutHelper.layoutAndReturnStartIndexOfMoreSuggestions(
            context, suggestedWords, suggestionsStrip, this
        )
        isExternalSuggestionVisible = false
        updateKeys()
    }

    fun setExternalSuggestionView(view: View?, addCloseButton: Boolean) {
        clear()
        isExternalSuggestionVisible = true

        if (addCloseButton) {
            val wrapper = LinearLayout(context)
            suggestionsStrip.doOnNextLayout {
                wrapper.layoutParams = LinearLayout.LayoutParams(suggestionsStrip.width - 30.dpToPx(resources), LayoutParams.MATCH_PARENT)
            }
            wrapper.addView(view)
            suggestionsStrip.addView(wrapper)

            val closeButton = createToolbarKey(context, ToolbarKey.CLOSE_HISTORY)
            closeButton.layoutParams = LinearLayout.LayoutParams(30.dpToPx(resources), LayoutParams.MATCH_PARENT)
            Settings.getValues().mColors.setColor(closeButton, ColorType.TOOL_BAR_KEY)
            Settings.getValues().mColors.setBackground(closeButton, ColorType.STRIP_BACKGROUND)
            closeButton.setOnClickListener {
                listener.removeExternalSuggestions()
            }
            suggestionsStrip.addView(closeButton)
        } else {
            suggestionsStrip.addView(view)
        }
        updateMiddle()
    }

    fun setMoreSuggestionsHeight(remainingHeight: Int) {
        layoutHelper.setMoreSuggestionsHeight(remainingHeight)
    }

    fun dismissMoreSuggestionsPanel() {
        moreSuggestionsView.dismissPopupKeysPanel()
    }

    // overrides: necessarily public, but not used from outside

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if (key == Settings.PREF_ALWAYS_INCOGNITO_MODE)
            GlobalScope.launch { delay(10); withContext(Dispatchers.Main) { updateKeys() } }
        if (key == KudSettings.PREF_VOICE_TYPING)
            updateMicVisibility(prefs.getBoolean(key, KudSettings.DEFAULT_VOICE_TYPING))
    }

    override fun onVisibilityChanged(view: View, visibility: Int) {
        super.onVisibilityChanged(view, visibility)
        // workaround for a bug with inline suggestions views that just keep showing up otherwise, https://github.com/HeliBorg/HeliBoard/pull/386
        if (view === this)
            suggestionsStrip.visibility = visibility
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissMoreSuggestionsPanel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Called by the framework when the size is known. Show the important notice if applicable.
        // This may be overridden by showing suggestions later, if applicable.
    }

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean {
        // Don't populate accessibility event with suggested words and voice key.
        return true
    }

    override fun onInterceptTouchEvent(motionEvent: MotionEvent): Boolean {
        // Detecting sliding up finger to show MoreSuggestionsView.
        return moreSuggestionsView.shouldInterceptTouchEvent(motionEvent)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(motionEvent: MotionEvent): Boolean {
        moreSuggestionsView.touchEvent(motionEvent)
        return true
    }

    override fun onClick(view: View) {
        val tag = view.tag
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)

        // tag for word views is set in SuggestionStripLayoutHelper (setupWordViewsTextAndColor, layoutPunctuationSuggestions)
        if (tag is Int) {
            if (tag >= suggestedWords.size()) {
                return
            }
            val wordInfo = suggestedWords.getInfo(tag)
            listener.pickSuggestionManually(wordInfo)
        }
    }

    override fun onLongClick(view: View): Boolean {
        AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
        return if (view is TextView && wordViews.contains(view)) {
            onLongClickSuggestion(view)
        } else {
            showMoreSuggestions()
        }
    }

    // actually private stuff

    @SuppressLint("ClickableViewAccessibility") // no need for View#performClick, we only return false mostly anyway
    private fun onLongClickSuggestion(wordView: TextView): Boolean {
        var showIcon = true
        if (wordView.tag is Int) {
            val index = wordView.tag as Int
            val type = suggestedWords.getInfo(index).mSourceDict
            if (type == Dictionary.DICTIONARY_USER_TYPED || type == Dictionary.DICTIONARY_HARDCODED)
                showIcon = false
        }
        if (showIcon) {
            val icon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_BIN, context)!!
            Settings.getValues().mColors.setColor(icon, ColorType.REMOVE_SUGGESTION_ICON)
            val w = icon.intrinsicWidth
            val h = icon.intrinsicHeight
            wordView.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            wordView.ellipsize = TextUtils.TruncateAt.END
            val downOk = AtomicBoolean(false)
            wordView.setOnTouchListener { _, motionEvent ->
                if (motionEvent.action == MotionEvent.ACTION_UP && downOk.get()) {
                    val x = motionEvent.x
                    val y = motionEvent.y
                    if (0 < x && x < w && 0 < y && y < h) {
                        removeSuggestion(wordView)
                        wordView.cancelLongPress()
                        wordView.isPressed = false
                        return@setOnTouchListener true
                    }
                } else if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                    val x = motionEvent.x
                    val y = motionEvent.y
                    if (0 < x && x < w && 0 < y && y < h) {
                        downOk.set(true)
                    }
                }
                false
            }
        }
        if (DebugFlags.DEBUG_ENABLED && (isShowingMoreSuggestionPanel || !showMoreSuggestions())) {
            showSourceDict(wordView)
            return true
        }
        return showMoreSuggestions()
    }

    private fun showMoreSuggestions(): Boolean {
        if (suggestedWords.size() <= startIndexOfMoreSuggestions) {
            return false
        }
        if (!moreSuggestionsView.show(
                suggestedWords, startIndexOfMoreSuggestions, moreSuggestionsContainer, layoutHelper, this
        ))
            return false
        for (i in 0..<startIndexOfMoreSuggestions) {
            wordViews[i].isPressed = false
        }
        return true
    }

    private fun showSourceDict(wordView: TextView) {
        val word = wordView.text.toString()
        val index = wordView.tag as? Int ?: return
        if (index >= suggestedWords.size()) return
        val info = suggestedWords.getInfo(index)
        if (info.word != word) return

        val text = info.mSourceDict.mDictType + ":" + info.mSourceDict.mLocale
        if (isShowingMoreSuggestionPanel) {
            moreSuggestionsView.dismissPopupKeysPanel()
        }
        KeyboardSwitcher.getInstance().showToast(text, true)
    }

    private fun removeSuggestion(wordView: TextView) {
        val word = wordView.text.toString()
        listener.removeSuggestion(word)
        moreSuggestionsView.dismissPopupKeysPanel()
        // show suggestions, but without the removed word
        val suggestedWordInfos = ArrayList<SuggestedWordInfo>()
        for (i in 0..<suggestedWords.size()) {
            val info = suggestedWords.getInfo(i)
            if (info.word != word) suggestedWordInfos.add(info)
        }
        suggestedWords.mRawSuggestions?.removeFirst { it.word == word }

        val newSuggestedWords = SuggestedWords(
            suggestedWordInfos, suggestedWords.mRawSuggestions, suggestedWords.typedWordInfo, suggestedWords.mTypedWordValid,
            suggestedWords.mWillAutoCorrect, suggestedWords.mIsObsoleteSuggestions, suggestedWords.mInputStyle, suggestedWords.mSequenceNumber
        )
        setSuggestions(newSuggestedWords, direction != 1)
        updateMiddle()
    }

    private fun clear() {
        suggestionsStrip.removeAllViews()
        if (DEBUG_SUGGESTIONS) removeAllDebugInfoViews()
        dismissMoreSuggestionsPanel()
        for (word in wordViews) {
            word.setOnTouchListener(null)
        }
    }

    private fun removeAllDebugInfoViews() {
        for (debugInfoView in debugInfoViews) {
            val parent = debugInfoView.parent
            if (parent is ViewGroup) {
                parent.removeView(debugInfoView)
            }
        }
    }

    private fun updateKeys() {
        isPrivateField = Settings.getValues().mIncognitoModeEnabled
        updateMicVisibility(Settings.getValues().mKudVoiceTyping)
        isExternalSuggestionVisible = false
        updateMiddle()
    }

    // ---- kudkeys: quick voice and the Private pill ----

    /** Rich input → Voice typing off hides the Mic; its slot stays, so Instruct never moves. */
    private fun updateMicVisibility(voiceTyping: Boolean) {
        micKey.visibility = if (voiceTyping) VISIBLE else INVISIBLE
    }

    /** Whether the Mic is on the bar (for tests). */
    val isMicShown get() = micKey.visibility == VISIBLE

    fun setQuickVoice(controller: QuickVoiceController?) {
        quickVoice = controller
    }

    fun setTextTools(actions: TextActionsController?, editor: VoiceEditorController?) {
        textActions = actions
        voiceEditor = editor
        actions?.let { renderTextAction(it.state) }
        editor?.let { renderEditor(it.ui) }
    }

    /** ✦ Fix / Instruct: a spinner on the icon, the spoken instruction, or a short message ("Fixed · Undo"). */
    fun renderTextAction(state: TextBarState) {
        textBarState = state
        val busy = (state as? TextBarState.Busy)?.action
        setBusyIcon(fixKey, busy == TextAction.Fix, R.drawable.kud_ic_fix)
        setBusyIcon(instructKey, busy == TextAction.Instruct, R.drawable.kud_ic_instruct)
        when (state) {
            is TextBarState.Listening -> {
                voiceDot.alpha = 1f
                voiceTime.isVisible = true
                voiceTime.text = VoiceText.elapsed(state.elapsedMs)
                voiceText.text = state.text.ifEmpty { resources.getString(R.string.kud_instruct_listening) }
                voiceHint.isVisible = false
            }
            is TextBarState.Message -> if (voiceBarState !is VoiceBarState.Message) showTextMessage(state)
            else -> Unit
        }
        updateMiddle()
    }

    private fun showTextMessage(state: TextBarState.Message) {
        val text = state.text
        val undoAt = text.lastIndexOf("Undo")
        barMessage.text = if (state.undo && undoAt >= 0) android.text.SpannableString(text).apply {
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), undoAt, text.length, 0)
            setSpan(android.text.style.UnderlineSpan(), undoAt, text.length, 0)
        } else text
        barMessage.isClickable = state.undo
    }

    private fun setBusyIcon(key: ImageButton, busy: Boolean, icon: Int) {
        val spinning = key.drawable is KudSpinnerDrawable
        if (busy == spinning) return
        if (busy) {
            val d = KudSpinnerDrawable(18.dpToPx(resources), 2f * resources.displayMetrics.density,
                Settings.getValues().mColors.get(ColorType.KEY_TEXT))
            key.setImageDrawable(d)
            d.start()
        } else {
            (key.drawable as? KudSpinnerDrawable)?.stop()
            key.setImageResource(icon)
        }
        Settings.getValues().mColors.setColor(key, ColorType.TOOL_BAR_KEY)
    }

    /**
     * Full voice mode: while the editor is open the bar keeps only Mic (stop while recording, else back to keys).
     * Closed with a draft: a dot on Mic.
     */
    fun renderEditor(ui: EditorUi) {
        editorUi = ui
        val open = ui.open
        fixKey.visibility = if (open) INVISIBLE else VISIBLE
        instructKey.visibility = if (open) INVISIBLE else VISIBLE
        micDot.alpha = if (!open && ui.hasDraft) 255 else 0
        updateMicIcon()
        updateMiddle()
    }

    private fun placeMicDot(v: View) {
        val size = 7.dpToPx(resources)
        val cx = v.width / 2 + 9.dpToPx(resources)
        val cy = v.height / 2 - 8.dpToPx(resources)
        micDot.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        v.overlay.remove(micDot)
        v.overlay.add(micDot)
    }

    /** Whether the draft dot is on Mic (for tests). */
    val isMicDotShown get() = micDot.alpha > 0

    private fun updateMicIcon() {
        val locked = voiceBarState is VoiceBarState.Recording && (voiceBarState as VoiceBarState.Recording).locked
        val (icon, desc) = when {
            // the editor has its own big stop button; here Mic only goes back to the keys (a recording stops and stays)
            editorUi.open -> R.drawable.kud_ic_keyboard to R.string.kud_back_to_keys
            locked -> R.drawable.kud_ic_stop to R.string.kud_stop
            else -> R.drawable.sym_keyboard_voice_lxx to R.string.kud_mic
        }
        if (icon == micIcon) return
        micIcon = icon
        micKey.setImageResource(icon)
        if (icon != R.drawable.kud_ic_stop) Settings.getValues().mColors.setColor(micKey, ColorType.TOOL_BAR_KEY)
        micKey.contentDescription = resources.getString(desc)
    }

    /** Re-reads whether the current field is private (call after settings were reloaded for a new field). */
    fun updatePrivacy() = updateKeys()

    /** The amber Private marker is on screen: the centred pill (empty strip) or the compact tag (suggestions). */
    val isPrivatePillShown get() = privatePill.isVisible || privateTag.isVisible

    fun renderVoice(state: VoiceBarState) {
        voiceBarState = state
        when (state) {
            is VoiceBarState.Recording -> {
                voiceDot.alpha = 1f
                voiceTime.isVisible = true
                voiceTime.text = VoiceText.elapsed(state.elapsedMs)
                voiceText.text = state.text
                voiceHint.isVisible = !state.locked
            }
            is VoiceBarState.Finishing -> {
                voiceDot.alpha = 0.35f
                voiceTime.isVisible = false
                voiceText.text = state.text.ifEmpty { "…" }
                voiceHint.isVisible = false
            }
            is VoiceBarState.Message -> {
                barMessage.text = state.text
                barMessage.isClickable = state.opensSettings
            }
            VoiceBarState.Idle -> Unit
        }
        updateMicIcon()
        updateMiddle()
    }

    /**
     * What the middle of the bar shows: voice row, message, or suggestions. In a private field the amber marker is
     * always there: the centred pill while the strip is empty, a compact tag before the suggestions while typing.
     */
    private fun updateMiddle() {
        val editor = editorUi.open
        val textListening = textBarState is TextBarState.Listening
        val voice = !editor && (voiceBarState is VoiceBarState.Recording || voiceBarState is VoiceBarState.Finishing || textListening)
        val voiceMessage = voiceBarState is VoiceBarState.Message
        val textMessage = textBarState is TextBarState.Message
        if (voiceMessage) {
            val m = voiceBarState as VoiceBarState.Message
            barMessage.text = m.text
            barMessage.isClickable = m.opensSettings
        } else if (textMessage) showTextMessage(textBarState as TextBarState.Message)
        val message = !editor && !voice && (voiceMessage || textMessage)
        val stripEmpty = !isExternalSuggestionVisible && suggestedWords.isEmpty
        // in the editor the middle is empty but for the Private pill: suggestions belong to typing
        val pill = isPrivateField && !voice && !message && (stripEmpty || editor)
        val hideStrip = voice || message || pill || editor
        voiceRow.isVisible = voice
        barMessage.isVisible = message
        privatePill.isVisible = pill
        // The tag keeps its space (INVISIBLE) under the pill, so the strip width doesn't jump when typing starts.
        privateTag.visibility = when {
            !isPrivateField -> GONE
            hideStrip -> INVISIBLE
            else -> VISIBLE
        }
        suggestionsStrip.visibility = if (hideStrip) INVISIBLE else VISIBLE
        if (voice || message || editor) dismissMoreSuggestionsPanel()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onInstructTouch(view: View, event: MotionEvent): Boolean {
        val controller = textActions ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.parent?.requestDisallowInterceptTouchEvent(true)
                view.isPressed = true
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
                controller.onInstructDown()
            }
            MotionEvent.ACTION_UP -> { view.isPressed = false; controller.onInstructUp() }
            MotionEvent.ACTION_CANCEL -> { view.isPressed = false; controller.onInstructCancelled() }
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onMicTouch(view: View, event: MotionEvent): Boolean {
        // Full voice mode: Mic is a plain button there (stop, or back to the keys); no quick voice on top of it.
        val editor = voiceEditor
        if (editor != null && editorUi.open) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.isPressed = true
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    editor.close()
                }
                MotionEvent.ACTION_CANCEL -> view.isPressed = false
            }
            return true
        }
        val controller = quickVoice ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.parent?.requestDisallowInterceptTouchEvent(true)
                view.isPressed = true
                controller.onMicDown(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_MOVE -> controller.onMicMove(event.rawX, event.rawY)
            MotionEvent.ACTION_UP -> {
                view.isPressed = false
                controller.onMicUp()
            }
            MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                controller.onMicTouchCancelled()
            }
        }
        return true
    }

    companion object {
        @JvmField
        var DEBUG_SUGGESTIONS = false
        private const val DEBUG_INFO_TEXT_SIZE_IN_DIP = 6.5f
        private val TAG = SuggestionStripView::class.java.simpleName
    }
}
