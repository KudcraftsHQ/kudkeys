// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inputmethod.EditorInfo
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Renders the keyboard with its top bar in four states (idle with suggestions, private, recording, locked) at a
 * 411 dp phone and writes PNGs to `$TMPDIR/kudkeys-toolbar/`. Also pins the bar's icons: Fix left, Instruct and
 * Mic right, nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowInputMethodManager2::class], qualifiers = "w411dp-h891dp-port-xxhdpi")
class KudKeysToolbarScreenshotTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val switcher = KeyboardSwitcher.getInstance()
    private val outDir = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"), "kudkeys-toolbar")
    private val inputView: View
    private val strip: SuggestionStripView

    init {
        latinIME.prefs().edit().clear().commit()
        loadSettingsFor(EditorInfo())
        inputView = switcher.onCreateInputView(latinIME, true)
        latinIME.setInputView(inputView)
        switcher.reloadMainKeyboard()
        strip = inputView.findViewById(R.id.suggestion_strip_view)
    }

    private fun loadSettingsFor(ei: EditorInfo) {
        Settings.getInstance().loadSettings(latinIME, Locale.US, InputAttributes(ei, false, latinIME.packageName))
    }

    private fun suggestions(vararg words: String): SuggestedWords {
        val infos = ArrayList(words.mapIndexed { i, w ->
            SuggestedWordInfo(w, "", 100 - i, SuggestedWordInfo.KIND_COMPLETION, Dictionary.DICTIONARY_USER_TYPED, 0, 0)
        })
        return SuggestedWords(infos, null, infos[0], false, false, false, SuggestedWords.INPUT_STYLE_TYPING, 0)
    }

    /** Lets the IME settle, then applies [state] to the bar and draws keyboard + bar. */
    private fun render(name: String, state: () -> Unit) {
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        layoutOnce()
        state()
        forceLayout(inputView)
        layoutOnce()
        // single-line centred text (the suggestion words) is scrolled into view in the pre-draw pass, which only a
        // real window runs
        preDrawText(inputView)
        val bitmap = Bitmap.createBitmap(inputView.measuredWidth, inputView.measuredHeight, Bitmap.Config.ARGB_8888)
        inputView.draw(Canvas(bitmap))
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun preDrawText(view: View) {
        if (view is android.widget.TextView) view.onPreDraw()
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) preDrawText(view.getChildAt(i))
    }

    private fun forceLayout(view: View) {
        view.forceLayout()
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) forceLayout(view.getChildAt(i))
    }

    private fun layoutOnce() {
        val width = latinIME.resources.displayMetrics.widthPixels
        inputView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        inputView.layout(0, 0, inputView.measuredWidth, inputView.measuredHeight)
    }

    @Test fun barHasExactlyThreeIcons() {
        layoutOnce()
        val fix = strip.findViewById<View>(R.id.kud_fix_key)
        val instruct = strip.findViewById<View>(R.id.kud_instruct_key)
        val mic = strip.findViewById<View>(R.id.kud_mic_key)
        assertEquals(0, fix.left)
        assertEquals(strip.width, mic.right)
        assertEquals(mic.left, instruct.right)
        // one row: Fix, the middle, Instruct, Mic, and nothing else
        val row = strip.findViewById<android.view.ViewGroup>(R.id.suggestions_strip_wrapper)
        assertEquals(listOf(R.id.kud_fix_key, R.id.kud_bar_middle, R.id.kud_instruct_key, R.id.kud_mic_key),
            (0 until row.childCount).map { row.getChildAt(it).id })
    }

    @Test fun idleWithSuggestions() = render("1-idle-suggestions") {
        strip.setSuggestions(suggestions("halo", "hai", "hari"), false)
        assertFalse(strip.isPrivatePillShown)
    }

    @Test fun privateField() = render("2-private") {
        loadSettingsFor(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_SEARCH })
        strip.setSuggestions(SuggestedWords.getEmptyInstance(), false)
        assertFalse(strip.isPrivatePillShown) // no amber marker; the incognito look shows privacy
    }

    @Test fun recordingWithLiveText() = render("3-recording") {
        strip.renderVoice(VoiceBarState.Recording(7_400, "aku udah di jalan, nanti kabarin lagi ya", locked = false))
    }

    @Test fun locked() = render("4-locked") {
        strip.renderVoice(VoiceBarState.Recording(42_000, "catatan buat rapat besok: cek angka iklan", locked = true))
    }
}
