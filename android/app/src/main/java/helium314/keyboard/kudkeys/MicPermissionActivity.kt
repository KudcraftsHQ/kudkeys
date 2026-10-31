// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import com.kudcrafts.kudkeys.voice.VoiceSettings
import com.kudcrafts.kudkeys.voice.hasMicPermission

/** Invisible activity that asks for the microphone, because a keyboard can't show the system dialog itself. */
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasMicPermission(this)) return finish()
        if (savedInstanceState == null)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasMicPermission(this)) {
            setBlocked(this, false)
            Toast.makeText(this, "Microphone on. Hold Mic to talk", Toast.LENGTH_SHORT).show()
        } else if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            // Denied with "don't ask again" (or by policy): the system won't show the dialog any more, so the
            // keyboard shows "Microphone blocked — tap to open settings" instead of opening this activity again.
            setBlocked(this, true)
        }
        finish()
    }

    companion object {
        private const val REQUEST = 1
        private const val KEY_BLOCKED = "mic_blocked"

        fun start(context: Context) {
            context.startActivity(Intent(context, MicPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }

        private fun prefs(context: Context) = context.getSharedPreferences(VoiceSettings.PREFS, Context.MODE_PRIVATE)

        /** The user denied the mic for good; cleared once it is granted. */
        fun isBlocked(context: Context) = prefs(context).getBoolean(KEY_BLOCKED, false)

        fun setBlocked(context: Context, blocked: Boolean) = prefs(context).edit { putBoolean(KEY_BLOCKED, blocked) }

        /** This app's page in system settings (Permissions → Microphone). */
        fun openAppSettings(context: Context) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
