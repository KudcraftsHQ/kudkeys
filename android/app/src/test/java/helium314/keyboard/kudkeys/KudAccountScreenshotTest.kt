// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.kudcrafts.kudkeys.voice.Account
import com.kudcrafts.kudkeys.voice.VoiceSettings
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.screens.KudMainSettingsScreen
import helium314.keyboard.settings.screens.KudProfileScreen
import helium314.keyboard.settings.screens.KudSignInScreen
import helium314.keyboard.settings.screens.VoiceScreen
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
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

/** The account screens, to `$TMPDIR/kudkeys-v02-shots/account-*.png`. The profile loads from a MockWebServer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowInputMethodManager2::class], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
class KudAccountScreenshotTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val outDir = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"), "kudkeys-v02-shots")
    private val voicePrefs = latinIME.getSharedPreferences(VoiceSettings.PREFS, Context.MODE_PRIVATE)
    private val settings = VoiceSettings(voicePrefs)
    private val server = MockWebServer()

    init {
        latinIME.prefs().edit().clear().commit()
        voicePrefs.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(latinIME)
        Settings.getInstance().loadSettings(latinIME, Locale.US, InputAttributes(EditorInfo(), false, latinIME.packageName))
        outDir.mkdirs()
    }

    @After fun tearDown() = server.shutdown()

    private fun renderScreen(name: String, heightFactor: Float = 1f, settle: () -> Boolean = { true }, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        SettingsActivity.settingsContainer = SettingsContainer(activity)
        activity.setContent { Theme(true) { Surface { content() } } }
        val dm = activity.resources.displayMetrics
        val root = activity.window.decorView
        val height = (dm.heightPixels * heightFactor).toInt()
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, dm.widthPixels, height)
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        }
        val end = System.currentTimeMillis() + 10_000
        do { layout(); Thread.sleep(50) } while (!settle() && System.currentTimeMillis() < end)
        repeat(3) { layout(); Thread.sleep(50) }
        val bitmap = Bitmap.createBitmap(dm.widthPixels, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable private fun home() = KudMainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {})

    @Test fun rowSignedOut() = renderScreen("account-0-row-signed-out") { home() }

    @Test fun rowSignedIn() {
        settings.signedIn("tok.sig", "Hammas", "hammas.hamzah@gmail.com")
        renderScreen("account-1-row-signed-in") { home() }
    }

    @Test fun signIn() = renderScreen("account-2-sign-in") { KudSignInScreen(Account(settings), {}, {}) }

    @Test fun signInAfter401() {
        settings.signedIn("tok.sig", "Hammas", "hammas.hamzah@gmail.com")
        settings.markSignedOut()
        renderScreen("account-3-sign-in-signed-out") { KudSignInScreen(Account(settings), {}, {}) }
    }

    @Test fun profile() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/api/me" -> json("""{"id":"u1","email":"hammas.hamzah@gmail.com","name":"Hammas","image":null,
                    "voiceKeywords":["Fenno","TypeMap","FaceMap","Kudcrafts","kenapa.fyi","Hammas"],"languages":["id","en"]}""")
                "/api/me/usage" -> json("""{"week":{"since":"2026-10-25T17:00:00.000Z","sessions":12,"audioMinutes":8.4},
                    "month":{"since":"2026-09-30T17:00:00.000Z","sessions":40,"audioMinutes":31.2},
                    "sessions":[${(0 until 6).joinToString(",") { i ->
                        """{"id":"c$i","session":"s$i","startedAt":"2026-10-${31 - i / 3}T0${9 - i}:15:00.000Z","endedAt":"x",
                        "audioSeconds":${4 + i * 9}.0,"finals":2,"outcome":"${if (i == 2) "idle" else "stopped"}","model":"whisper-large-v3"}"""
                    }}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        settings.serverUrl = server.url("/v1/stream").toString().replace("http://", "ws://")
        settings.signedIn("tok.sig", "Hammas", "hammas.hamzah@gmail.com")
        renderScreen("account-4-profile", 2.1f, settle = { server.requestCount >= 2 }) {
            KudProfileScreen(Account(settings), {}, {}, {})
        }
        // MockWebServer can also record a dropped connection (no headers): look at the /api/me call itself
        val me = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }.first { it.path == "/api/me" }
        assertEquals("Bearer tok.sig", me.getHeader("Authorization"))
    }

    @Test fun voiceScreen() {
        settings.signedIn("tok.sig", "Hammas", "hammas.hamzah@gmail.com")
        renderScreen("account-5-voice") { VoiceScreen({}) }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
