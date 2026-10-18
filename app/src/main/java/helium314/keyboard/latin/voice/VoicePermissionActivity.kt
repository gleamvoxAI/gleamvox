// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle

/**
 * Invisible activity that only shows the system dialog for the microphone permission (a keyboard can't show it
 * itself). Not exported, started only by the keyboard. Listening does not start by itself afterwards.
 */
class VoicePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // stage 5g-25: also "Nearby devices" (BLUETOOTH_CONNECT), to tell the car radio from a watch; nothing else
        val permission = intent?.getStringExtra(EXTRA_PERMISSION)?.takeIf { it in ALLOWED } ?: Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        if (savedInstanceState == null)
            requestPermissions(arrayOf(permission), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 1
        const val EXTRA_PERMISSION = "permission"
        private val ALLOWED = setOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
    }
}
