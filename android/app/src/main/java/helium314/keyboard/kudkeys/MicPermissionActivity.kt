// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
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
        if (hasMicPermission(this)) Toast.makeText(this, "Microphone on. Hold Mic to talk", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val REQUEST = 1

        fun start(context: Context) {
            context.startActivity(Intent(context, MicPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }
}
