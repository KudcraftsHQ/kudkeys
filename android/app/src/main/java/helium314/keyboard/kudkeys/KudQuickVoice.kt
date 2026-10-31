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
import com.kudcrafts.kudkeys.voice.VoiceClient
import com.kudcrafts.kudkeys.voice.VoiceSettings
import com.kudcrafts.kudkeys.voice.hasMicPermission
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.dpToPx
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/** Connects quick voice to the IME: one per [LatinIME], drawing into whichever strip view is current. */
class KudQuickVoice(private val ime: LatinIME) : QuickVoiceController.Host {
    private val settings = VoiceSettings(ime)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var strip: SuggestionStripView? = null
    val controller = QuickVoiceController(VoiceClientEngine(VoiceClient.create(ime)), this, scope)

    /** A locked recording must not keep the mic open with the screen off. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) controller.onHidden()
        }
    }

    init {
        ime.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    fun attach(view: SuggestionStripView?) {
        strip = view
        view?.setQuickVoice(controller)
    }

    /** Input started (or restarted) in a field; a different field drops the session. */
    fun onField(editorInfo: EditorInfo?) = controller.onField(VoiceField.of(editorInfo))

    /** The field is gone (onFinishInput, or onFinishInputView with finishingInput). */
    fun onFieldFinished() = controller.onField(null)

    /** The keyboard was hidden but the field may still be bound. */
    fun onHidden() = controller.onHidden()

    /** Comma long-press: hands-free recording. */
    fun startLocked() = controller.startLocked()

    fun destroy() {
        runCatching { ime.unregisterReceiver(screenOff) }
        controller.close()
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
    override fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
        val r = Runnable(block)
        handler.postDelayed(r, delayMs)
        return { handler.removeCallbacks(r) }
    }
}
