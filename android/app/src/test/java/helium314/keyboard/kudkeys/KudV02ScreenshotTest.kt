// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.screens.KudEmojiScreen
import helium314.keyboard.settings.screens.KudLanguagesScreen
import helium314.keyboard.settings.screens.KudLayoutKeysScreen
import helium314.keyboard.settings.screens.KudMainSettingsScreen
import helium314.keyboard.settings.screens.KudResizeScreen
import helium314.keyboard.settings.screens.KudRichInputScreen
import helium314.keyboard.settings.screens.KudSoundScreen
import helium314.keyboard.settings.screens.KudTypingScreen
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Writes the v0.2 screens to `$TMPDIR/kudkeys-v02-shots/`: every SwiftKey-style settings screen, and the keyboard
 * in a normal and in a private field (SwiftKey's incognito look), to compare with the SwiftKey screenshots by eye.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowInputMethodManager2::class], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
class KudV02ScreenshotTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val switcher = KeyboardSwitcher.getInstance()
    private val outDir = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"), "kudkeys-v02-shots")

    init {
        latinIME.prefs().edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(latinIME) // the singleton is shared across test classes
        loadSettingsFor(EditorInfo())
        outDir.mkdirs()
    }

    private fun loadSettingsFor(ei: EditorInfo) {
        Settings.getInstance().loadSettings(latinIME, Locale.US, InputAttributes(ei, false, latinIME.packageName))
    }

    private fun save(name: String, bitmap: Bitmap) {
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- the keyboard ----

    private fun renderKeyboard(name: String, ei: EditorInfo) {
        loadSettingsFor(ei)
        val inputView = switcher.onCreateInputView(latinIME, true)
        latinIME.setInputView(inputView)
        switcher.reloadMainKeyboard()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        val strip = inputView.findViewById<SuggestionStripView>(R.id.suggestion_strip_view)
        val infos = ArrayList(listOf("aku", "I", "kau").mapIndexed { i, w ->
            SuggestedWordInfo(w, "", 100 - i, SuggestedWordInfo.KIND_PREDICTION, Dictionary.DICTIONARY_USER_TYPED, 0, 0)
        })
        strip.setSuggestions(SuggestedWords(infos, null, null, false, false, false, SuggestedWords.INPUT_STYLE_PREDICTION, 0), false)
        val width = latinIME.resources.displayMetrics.widthPixels
        repeat(2) {
            inputView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            inputView.layout(0, 0, inputView.measuredWidth, inputView.measuredHeight)
        }
        preDrawText(inputView)
        val bitmap = Bitmap.createBitmap(inputView.measuredWidth, inputView.measuredHeight, Bitmap.Config.ARGB_8888)
        inputView.draw(Canvas(bitmap))
        save(name, bitmap)
    }

    private fun preDrawText(view: View) {
        if (view is android.widget.TextView) view.onPreDraw()
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) preDrawText(view.getChildAt(i))
    }

    @Test fun keyboardNormal() = renderKeyboard("keyboard-normal", EditorInfo())

    @Test fun keyboardIncognito() {
        renderKeyboard("keyboard-incognito", EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_SEARCH })
        assertTrue(Settings.getValues().mKudIncognitoLook)
    }

    // ---- settings screens ----

    private fun renderScreen(name: String, heightFactor: Float = 1f, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        SettingsActivity.settingsContainer = SettingsContainer(activity)
        activity.setContent { Theme(true) { Surface { content() } } }
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        val dm = activity.resources.displayMetrics
        val root = activity.window.decorView
        val height = (dm.heightPixels * heightFactor).toInt()
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, dm.widthPixels, height)
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        }
        val bitmap = Bitmap.createBitmap(dm.widthPixels, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        save(name, bitmap)
    }

    @Test fun settingsHome() = renderScreen("settings-0-home") { KudMainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun settingsLanguages() = renderScreen("settings-1-languages") { KudLanguagesScreen({}, {}) }
    @Test fun settingsTyping() = renderScreen("settings-2-typing", 1.25f) { KudTypingScreen {} }
    @Test fun settingsEmoji() = renderScreen("settings-3-emoji") { KudEmojiScreen {} }
    @Test fun settingsRichInput() = renderScreen("settings-4-rich-input") { KudRichInputScreen {} }
    @Test fun settingsLayoutKeys() = renderScreen("settings-5-layout-keys", 1.15f) { KudLayoutKeysScreen {} }
    @Test fun settingsSound() = renderScreen("settings-6-sound") { KudSoundScreen {} }
    @Test fun settingsResize() = renderScreen("settings-7-resize") { KudResizeScreen {} }
}
