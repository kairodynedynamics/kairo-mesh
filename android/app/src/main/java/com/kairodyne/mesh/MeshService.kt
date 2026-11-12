package com.kairodyne.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.kairodyne.mesh.core.Node

/**
 * Runs the mesh as a foreground service so the phone keeps carrying mail with the screen off or the app closed.
 * Takes the same "add" and "send" extras as MainActivity, which forwards them here.
 */
class MeshService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var profile: Profile
    private lateinit var node: Node
    private lateinit var transport: BleTransport

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Mesh", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Kairo Mesh").setContentText("Carrying messages for nearby contacts").setOngoing(true).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        profile = Profile(this)
        transport = BleTransport(this) { frame -> synchronized(this) { node.receive(frame) } }
        node = Node(profile.identity, transport::send, { from, text ->
            profile.delivered = node.delivered // on disk before it is shown, so a restart cannot be used to replay it
            show("RECV from=$from text=$text")
        }, delivered = profile.delivered)
        for ((name, pub) in profile.contacts) node.addContact(name, pub)
        show("READY code=${profile.code}")
        // a newly met phone is greeted at once, but never more often than the node's 2-second budget allows
        transport.onPeer = { main.post { if (System.currentTimeMillis() - lastTick >= BUSY_MS) { main.removeCallbacks(ticker); ticker.run() } } }
        transport.start()
        main.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("add")?.split("=", limit = 2)?.takeIf { it.size == 2 }?.let { addContact(it[0], it[1]) }
        intent?.getStringExtra("send")?.split("=", limit = 2)?.takeIf { it.size == 2 }?.let { send(it[0], it[1]) }
        return START_STICKY
    }

    override fun onDestroy() {
        transport.stop()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun addContact(name: String, code: String) {
        try {
            synchronized(this) { node.addContact(name, Base64.decode(code, Base64.NO_WRAP)) }
            profile.addContact(name, code)
            show("PAIRED $name")
        } catch (e: IllegalArgumentException) { show("ERROR bad pairing code for $name") }
    }

    private fun send(name: String, text: String) {
        try {
            synchronized(this) { node.sendMessage(name, text) }
            show("QUEUED to=$name")
        } catch (e: RuntimeException) { show("ERROR ${e.message}") }
    }

    private var lastTick = 0L

    private val ticker = object : Runnable {
        override fun run() {
            lastTick = System.currentTimeMillis()
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.let { 100 * it.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } ?: 100
            val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val power = getSystemService(PowerManager::class.java)
            // Carrying for others is a favour: it stops when the owner needs the battery, and resumes on the charger.
            val relay = charging || (!power.isPowerSaveMode && level > LOW_BATTERY)
            transport.setLowPower(!charging && (!power.isInteractive || !relay))
            val carrying = synchronized(this@MeshService) { node.relay = relay; node.tick(); node.carrying }
            val peers = transport.peerCount
            // HELLOs only matter to phones in range, and only often while there is mail to offer them
            val next = if (peers > 0 && carrying > 0) BUSY_MS else IDLE_MS
            Log.i(BleTransport.TAG, "STATUS peers=$peers carrying=$carrying relay=$relay battery=$level next=${next / 1000}s")
            main.postDelayed(this, next)
        }
    }

    private fun show(line: String) {
        Log.i(BleTransport.TAG, line)
        main.post { lines += line; listener?.invoke(line) }
    }

    companion object {
        private const val CHANNEL = "mesh"
        private const val BUSY_MS = 2000L
        private const val IDLE_MS = 10_000L
        private const val LOW_BATTERY = 20 // percent
        /** Log lines for the screen; touched only on the main thread. */
        val lines = mutableListOf<String>()
        var listener: ((String) -> Unit)? = null
    }
}
