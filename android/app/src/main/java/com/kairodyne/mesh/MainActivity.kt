package com.kairodyne.mesh

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A minimal chat screen over the mesh, which runs in MeshService. Test automation drives the same actions through intent extras:
 *   adb shell am start -n com.kairodyne.mesh/.MainActivity --es add "bora=<pairing code>"
 *   adb shell am start -n com.kairodyne.mesh/.MainActivity --es send "bora=hello"
 * and reads the "Kairo" log tag: READY, PAIRED, QUEUED, RECV, STATUS lines.
 */
class MainActivity : Activity() {
    private lateinit var log: TextView
    private var waiting: Intent? = null // extras that arrived before Bluetooth permission was granted

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        buildUi(Profile(this).code)
        MeshService.lines.forEach { log.append(it + "\n") }
        MeshService.listener = { log.append(it + "\n") }
        waiting = intent
        requestBluetooth()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        toService(intent)
    }

    override fun onDestroy() {
        MeshService.listener = null
        super.onDestroy()
    }

    private fun granted() = BLUETOOTH.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun toService(extras: Intent? = null) {
        if (!granted()) { waiting = extras; return }
        startForegroundService(Intent(this, MeshService::class.java).apply { extras?.extras?.let(::putExtras) })
    }

    private fun requestBluetooth() {
        if (granted()) toService(waiting)
        // notifications are optional: without them the service still runs, its notice is just hidden
        else requestPermissions(BLUETOOTH + Manifest.permission.POST_NOTIFICATIONS, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (granted()) toService(waiting)
        else log.append("ERROR Bluetooth permission denied; the mesh cannot run\n")
    }

    private fun buildUi(code: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        fun field(hint: String) = EditText(this).apply { this.hint = hint; isSingleLine = true }
        fun extra(key: String, value: String) = toService(Intent().putExtra(key, value))
        root.addView(TextView(this).apply { text = "Kairo Mesh"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })
        root.addView(TextView(this).apply { text = "Your pairing code (tap to copy):\n$code"; setTextIsSelectable(true)
            setOnClickListener { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Kairo code", code)) } })
        val name = field("Contact name")
        val pairing = field("Their pairing code")
        root.addView(name); root.addView(pairing)
        root.addView(Button(this).apply { text = "Add contact"; setOnClickListener { extra("add", "${name.text.toString().trim()}=${pairing.text.toString().trim()}") } })
        val to = field("Send to (contact name)")
        val message = field("Message")
        root.addView(to); root.addView(message)
        root.addView(Button(this).apply { text = "Send"; setOnClickListener { extra("send", "${to.text.toString().trim()}=${message.text}"); message.text.clear() } })
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root, android.view.ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private companion object {
        val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    }
}
