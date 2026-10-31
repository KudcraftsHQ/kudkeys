// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
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

    fun attach(view: SuggestionStripView?) {
        strip = view
        view?.setQuickVoice(controller)
    }

    /** Leaving the field or hiding the keyboard. */
    fun cancel() = controller.cancelSession()

    fun destroy() {
        controller.close()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    override fun hasToken() = settings.hasToken
    override fun hasMicPermission() = hasMicPermission(ime)
    override fun requestMicPermission() = MicPermissionActivity.start(ime)
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
