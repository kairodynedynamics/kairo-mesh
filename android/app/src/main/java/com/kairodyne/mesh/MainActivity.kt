package com.kairodyne.mesh

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kairodyne.mesh.core.Identity
import com.kairodyne.mesh.core.Node

/**
 * A minimal chat screen over the mesh. Test automation drives the same actions through intent extras:
 *   adb shell am start -n com.kairodyne.mesh/.MainActivity --es add "bora=<pairing code>"
 *   adb shell am start -n com.kairodyne.mesh/.MainActivity --es send "bora=hello"
 * and reads the "Kairo" log tag: READY, PAIRED, QUEUED, RECV, STATUS lines.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var node: Node
    private lateinit var transport: BleTransport
    private lateinit var log: TextView
    private var started = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // shortcut: the private key sits in app-private storage, unencrypted; move it to the Android Keystore before release.
        val prefs = getSharedPreferences("kairo", MODE_PRIVATE)
        val secret = prefs.getString("secret", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        val identity = Identity.create(secret)
        if (secret == null) prefs.edit().putString("secret", Base64.encodeToString(identity.secret, Base64.NO_WRAP)).apply()

        transport = BleTransport(this) { frame -> synchronized(this) { node.receive(frame) } }
        val delivered = prefs.getString("delivered", "")!!.split(",").filter { it.isNotEmpty() }.map { it.substringBefore(":") to it.substringAfter(":") }
        node = Node(identity, transport::send, { from, text ->
            // persisted before showing, so a restart cannot be used to replay this message
            prefs.edit().putString("delivered", node.delivered.joinToString(",") { "${it.first}:${it.second}" }).apply()
            show("RECV from=$from text=$text")
        }, delivered = delivered)
        for ((name, code) in prefs.getStringSet("contacts", emptySet())!!.map { it.split("=", limit = 2) })
            node.addContact(name, Base64.decode(code, Base64.NO_WRAP))

        buildUi(Base64.encodeToString(identity.pub, Base64.NO_WRAP))
        show("READY code=${Base64.encodeToString(identity.pub, Base64.NO_WRAP)}")
        requestBluetooth()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        if (started) transport.stop()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun handle(intent: Intent) {
        intent.getStringExtra("add")?.let { arg -> arg.split("=", limit = 2).takeIf { it.size == 2 }?.let { addContact(it[0], it[1]) } }
        intent.getStringExtra("send")?.let { arg -> arg.split("=", limit = 2).takeIf { it.size == 2 }?.let { send(it[0], it[1]) } }
    }

    private fun addContact(name: String, code: String) {
        try {
            synchronized(this) { node.addContact(name, Base64.decode(code, Base64.NO_WRAP)) }
            val prefs = getSharedPreferences("kairo", MODE_PRIVATE)
            prefs.edit().putStringSet("contacts", prefs.getStringSet("contacts", emptySet())!! + "$name=$code").apply()
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
            synchronized(this@MainActivity) { node.tick() }
            Log.i(BleTransport.TAG, "STATUS peers=${transport.peerCount} carrying=${synchronized(this@MainActivity) { node.carrying }}")
            main.postDelayed(this, 2000)
        }
    }

    private fun requestBluetooth() {
        val needed = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        if (needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startMesh()
        else requestPermissions(needed, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) startMesh()
        else show("ERROR Bluetooth permission denied; the mesh cannot run")
    }

    private fun startMesh() {
        if (started) return
        started = true
        transport.start()
        main.post(ticker)
    }

    private fun show(line: String) {
        Log.i(BleTransport.TAG, line)
        main.post { log.append(line + "\n") }
    }

    private fun buildUi(code: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        fun field(hint: String) = EditText(this).apply { this.hint = hint; isSingleLine = true }
        root.addView(TextView(this).apply { text = "Kairo Mesh"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })
        root.addView(TextView(this).apply { text = "Your pairing code (tap to copy):\n$code"; setTextIsSelectable(true)
            setOnClickListener { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Kairo code", code)) } })
        val name = field("Contact name")
        val pairing = field("Their pairing code")
        root.addView(name); root.addView(pairing)
        root.addView(Button(this).apply { text = "Add contact"; setOnClickListener { addContact(name.text.toString().trim(), pairing.text.toString().trim()) } })
        val to = field("Send to (contact name)")
        val message = field("Message")
        root.addView(to); root.addView(message)
        root.addView(Button(this).apply { text = "Send"; setOnClickListener { send(to.text.toString().trim(), message.text.toString()); message.text.clear() } })
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root, android.view.ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
}
