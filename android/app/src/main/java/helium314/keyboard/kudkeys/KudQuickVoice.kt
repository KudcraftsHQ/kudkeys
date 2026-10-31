// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.inputmethod.EditorInfo
import com.kudcrafts.kudkeys.voice.TextApi
import com.kudcrafts.kudkeys.voice.VoiceClient
import com.kudcrafts.kudkeys.voice.VoiceSettings
import com.kudcrafts.kudkeys.voice.hasMicPermission
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.dpToPx
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * Connects the voice tools to the IME, one of each per [LatinIME], drawing into whichever views are current: quick
 * voice (hold Mic), the full voice editor (tap Mic) and ✦ Fix / Instruct. Each has its own voice client, so their
 * sessions never see each other's state.
 */
class KudQuickVoice(private val ime: LatinIME) : QuickVoiceController.Host {
    private val settings = VoiceSettings(ime)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var strip: SuggestionStripView? = null
    private var editorView: KudVoiceEditorView? = null
    private val textApi = TextApi(settings)
    val controller = QuickVoiceController(VoiceClientEngine(VoiceClient.create(ime)), this, scope)

    val editor = VoiceEditorController(
        VoiceClientEngine(VoiceClient.create(ime)), textApi, PrefsDraftStore(ime),
        object : VoiceEditorController.Host {
            override fun hasVoiceToken() = settings.hasToken
            override fun hasMicPermission() = this@KudQuickVoice.hasMicPermission()
            override fun requestMicPermission() = MicPermissionActivity.start(ime)
            override fun isPrivateField() = this@KudQuickVoice.isPrivateField()
            override fun insert(text: String) = ime.onVoiceTextInput(text)
            override fun render(ui: EditorUi) {
                editorView?.render(ui)
                strip?.renderEditor(ui)
            }
            override fun haptic() = this@KudQuickVoice.haptic()
            override fun schedule(delayMs: Long, block: () -> Unit) = this@KudQuickVoice.schedule(delayMs, block)
        },
        scope,
    )

    val textActions = TextActionsController(
        VoiceClientEngine(VoiceClient.create(ime)), textApi,
        InputConnectionFieldEditor({ ime.currentInputConnection }, ime::kudExpectedSelection, ime::kudPrepareExternalEdit),
        object : TextActionsController.Host {
            override fun isSignedIn() = settings.isSignedIn
            override fun isPrivateField() = this@KudQuickVoice.isPrivateField()
            override fun hasMicPermission() = this@KudQuickVoice.hasMicPermission()
            override fun requestMicPermission() = MicPermissionActivity.start(ime)
            override fun haptic() = this@KudQuickVoice.haptic()
            override fun render(state: TextBarState) { strip?.renderTextAction(state) }
            override fun schedule(delayMs: Long, block: () -> Unit) = this@KudQuickVoice.schedule(delayMs, block)
        },
        scope,
    )

    /** No recording keeps the mic open with the screen off: quick voice, the editor's dictation, a held Instruct. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) onHidden()
        }
    }

    init {
        ime.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    fun attach(view: SuggestionStripView?) {
        strip = view
        view?.setQuickVoice(controller)
        view?.setTextTools(textActions, editor)
    }

    /** The editor view of the current input view (it is recreated with the keyboard, e.g. on a theme change). */
    fun attachEditor(view: KudVoiceEditorView?) {
        editorView = view
        view?.controller = editor
        view?.render(editor.ui)
    }

    /**
     * Input started (or restarted) in a field; a different field drops the session and a pending Fix. [newInput]: from
     * onStartInput (also a restart in the same field), which drops a Fix/Instruct in flight even when the field looks
     * the same.
     */
    @JvmOverloads
    fun onField(editorInfo: EditorInfo?, newInput: Boolean = false) {
        val f = VoiceField.of(editorInfo)
        controller.onField(f)
        textActions.onField(f)
        if (newInput) textActions.onInputStarted()
        // the editor's look follows the field (normal / incognito); its draft is not tied to a field
        editorView?.render(editor.ui)
    }

    /** The field is gone (onFinishInput, or onFinishInputView with finishingInput). */
    fun onFieldFinished() {
        controller.onField(null)
        textActions.onField(null)
        editor.onHidden()
    }

    /** The keyboard was hidden but the field may still be bound. The editor closes and keeps its draft. */
    fun onHidden() {
        controller.onHidden()
        textActions.onHidden()
        editor.onHidden()
    }

    /** Comma long-press: hands-free recording. */
    fun startLocked() = controller.startLocked()

    fun destroy() {
        runCatching { ime.unregisterReceiver(screenOff) }
        controller.close()
        editor.destroy()
        textActions.destroy()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    override fun hasToken() = settings.hasToken
    override fun hasMicPermission(): Boolean {
        val granted = hasMicPermission(ime)
        // Allowed in system settings since: forget the block, so a later revoke asks again.
        if (granted && MicPermissionActivity.isBlocked(ime)) MicPermissionActivity.setBlocked(ime, false)
        return granted
    }
    override fun requestMicPermission() = MicPermissionActivity.start(ime)
    override fun isMicBlocked() = MicPermissionActivity.isBlocked(ime)
    override fun openMicSettings() = MicPermissionActivity.openAppSettings(ime)
    override fun isPrivateField() = Settings.getValues().mIncognitoModeEnabled
    override fun haptic() {
        strip?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }
    override fun insert(text: String) = ime.onVoiceTextInput(text)
    override fun render(state: VoiceBarState) { strip?.renderVoice(state) }
    override fun barWidth() = strip?.width?.takeIf { it > 0 } ?: ime.resources.displayMetrics.widthPixels
    override fun lockDistance() = 40.dpToPx(ime.resources)
    override fun onTap() {
        if (!Settings.getValues().mKudVoiceTyping) return
        editor.open()
    }
    override fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
        val r = Runnable(block)
        handler.postDelayed(r, delayMs)
        return { handler.removeCallbacks(r) }
    }
}
