package com.kairodyne.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
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

    private val ticker = object : Runnable {
        override fun run() {
            synchronized(this@MeshService) { node.tick() }
            Log.i(BleTransport.TAG, "STATUS peers=${transport.peerCount} carrying=${synchronized(this@MeshService) { node.carrying }}")
            main.postDelayed(this, 2000)
        }
    }

    private fun show(line: String) {
        Log.i(BleTransport.TAG, line)
        main.post { lines += line; listener?.invoke(line) }
    }

    companion object {
        private const val CHANNEL = "mesh"
        /** Log lines for the screen; touched only on the main thread. */
        val lines = mutableListOf<String>()
        var listener: ((String) -> Unit)? = null
    }
}
