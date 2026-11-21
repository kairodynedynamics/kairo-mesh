package com.kairodyne.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the mesh after the phone reboots, so it carries calls (and resends the owner's unconfirmed one) without the
 * user having to open the app.
 */
class BootAlici : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // without "Nearby devices" the service still runs: a phone with internet sends its pending call
        runCatching { context.startForegroundService(Intent(context, MeshService::class.java)) }
    }
}
