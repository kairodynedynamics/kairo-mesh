package com.kairodyne.mesh

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import com.kairodyne.mesh.core.Node
import com.kairodyne.mesh.core.Triyaj
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs the mesh as a foreground service so the phone keeps carrying mail with the screen off or the app closed.
 * Takes the "add", "send", "kurum" and "acil" extras, which MainActivity forwards here.
 *
 * Emergency relay: with an authority code set, the phone carries everyone's emergency calls, and whenever it has a
 * validated internet connection it uploads them to the authority in the background and fetches the authority's
 * signed announcements to pass on. Uploads and fetches run on worker threads; their results come back under the
 * same lock as every other call into the node.
 */
class MeshService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var profile: Profile
    private lateinit var node: Node
    private lateinit var transport: BleTransport
    private var kurum: Kurum? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Mesh", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Kairo Mesh").setContentText("Yakındaki telefonlar için mesaj taşıyor").setOngoing(true).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        profile = Profile(this)
        transport = BleTransport(this) { frame -> synchronized(this) { node.receive(frame) } }
        buildNode()
        show("READY code=${profile.code}")
        kurum?.let { show("KURUM ${it.url}") }
        // a newly met phone is greeted at once, but never more often than the node's 2-second budget allows
        transport.onPeer = { main.post { if (System.currentTimeMillis() - lastTick >= BUSY_MS) { main.removeCallbacks(ticker); ticker.run() } } }
        transport.start()
        main.post(ticker)
    }

    /** (Re)creates the node, e.g. when the authority code changes. Mail queued in memory is not kept across it. */
    private fun buildNode() = synchronized(this) {
        kurum = profile.kurum?.let(Kurum::parse)
        val k = kurum
        node = Node(profile.identity, transport::send, { from, text ->
            profile.delivered = node.delivered // on disk before it is shown, so a restart cannot be used to replay it
            show("RECV from=$from text=$text")
        }, delivered = profile.delivered,
            authority = k?.pub, online = ::online,
            uplink = k?.let { kk -> { env: ByteArray, done: (ByteArray?) -> Unit -> kk.upload(env) { r -> synchronized(this) { done(r) } } } },
            onEmergencyDelivered = { id -> profile.bekleyenAcil = null; show("ACIL_ULASTI id=$id saat=${saat()}") },
            authoritySign = k?.signPub,
            downlink = k?.let { kk -> { done: (List<ByteArray>) -> Unit -> kk.announcements { f -> synchronized(this) { done(f) } } } },
            onAnnouncement = { n -> show("DUYURU ${n.text}") })
        for ((name, pub) in profile.contacts) node.addContact(name, pub)
        // A call the authority has not confirmed survives a restart of the app or the phone: it is sent again.
        // The authority may then receive it twice (the old envelope may still be on its way); a duplicate costs less
        // than a lost call.
        val bekleyen = profile.bekleyenAcil
        if (k != null && bekleyen != null) Thread {
            try {
                val id = synchronized(this) { node.sendEmergency(bekleyen) }
                show("ACIL_YENIDEN id=$id saat=${saat()}")
            } catch (e: RuntimeException) { show("ERROR ${e.message}") }
        }.start()
    }

    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("add")?.split("=", limit = 2)?.takeIf { it.size == 2 }?.let { addContact(it[0], it[1]) }
        intent?.getStringExtra("send")?.split("=", limit = 2)?.takeIf { it.size == 2 }?.let { send(it[0], it[1]) }
        intent?.getStringExtra("kurum")?.let(::setKurum)
        intent?.getStringExtra("acil")?.let(::emergency)
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

    private fun setKurum(code: String) {
        val k = Kurum.parse(code) ?: return show("ERROR kurum kodu geçersiz")
        profile.kurum = k.code
        buildNode()
        show("KURUM ${k.url}")
    }

    private val triyaj by lazy {
        runCatching { Triyaj(assets.open("triyaj.json").use { it.readBytes().toString(Charsets.UTF_8) }, assets.open("triyaj.bin").use { it.readBytes() }) }
            .onFailure { show("ERROR triyaj modeli yüklenemedi: ${it.message}") }.getOrNull()
    }

    /**
     * `form`: "tur|kisi|ozel1,ozel2|metin" from the emergency screen; `tur` may be empty when only text was written.
     * What the caller tapped always wins; the on-device model fills in what is missing from the text (type, number
     * of people, address, who is present). Location is added here, if permitted.
     */
    private fun emergency(form: String) {
        val p = form.split("|", limit = 4)
        if (p.size < 4 || (p[0] !in Cagri.TURLER && p[3].isBlank())) return show("ERROR acil formu eksik")
        if (kurum == null) return show("ERROR kurum kodu yok: çağrının gideceği kurum tanımlı değil")
        val loc = lastLocation()
        // reading the text and the proof of work take a moment: off the main thread, then queued under the node lock
        Thread {
            try {
                val t0 = System.nanoTime()
                val k = if (p[3].isNotBlank()) triyaj?.coz(p[3]) else null
                if (k != null) show("TRIYAJ tur=${k.tur} aciliyet=${k.aciliyet} kisi=${k.kisi} il=${k.il} ms=${(System.nanoTime() - t0) / 1_000_000}")
                // an unrecognised or off-topic message still goes out as a call for rescue: missing a call costs more
                val tur = p[0].takeIf { it in Cagri.TURLER } ?: k?.tur?.takeIf { it in Cagri.TURLER } ?: "enkaz"
                val ozel = p[2].split(",").filter { it in Cagri.OZEL }.toSet() + (k?.ozel?.filter { it in Cagri.OZEL } ?: emptyList())
                val json = Cagri.rapor(tur, p[1].toIntOrNull() ?: k?.kisi, ozel, p[3],
                    loc?.latitude, loc?.longitude, loc?.accuracy, loc?.time ?: System.currentTimeMillis(),
                    adres = k?.let { mapOf("il" to it.il, "ilce" to it.ilce, "mahalle" to it.mahalle, "sokak" to it.sokak, "bina" to it.bina) } ?: emptyMap(),
                    // A call typed on the emergency screen is a call for help: the model fills in the details but
                    // may not rank it below 2. On unseen phrasings it misses about 13% of life-threatening calls
                    // (triyaj/model/olcum.json); a needless "urgent" costs the dispatcher less than a missed one.
                    aciliyet = k?.aciliyet?.let { if (p[0] !in Cagri.TURLER) minOf(it, 2) else it },
                    model = k != null && p[0] !in Cagri.TURLER)
                val id = synchronized(this) { node.sendEmergency(json) }
                profile.bekleyenAcil = json // on disk before the screen says it was sent
                show("ACIL_GONDERILDI id=$id konum=${if (loc != null) "var" else "yok"} saat=${saat()}")
            } catch (e: RuntimeException) { show("ERROR ${e.message}") }
        }.start()
    }

    private fun lastLocation(): Location? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = getSystemService(LocationManager::class.java)
        // GPS works without a network; the freshest fix from any provider is used
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        } catch (e: SecurityException) { null }
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
            Log.i(BleTransport.TAG, "STATUS peers=$peers carrying=$carrying relay=$relay battery=$level online=${online()} next=${next / 1000}s")
            main.postDelayed(this, next)
        }
    }

    private fun saat() = SimpleDateFormat("HH:mm", Locale("tr")).format(Date())

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
