package com.kairodyne.mesh

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Starts the mesh after the phone reboots, so it carries calls (and resends the owner's unconfirmed one) without the
 * user having to open the app. Only once the Bluetooth permissions were granted in the app.
 */
class BootAlici : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val izin = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (izin) runCatching { context.startForegroundService(Intent(context, MeshService::class.java)) }
    }
}
