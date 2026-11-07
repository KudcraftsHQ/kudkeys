// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.content.Context
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.Typeface
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getDisplayContext

/**
 * Makes the first keyboard open (process running, keyboard never shown) nearly as fast as a later one. Android
 * restarts the IME right after its process dies, so there is idle time between the service starting and the first
 * show; in it, one small step per idle moment (a show request can always get in between):
 * 1. the input view is inflated ([KeyboardSwitcher.prebuildInputView]); the first show takes it instead of inflating
 *    (the normal look first, the private fields' incognito look last);
 * 2. the alphabet keyboards for the current field are built, warming the layout parser's caches and classes;
 * 3. a tiny off-screen frame is drawn with the hardware renderer, on its own thread: the render thread's EGL context,
 *    shader cache and glyph cache are created now, not during the first frame of the keyboard (an IME, unlike an
 *    activity, gets no renderer preload from the system).
 * Nothing here changes behaviour: each step is skipped once the keyboard is showing or has been built.
 */
object KudWarmup {
    private const val TAG = "KudWarmup"

    /** [hardwareAccelerated]: what LatinIME got from enableHardwareAcceleration, which may only be called before onCreate. */
    /** Bumped by [stop]: an idle handler from an earlier [start] gives up instead of touching a destroyed IME. */
    private var generation = 0

    /** LatinIME.onDestroy: drop any warmup steps still waiting, so they never run against a destroyed keyboard. */
    fun stop() {
        generation++
    }

    fun start(ime: LatinIME, hardwareAccelerated: Boolean) {
        val gen = ++generation
        val switcher = KeyboardSwitcher.getInstance()
        val steps = ArrayDeque<Pair<String, () -> Unit>>()
        steps += "input view" to { switcher.prebuildInputView(ime.getDisplayContext(), false) }
        steps += "keyboard" to { switcher.prewarmKeyboard(ime.currentInputEditorInfo, KeyboardElement.ALPHABET_AUTOMATIC_SHIFTED) }
        steps += "keyboard (unshifted)" to { switcher.prewarmKeyboard(ime.currentInputEditorInfo, KeyboardElement.ALPHABET) }
        steps += "input view (private look)" to { switcher.prebuildInputView(ime.getDisplayContext(), true) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hardwareAccelerated)
            steps += "renderer" to { warmRenderer(ime) }

        val handler = Handler(Looper.getMainLooper())
        Looper.myQueue().addIdleHandler(object : MessageQueue.IdleHandler {
            override fun queueIdle(): Boolean {
                if (gen != generation) return false // stopped: the IME was destroyed (or started again)
                if (ime.isInputViewShown) return false // too late to help
                val (name, step) = steps.removeFirstOrNull() ?: return false
                val t = SystemClock.uptimeMillis()
                try {
                    step()
                } catch (e: Throwable) {
                    Log.w(TAG, "$name failed", e)
                }
                Log.i(TAG, "$name: ${SystemClock.uptimeMillis() - t} ms")
                if (steps.isEmpty()) return false
                handler.post { } // an idle handler only runs again after a message: queue one for the next step
                return true
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun warmRenderer(context: Context) {
        val app = context.applicationContext ?: context
        Thread({
            try {
                drawOffscreen(app)
            } catch (e: Throwable) {
                Log.w(TAG, "renderer warmup failed", e)
            }
        }, "kudkeys-warmup").apply { isDaemon = true }.start()
    }

    /** One frame like a keyboard's (fills, rounded keys, letters, a vector icon) into a small off-screen surface. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun drawOffscreen(context: Context) {
        val w = 256
        val h = 128
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val renderer = HardwareRenderer()
        try {
            val node = RenderNode("kudkeys-warmup")
            node.setPosition(0, 0, w, h)
            val canvas = node.beginRecording(w, h)
            try {
                canvas.drawColor(Color.rgb(23, 23, 23))
                val key = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(42, 46, 55) }
                canvas.drawRoundRect(4f, 4f, 60f, 60f, 10f, 10f, key)
                canvas.drawRect(64f, 4f, 120f, 60f, key)
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(214, 214, 214)
                    textSize = 48f
                    typeface = Typeface.DEFAULT
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("qwertyuiopasdfghjklzxcvbnm", w / 2f, 110f, text)
                text.textSize = 30f
                canvas.drawText("QWERTYUIOP,.?!1234567890", w / 2f, 80f, text)
                ContextCompat.getDrawable(context, R.drawable.sym_keyboard_shift_lxx)?.let {
                    it.setBounds(130, 4, 186, 60)
                    it.draw(canvas)
                }
            } finally {
                node.endRecording()
            }
            renderer.setContentRoot(node)
            renderer.setSurface(reader.surface)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        } finally {
            renderer.destroy()
            reader.close()
        }
    }
}
