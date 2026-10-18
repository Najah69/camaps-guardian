package com.najah.camapsguardian.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.najah.camapsguardian.core.Logbook
import com.najah.camapsguardian.core.Notifier
import com.najah.camapsguardian.core.Status

class MainActivity : ComponentActivity() {

    private var askedThisSession = false

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            Status.refresh(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannels(this)
        Logbook.init(this)
        setContent {
            GuardianTheme {
                GuardianScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Status.refresh(this)
        requestCorePermissions()
    }

    /** Les seules autorisations « runtime » utiles : Bluetooth (31+) et notifications (33+). */
    private fun requestCorePermissions() {
        if (askedThisSession) return
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) wanted += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            askedThisSession = true
            permissions.launch(missing.toTypedArray())
        }
    }
}
