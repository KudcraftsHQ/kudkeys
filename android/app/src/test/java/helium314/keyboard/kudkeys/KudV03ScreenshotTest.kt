// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inputmethod.EditorInfo
import com.kudcrafts.kudkeys.voice.TidyQuestion
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.SubtypeSettings
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
 * Full voice mode, Fix and Instruct drawn by the real keyboard views at a 411 dp phone, into
 * `$TMPDIR/kudkeys-v03-shots/`. Look at them after any change to the editor or the bar.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowInputMethodManager2::class], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
class KudV03ScreenshotTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val switcher = KeyboardSwitcher.getInstance()
    private val outDir = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"), "kudkeys-v03-shots")
    private lateinit var inputView: View
    private lateinit var strip: SuggestionStripView
    private lateinit var editor: KudVoiceEditorView

    init {
        latinIME.prefs().edit().clear().commit()
        latinIME.getSharedPreferences(PrefsDraftStore.PREFS, 0).edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(latinIME)
        outDir.mkdirs()
    }

    private fun keyboard(ei: EditorInfo = EditorInfo()) {
        Settings.getInstance().loadSettings(latinIME, Locale.US, InputAttributes(ei, false, latinIME.packageName))
        inputView = switcher.onCreateInputView(latinIME, true)
        latinIME.setInputView(inputView)
        switcher.reloadMainKeyboard()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        strip = inputView.findViewById(R.id.suggestion_strip_view)
        editor = inputView.findViewById(R.id.kud_voice_editor)
    }

    private fun layout() {
        val width = latinIME.resources.displayMetrics.widthPixels
        repeat(2) {
            forceLayout(inputView)
            inputView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            inputView.layout(0, 0, inputView.measuredWidth, inputView.measuredHeight)
        }
    }

    private fun shot(name: String) {
        layout()
        // the editor scrolls its text in a posted block, once laid out
        ShadowLooper.idleMainLooper()
        layout()
        preDrawText(inputView)
        val bitmap = Bitmap.createBitmap(inputView.measuredWidth, inputView.measuredHeight, Bitmap.Config.ARGB_8888)
        inputView.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun forceLayout(view: View) {
        view.forceLayout()
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) forceLayout(view.getChildAt(i))
    }

    private fun preDrawText(view: View) {
        if (view is android.widget.TextView) view.onPreDraw()
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) preDrawText(view.getChildAt(i))
    }

    private fun showEditor(ui: EditorUi) {
        editor.render(ui)
        strip.renderEditor(ui)
    }

    private val tidied = "Oke, besok kita meeting jam 10 sama Feno. Yang perlu dibahas:\n\n" +
        "1. Invoice bulan ini\n2. Jadwal shooting iklan baru\n3. Budget Meta Ads minggu depan"
    private val raw = "oke besok kita meeting jam sepuluh sama feno yang perlu dibahas pertama invoice bulan ini " +
        "kedua jadwal shooting iklan baru terus yang ketiga budget meta ads minggu depan"
    private val feno = tidied.indexOf("Feno")
    private val question = TidyQuestion("q1", "Feno", feno, feno + 4, listOf("Fenno"))

    @Test fun editorRecording() {
        keyboard()
        showEditor(EditorUi(open = true, mode = EditorMode.Recording, text = "Oke besok kita meeting jam sepuluh sama Feno,",
            partial = " yang perlu dibahas pertama", elapsedMs = 12_000, hasDraft = true))
        shot("1-editor-recording")
        // the editor covers exactly the key area, the bar stays above it
        val keys = inputView.findViewById<View>(R.id.keyboard_view)
        assertEquals(keys.height, editor.height)
        assertEquals(View.VISIBLE, editor.visibility)
        // the top bar stays: ✦ Fix · … · Instruct · Mic (Mic stops, like the big button)
        assertEquals(View.VISIBLE, strip.findViewById<View>(R.id.kud_fix_key).visibility)
        assertEquals(View.VISIBLE, strip.findViewById<View>(R.id.kud_instruct_key).visibility)
        assertEquals(latinIME.getString(R.string.kud_stop), strip.findViewById<View>(R.id.kud_mic_key).contentDescription)
    }

    /** While dictating, text that fits is never scrolled (a few pixels of scroll faded the first line half away). */
    @Test fun editorRecordingShortTextNeverScrolls() {
        keyboard()
        showEditor(EditorUi(open = true, mode = EditorMode.Recording, text = "Can you check and plan", elapsedMs = 3_000, hasDraft = true))
        shot("12-editor-recording-grows-1")
        showEditor(EditorUi(open = true, mode = EditorMode.Recording, text = "Can you check and plan what can we improve from the",
            partial = " system", elapsedMs = 11_000, hasDraft = true))
        shot("12-editor-recording-grows-2")
        assertEquals(0, editor.textScroll.scrollY, "two lines fit: no scroll")
        // longer than the area: scrolled by whole lines, the newest line fully in view
        showEditor(EditorUi(open = true, mode = EditorMode.Recording, text = raw + " " + raw, partial = " dan satu lagi", elapsedMs = 40_000, hasDraft = true))
        shot("12-editor-recording-grows-3")
        val scroll = editor.textScroll
        val body = editor.textBody
        val line = body.lineHeight
        assertTrue(scroll.scrollY > 0)
        assertEquals(0, scroll.scrollY % line, "scrolled by whole lines")
        val last = body.layout.lineCount - 1
        assertTrue(body.layout.getLineBottom(last) <= scroll.scrollY + scroll.height, "the newest line is in view")
    }

    private val longTidied = tidied + "\n\nTerus soal shooting, kita butuh lokasi yang terang, jadi mungkin di studio " +
        "yang biasa aja. Kalo studio penuh, kita pindah ke rumah aja, nggak apa-apa.\n\nBudget Meta Ads naik dikit " +
        "minggu depan, soalnya iklan yang kemaren performanya bagus banget. Nanti aku kabarin angkanya.\n\n" +
        "Satu lagi, tolong cek kontrak sama Rendi sebelum Jumat ya."
    private val rendi = longTidied.indexOf("Rendi")

    /** V1: a long tidied text, scrolled so the question's word is in view; whole lines only. */
    @Test fun editorLongTextScrolledToQuestion() {
        keyboard()
        showEditor(EditorUi(open = true, text = longTidied, canShowRaw = true, canUndo = true,
            question = TidyQuestion("q1", "Rendi", rendi, rendi + 5, listOf("Randy", "Hendi")), questionsLeft = 1, hasDraft = true))
        shot("11-editor-long-question")
        val scroll = editor.textScroll
        val body = editor.textBody
        val line = body.lineHeight
        assertEquals(0, scroll.height % line, "the text area shows whole lines")
        assertTrue(scroll.height / line >= 3, "at least three lines with the card showing")
        assertEquals(0, scroll.scrollY % line, "scrolled by whole lines")
        val l = body.layout.getLineForOffset(rendi)
        assertTrue(body.layout.getLineTop(l) >= scroll.scrollY && body.layout.getLineBottom(l) <= scroll.scrollY + scroll.height,
            "the question's word is in view")
        assertTrue(scroll.scrollY > 0)
    }

    /** H4: tidy failed (busy): the message says when, and Tidy is offered. */
    @Test fun editorTidyRetry() {
        keyboard()
        showEditor(EditorUi(open = true, text = raw, canTidy = true, canUndo = true, hasDraft = true,
            message = "Tidy busy — try again in 20 s"))
        shot("12-editor-tidy-retry")
    }

    @Test fun editorTidiedWithQuestion() {
        keyboard()
        showEditor(EditorUi(open = true, text = tidied, canShowRaw = true, canUndo = true, question = question,
            questionsLeft = 2, hasDraft = true))
        shot("2-editor-tidied-question")
    }

    @Test fun editorTidyOff() {
        keyboard()
        showEditor(EditorUi(open = true, text = raw, tidyOn = false, hasDraft = true))
        shot("3-editor-tidy-off")
    }

    @Test fun editorRaw() {
        keyboard()
        showEditor(EditorUi(open = true, text = raw, showingRaw = true, canShowRaw = true, canUndo = true, hasDraft = true))
        shot("4-editor-raw")
    }

    @Test fun editorSpokenEdit() {
        keyboard()
        showEditor(EditorUi(open = true, mode = EditorMode.InstructListening, text = tidied, canShowRaw = true,
            instruction = "jadikan list bernomor yang lebih singkat", hasDraft = true))
        shot("5-editor-spoken-edit")
    }

    @Test fun barFixed() {
        keyboard()
        strip.renderTextAction(TextBarState.Message(TextActionsController.FIXED, undo = true))
        shot("6-bar-fixed-undo")
    }

    @Test fun barInstructListening() {
        keyboard()
        strip.renderTextAction(TextBarState.Listening(2_300, "bikin lebih sopan"))
        shot("7-bar-instruct-listening")
    }

    @Test fun barInstructWaiting() {
        keyboard()
        strip.renderTextAction(TextBarState.Busy(TextAction.Instruct))
        shot("8-bar-instruct-waiting")
        assertTrue(strip.findViewById<android.widget.ImageButton>(R.id.kud_instruct_key).drawable is KudSpinnerDrawable)
        strip.renderTextAction(TextBarState.Idle)
        assertFalse(strip.findViewById<android.widget.ImageButton>(R.id.kud_instruct_key).drawable is KudSpinnerDrawable)
    }

    @Test fun barDraftDot() {
        keyboard()
        showEditor(EditorUi(open = false, hasDraft = true))
        shot("9-bar-draft-dot")
        assertTrue(strip.isMicDotShown)
    }

    @Test fun editorIncognito() {
        keyboard(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_SEARCH })
        assertTrue(Settings.getValues().mKudIncognitoLook)
        strip.updatePrivacy()
        showEditor(EditorUi(open = true, text = tidied, canShowRaw = true, question = question, questionsLeft = 1, hasDraft = true))
        shot("10-editor-incognito")
    }

    /** The real wiring: a tap on Mic opens the editor (no token here, so it says so instead of recording). */
    @Test fun micTapOpensEditor() {
        keyboard()
        val voice = latinIME.kudVoice!!
        voice.controller.onMicDown(10f, 10f)
        voice.controller.onMicUp()
        assertTrue(voice.editor.isOpen)
        assertEquals(View.VISIBLE, editor.visibility)
        voice.editor.close()
        assertEquals(View.GONE, editor.visibility)
    }
}
