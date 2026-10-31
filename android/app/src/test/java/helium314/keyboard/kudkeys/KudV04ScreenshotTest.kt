// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.kudcrafts.kudkeys.voice.Account
import com.kudcrafts.kudkeys.voice.SyncClient
import com.kudcrafts.kudkeys.voice.VoiceSettings
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.kudkeys.sync.KudLearnedWords
import helium314.keyboard.kudkeys.sync.KudSync
import helium314.keyboard.kudkeys.sync.KudSyncStore
import helium314.keyboard.kudkeys.sync.KudSyncedDicts
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.screens.KudBackupsScreen
import helium314.keyboard.settings.screens.KudProfileScreen
import helium314.keyboard.settings.screens.KudSyncSection
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

/** Account → Sync and the Backups list, to PNGs in `$TMPDIR/kudkeys-v04-shots`. The server is a MockWebServer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [ShadowInputMethodManager2::class], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
class KudV04ScreenshotTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val outDir = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"), "kudkeys-v04-shots")
    private val voicePrefs = latinIME.getSharedPreferences(VoiceSettings.PREFS, Context.MODE_PRIVATE)
    private val settings = VoiceSettings(voicePrefs)
    private val server = MockWebServer()
    private val store = KudSyncStore(File(latinIME.cacheDir, "v04-shots").also { it.deleteRecursively() })
    private val now = System.currentTimeMillis()
    private var backups = "[]"

    init {
        latinIME.prefs().edit().clear().commit()
        voicePrefs.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(latinIME)
        Settings.getInstance().loadSettings(latinIME, Locale.US, InputAttributes(EditorInfo(), false, latinIME.packageName))
        outDir.mkdirs()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/api/me" -> json("""{"id":"u1","email":"hammas.hamzah@gmail.com","name":"Hammas","image":null,
                    "voiceKeywords":["Fenno","TypeMap","Kudcrafts"],"languages":["id","en"]}""")
                "/api/me/usage" -> json("""{"week":{"sessions":12,"audioMinutes":8.4},"month":{"sessions":40,"audioMinutes":31.2},"sessions":[]}""")
                "/api/backups" -> json(backups)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        settings.serverUrl = server.url("/v1/stream").toString().replace("http://", "ws://")
        settings.signedIn("tok.sig", "Hammas", "hammas.hamzah@gmail.com")
    }

    @After fun tearDown() = server.shutdown()

    private fun sync() = KudSync(
        latinIME, settings, SyncClient({ settings.apiBase }), store,
        learned = KudLearnedWords { emptyMap() }, dicts = KudSyncedDicts { _, _ -> }, onSignedOut = {},
    )

    private fun iso(msAgo: Long) = java.time.Instant.ofEpochMilli(now - msAgo).toString()

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
        repeat(20) { layout(); Thread.sleep(50) } // let the answer arrive and draw
        val bitmap = Bitmap.createBitmap(dm.widthPixels, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun profileWithSync() {
        store.update { it.copy(lastSyncAt = now - 5 * 60_000, lastBackupAt = now - 26 * 3_600_000, initialDone = true) }
        renderScreen("sync-0-profile", 2.4f, settle = { server.requestCount >= 2 }) {
            KudProfileScreen(Account(settings), {}, {}, {}, sync = sync())
        }
    }

    @Test fun syncSection() {
        store.update { it.copy(lastSyncAt = now - 5 * 60_000, lastBackupAt = now - 26 * 3_600_000) }
        renderScreen("sync-1-section", 0.6f) { SearchSettingsScreen({}, "Account", emptyList()) { Column { KudSyncSection(sync(), {}, {}) } } }
    }

    @Test fun syncSectionNeverAndError() {
        store.update { it.copy(lastSyncAt = 0, lastBackupAt = 0, lastError = KudSync.SYNC_ERROR, autoBackup = false) }
        renderScreen("sync-2-section-error", 0.6f) { SearchSettingsScreen({}, "Account", emptyList()) { Column { KudSyncSection(sync(), {}, {}) } } }
    }

    @Test fun backupsList() {
        backups = """[
            {"id":"b3","createdAt":"${iso(2 * 3_600_000)}","deviceName":"Pixel 8","kind":"manual","sizeBytes":1843200},
            {"id":"b2","createdAt":"${iso(26 * 3_600_000)}","deviceName":"Pixel 8","kind":"auto","sizeBytes":1790000},
            {"id":"b1","createdAt":"${iso(50 * 3_600_000)}","deviceName":"Galaxy A54","kind":"auto","sizeBytes":412000}]"""
        warmUp()
        renderScreen("backups-0-list", settle = { server.requestCount >= 2 }) { KudBackupsScreen({}, sync()) }
    }

    @Test fun backupsEmpty() {
        warmUp()
        renderScreen("backups-1-empty", settle = { server.requestCount >= 2 }) { KudBackupsScreen({}, sync()) }
    }

    /** The first HTTP call in a sandbox is slow; make it before rendering so the shot shows the loaded list. */
    private fun warmUp() { kotlinx.coroutines.runBlocking { sync().listBackups() } }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
