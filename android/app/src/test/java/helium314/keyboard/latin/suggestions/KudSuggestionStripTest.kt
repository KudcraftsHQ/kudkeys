// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.Settings
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SwiftKey's strip: always three slots, the most likely suggestion in the centre, the typed word verbatim on the left
 * while typing, and a long word shrinks its font instead of taking the whole strip. Screenshots go to
 * `$TMPDIR/kudkeys-strip/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowInputMethodManager2::class], qualifiers = "w411dp-h891dp-port-xxhdpi")
class KudSuggestionStripTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val switcher = KeyboardSwitcher.getInstance()
    private val outDir = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"), "kudkeys-strip")
    private val inputView: View
    private val strip: SuggestionStripView

    init {
        latinIME.prefs().edit().clear().commit()
        Settings.getInstance().loadSettings(latinIME, Locale.US, InputAttributes(EditorInfo(), false, latinIME.packageName))
        inputView = switcher.onCreateInputView(latinIME, true)
        latinIME.setInputView(inputView)
        switcher.reloadMainKeyboard()
        strip = inputView.findViewById(R.id.suggestion_strip_view)
    }

    // ---- the slot rule, pure ----

    @Test fun typingPutsTheTypedWordLeftAndTheBestInTheCentre() {
        assertContentEquals(intArrayOf(0, 1, 2), SuggestionStripLayoutHelper.getSlotIndices(listOf("Sjdh", "Dish", "Such", "And"), true))
    }

    @Test fun predictionsPutTheBestInTheCentre() {
        assertContentEquals(intArrayOf(1, 0, 2), SuggestionStripLayoutHelper.getSlotIndices(listOf("aku", "kamu", "dia"), false))
    }

    @Test fun theTypedWordNeverShowsTwice() {
        // HeliBoard adds the typed word again after an auto-correction
        assertContentEquals(intArrayOf(0, 1, 3), SuggestionStripLayoutHelper.getSlotIndices(listOf("hullo", "hello", "hullo", "hall"), true))
    }

    @Test fun missingSuggestionsLeaveEmptySlots() {
        assertContentEquals(intArrayOf(0, -1, -1), SuggestionStripLayoutHelper.getSlotIndices(listOf("Sjdh"), true))
        assertContentEquals(intArrayOf(-1, 0, -1), SuggestionStripLayoutHelper.getSlotIndices(listOf("aku"), false))
        assertContentEquals(intArrayOf(-1, -1, -1), SuggestionStripLayoutHelper.getSlotIndices(emptyList(), false))
    }

    // ---- the real strip ----

    private fun typing(typed: String, vararg suggestions: String): SuggestedWords {
        val typedInfo = SuggestedWordInfo(typed, "", SuggestedWordInfo.MAX_SCORE, SuggestedWordInfo.KIND_TYPED,
            Dictionary.DICTIONARY_USER_TYPED, 0, 0)
        val infos = ArrayList(listOf(typedInfo) + suggestions.mapIndexed { i, w ->
            SuggestedWordInfo(w, "", 100 - i, SuggestedWordInfo.KIND_CORRECTION, Dictionary.DICTIONARY_USER_TYPED, 0, 0)
        })
        return SuggestedWords(infos, null, typedInfo, false, false, false, SuggestedWords.INPUT_STYLE_TYPING, 0)
    }

    private fun predictions(vararg words: String): SuggestedWords {
        val infos = ArrayList(words.mapIndexed { i, w ->
            SuggestedWordInfo(w, "", 100 - i, SuggestedWordInfo.KIND_PREDICTION, Dictionary.DICTIONARY_USER_TYPED, 0, 0)
        })
        return SuggestedWords(infos, null, null, false, false, false, SuggestedWords.INPUT_STYLE_PREDICTION, 0)
    }

    private fun layoutOnce() {
        val width = latinIME.resources.displayMetrics.widthPixels
        inputView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        inputView.layout(0, 0, inputView.measuredWidth, inputView.measuredHeight)
    }

    private fun forceLayout(view: View) {
        view.forceLayout()
        if (view is ViewGroup) for (i in 0 until view.childCount) forceLayout(view.getChildAt(i))
    }

    private fun preDrawText(view: View) {
        if (view is TextView) view.onPreDraw()
        if (view is ViewGroup) for (i in 0 until view.childCount) preDrawText(view.getChildAt(i))
    }

    /** Shows [words] in the strip (laid out at the phone's width) and returns the slot views left to right. */
    private fun show(words: SuggestedWords): List<TextView> {
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        layoutOnce()
        strip.setSuggestions(words, false)
        forceLayout(inputView)
        layoutOnce()
        val row = strip.findViewById<ViewGroup>(R.id.suggestions_strip)
        return (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<TextView>()
    }

    private fun screenshot(name: String) {
        preDrawText(inputView)
        val bitmap = Bitmap.createBitmap(inputView.measuredWidth, inputView.measuredHeight, Bitmap.Config.ARGB_8888)
        inputView.draw(Canvas(bitmap))
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val baseTextSize by lazy { show(predictions("a", "b", "c"))[1].textSize }

    @Test fun typingShowsVerbatimLeftBestCentre() {
        val slots = show(typing("Sjdh", "Dish", "Such", "And"))
        assertEquals(listOf("Sjdh", "Dish", "Such"), slots.map { it.text.toString() })
        screenshot("1-typing-verbatim-left")
    }

    @Test fun predictionsShowBestCentre() {
        val slots = show(predictions("aku", "kamu", "dia", "kita"))
        assertEquals(listOf("kamu", "aku", "dia"), slots.map { it.text.toString() })
        screenshot("2-predictions")
    }

    @Test fun aLongSuggestionShrinksItsFontAndKeepsThreeSlots() {
        val base = baseTextSize
        val slots = show(typing("internasional", "internasionalnya", "internasionalisasi"))
        assertEquals(3, slots.size, "always three slots")
        val widths = slots.map { it.width }
        assertTrue(widths.all { it > 0 }, "slot widths $widths")
        val centre = slots[1]
        assertEquals("internasionalnya", centre.text.toString(), "fits at a smaller size, not cut")
        assertTrue(centre.textSize < base && centre.textSize >= base * SuggestionStripLayoutHelper.MIN_TEXT_SIZE_SCALE - 0.5f,
            "centre text size ${centre.textSize} vs normal $base")
        assertEquals(1f, centre.textScaleX, "letters are not squeezed")
        // a short word in the same strip keeps the normal size
        val short = show(typing("ok", "oke", "okay"))
        assertEquals(base, short[1].textSize)
        show(typing("internasional", "internasionalnya", "internasionalisasi"))
        screenshot("3-long-suggestion")
    }

    @Test fun aVeryLongSuggestionIsEllipsizedAtTheSmallestSize() {
        val base = baseTextSize
        val slots = show(predictions("pertanggungjawabannyalah-sekalian-semuanya", "dan", "yang"))
        val centre = slots[1]
        assertEquals(base * SuggestionStripLayoutHelper.MIN_TEXT_SIZE_SCALE, centre.textSize, 0.5f)
        assertTrue(centre.text.endsWith("…"), "ellipsized: ${centre.text}")
        assertEquals(3, slots.size)
        screenshot("4-very-long-suggestion")
    }

    @Test fun oneSuggestionKeepsThreeSlots() {
        val slots = show(typing("Sjdh"))
        assertEquals(3, slots.size)
        assertEquals(listOf("Sjdh", "", ""), slots.map { it.text?.toString() ?: "" })
        val widths = slots.map { it.width }
        assertTrue(widths.all { it > 0 }, "empty slots keep their width: $widths")
    }
}
