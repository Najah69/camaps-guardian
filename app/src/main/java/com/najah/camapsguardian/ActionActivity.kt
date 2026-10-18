package com.najah.camapsguardian

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.najah.camapsguardian.core.CamapsCtl
import com.najah.camapsguardian.core.Logbook
import com.najah.camapsguardian.core.Sys

/**
 * Écran éclair déclenché par la notification plein écran (Bluetooth éteint) ou par
 * l'action « Relancer CamAPS FX ». Il ne s'affiche que lorsque le téléphone le demande.
 */
class ActionActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_BT = "bt"
        const val MODE_APP = "app"
    }

    private val btPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                requestBluetoothEnable()
            } else {
                toast("Permission Bluetooth refusée")
                finish()
            }
        }

    private val btEnable =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val ok = result.resultCode == Activity.RESULT_OK
            Logbook.add(
                "action",
                "activation Bluetooth ${if (ok) "accordée" else "refusée"} par l'utilisateur"
            )
            if (ok) relaunch() else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Logbook.init(this)
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_APP -> {
                Logbook.add("action", "écran d'action : relance CamAPS FX")
                relaunch()
            }
            else -> {
                Logbook.add("action", "écran d'action : rallumage Bluetooth")
                askBluetooth()
            }
        }
    }

    private fun askBluetooth() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            toast("Pas de Bluetooth sur cet appareil")
            finish()
            return
        }
        if (adapter.isEnabled) {
            relaunch()
            return
        }
        if (!Sys.hasBtPermission(this)) {
            btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        requestBluetoothEnable()
    }

    private fun requestBluetoothEnable() {
        try {
            btEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (t: Throwable) {
            Logbook.add("action", "dialogue Bluetooth impossible : ${t.message}")
            runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            finish()
        }
    }

    private fun relaunch() {
        val cam = CamapsCtl(this)
        val target = cam.installed().firstOrNull()
        if (target == null) {
            toast("CamAPS FX introuvable")
            finish()
            return
        }
        Thread {
            val how = cam.launch(target)
            Logbook.add("action", "relance CamAPS : $how")
        }.start()
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
