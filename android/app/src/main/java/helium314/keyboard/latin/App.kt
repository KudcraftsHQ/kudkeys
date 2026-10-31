// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
package helium314.keyboard.latin

import android.app.Application
import androidx.work.Configuration
import android.os.Build
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.upgradeToolbarPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application(), Configuration.Provider {
    // WorkManager initialises on demand (first getInstance), never at process start: see AndroidManifest.xml.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        DebugFlags.init(this)
        // kudkeys: names this device on the web app's Devices page
        com.kudcrafts.kudkeys.voice.VoiceSettings.userAgent =
            "KudKeys/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})"
        FoldableUtils.init(this)
        Settings.init(this)
        SubtypeSettings.init(this)
        // kudkeys: periodic sync and backup while signed in (v0.4); no-op when signed out
        helium314.keyboard.kudkeys.sync.KudSyncWork.ensureScheduled(this)

        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { // do some uncritical work in background for faster startup
            SupportedEmojis.load(this@App)
            LayoutUtilsCustom.removeMissingLayouts(this@App)
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            Log.i(
                "startup", "Starting ${applicationInfo.processName} version ${packageInfo.versionName} (${
                    packageInfo.versionCode
                }) on Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            )
        }

        RichInputMethodManager.init(this)
        checkVersionUpgrade(this)
        if (BuildConfig.DEBUG) // do this on every debug apk start because we may work on adding a new toolbar key
            upgradeToolbarPrefs(prefs())
        app = this
        Defaults.initDynamicDefaults(this)
    }

    companion object {
        // used so JniUtils can access application once
        private var app: App? = null
        fun getApp(): App? {
            val application = app
            app = null
            return application
        }
    }
}
