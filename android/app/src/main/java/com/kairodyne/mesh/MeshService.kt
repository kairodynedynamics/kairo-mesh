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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import com.kairodyne.mesh.core.Kodlayici
import org.json.JSONArray
import org.json.JSONObject
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
        // connectedDevice may start before the Bluetooth permission (CHANGE_NETWORK_STATE also qualifies): a phone
        // with internet sends its own call even if "Nearby devices" was refused
        try {
            startForeground(1, bildirim(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Android 12+ may refuse a start from the background (the Doze alarm after the process was killed): no
            // crash; the next opening of the app, the next alarm or a reboot starts it again
            Log.w(BleTransport.TAG, "ön plan hizmeti başlatılamadı: ${e.message}")
            stopSelf()
            return
        }
        hazir = true

        profile = Profile(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(UYARI, "Kurum ve çağrı", NotificationManager.IMPORTANCE_HIGH))
        transport = BleTransport(this) { frame -> synchronized(this) { node.receive(frame) } }
        transport.onDurum = { acik -> show(if (acik) "BLUETOOTH_ACIK" else "BLUETOOTH_KAPALI") }
        buildNode()
        show("READY code=${profile.code}")
        kurum?.let { show("KURUM ${it.url} ad=${it.ad}") }
        // a newly met phone is greeted at once, but never more often than the node's 2-second budget allows
        transport.onPeer = { main.post { if (System.currentTimeMillis() - lastTick >= BUSY_MS) { main.removeCallbacks(ticker); ticker.run() } } }
        bluetoothBaslat()
        main.post(ticker)
        Thread { triyaj }.start() // ~2 s on a slow phone: loaded now, not while a caller waits
        // a call that was being prepared when the app died goes out now; once only (if preparing it is what kills
        // the app, it must not crash for ever), and only if it is recent (never an old form as a new call)
        profile.bekleyenForm?.let { f ->
            if (!profile.formTekrarlandi && System.currentTimeMillis() - profile.bekleyenFormZaman < 3_600_000L) {
                profile.formTekrarlandi = true
                emergency(f, tekrar = true)
            } else profile.bekleyenForm = null
        }
    }

    private var hazir = false

    private fun bildirim() = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle("KairoMesh").setContentText("Acil çağrılar için hazır").setOngoing(true).build()

    private var bluetoothBasladi = false

    private fun bluetoothBaslat() {
        if (bluetoothBasladi) return
        val izin = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (!izin) return show("BLUETOOTH_IZNI_YOK")
        bluetoothBasladi = true
        transport.start()
    }

    // AtomicFile: fsync before the rename, so a battery dying mid-write leaves the previous copy, never an empty one
    private val kayitDosyasi by lazy { android.util.AtomicFile(java.io.File(filesDir, "tasinan.txt")) }
    private val yazici = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var sonKayitIzi = ""

    /** Writes what the node carries when it changed (see Node.kayit), off the main thread. Call under the node lock. */
    private fun kaydet() {
        val iz = node.kayitIzi
        if (iz == sonKayitIzi) return
        sonKayitIzi = iz
        val metin = node.kayit().toByteArray()
        val yaz = Runnable {
            val akis = runCatching { kayitDosyasi.startWrite() }.getOrNull()
            if (akis == null) { sonKayitIzi = ""; return@Runnable } // written again on the next tick
            try { akis.write(metin); kayitDosyasi.finishWrite(akis) }
            catch (e: java.io.IOException) { kayitDosyasi.failWrite(akis); sonKayitIzi = ""; Log.w(BleTransport.TAG, "kayıt yazılamadı: ${e.message}") }
        }
        // the service is shutting down: written here, now, rather than lost with the call being prepared
        try { yazici.execute(yaz) } catch (e: java.util.concurrent.RejectedExecutionException) { yaz.run() }
    }

    /** (Re)creates the node, e.g. when the authority code changes, with what was carried before. */
    private fun buildNode() = synchronized(this) {
        // from the old node itself if there is one (the file may still be being written), else from disk
        val onceki = if (::node.isInitialized) node.kayit() else runCatching { String(kayitDosyasi.readFully()) }.getOrNull()
        kurum = profile.kurum?.let(Kurum::parse)
        val k = kurum
        node = Node(profile.identity, transport::send, { from, text ->
            profile.delivered = node.delivered // on disk before it is shown, so a restart cannot be used to replay it
            show("RECV from=$from text=$text")
        }, delivered = profile.delivered,
            authority = k?.pub, online = ::online,
            uplink = k?.let { kk -> { env: ByteArray, done: (ByteArray?) -> Unit -> kk.upload(env) { r -> synchronized(this) { done(r) } } } },
            onEmergencyDelivered = { id ->
                // only a receipt for this caller's pending call says "the authority has your call"; a location
                // follow-up, a correction or an older call arriving is not that
                if (id in profile.bekleyenIdler) {
                    profile.bekleyenAcil = null
                    show("ACIL_ULASTI id=$id saat=${saat()}")
                    bildir("Kurum çağrınızı aldı", "Yardım çağrınız ${saat()} itibarıyla kurumda.")
                } else show("EK_ULASTI id=$id saat=${saat()}")
            },
            authoritySign = k?.signPub,
            downlink = k?.let { kk -> { done: (List<ByteArray>) -> Unit -> kk.announcements { f -> synchronized(this) { done(f) } } } },
            onAnnouncement = { n -> show("DUYURU ${n.text}"); bildir("Kurum duyurusu", n.text) })
        for ((name, pub) in profile.contacts) node.addContact(name, pub)
        onceki?.let { runCatching { node.yukle(it) } }
        sonKayitIzi = ""
        kaydet()
        // A call the authority has not confirmed survives a restart of the app or the phone: its envelope comes back
        // from disk, or, if that is gone, it is sent again.
        if (k != null && profile.bekleyenAcil != null && node.kendiAcil == 0) yenidenGonder()
    }

    /**
     * Sends the pending call again. Runs after a restart, and every [YENIDEN_MS] while unconfirmed: the mesh drops an
     * envelope after a day, and the caller must not be left believing a call is on its way when it is not. The
     * authority may receive it twice; a duplicate costs less than a lost call.
     */
    private fun yenidenGonder(satir: String = "ACIL_YENIDEN") {
        val bekleyen = profile.bekleyenAcil ?: return
        profile.bekleyenZaman = System.currentTimeMillis()
        Thread {
            try {
                val id = synchronized(this) { node.sendEmergency(bekleyen) }
                profile.bekleyenIdEkle(id)
                show("$satir id=$id saat=${saat()}")
            } catch (e: RuntimeException) { show("ERROR ${e.message}") }
        }.start()
    }

    private fun bildir(baslik: String, metin: String) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val ac = android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify((baslik + metin).hashCode(),
            Notification.Builder(this, UYARI).setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle(baslik)
                .setContentText(metin).setStyle(Notification.BigTextStyle().bigText(metin)).setContentIntent(ac).setAutoCancel(true).build())
    }

    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hazir) return START_NOT_STICKY
        intent?.getStringExtra("add")?.split("=", limit = 2)?.takeIf { it.size == 2 }?.let { addContact(it[0], it[1]) }
        intent?.getStringExtra("send")?.split("=", limit = 2)?.takeIf { it.size == 2 }?.let { send(it[0], it[1]) }
        intent?.getStringExtra("kurum")?.let(::setKurum)
        intent?.getStringExtra("acil")?.let { emergency(it) }
        intent?.getStringExtra("iptal")?.let(::duzelt)
        if (intent?.hasExtra("geriAl") == true) geriAl()
        bluetoothBaslat() // the permission may have been granted since
        if (intent?.hasExtra("uyan") == true) {
            // the alarm below: the CPU stays up long enough for a round of greetings, uploads and the resend check
            getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kairo:uyan").acquire(15_000)
            main.post { main.removeCallbacks(ticker); ticker.run() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (hazir) { synchronized(this) { kaydet() }; transport.stop() }
        yazici.shutdown() // what is queued is still written
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
        val k = Kurum.parse(code) ?: return show("ERROR kurum kodu geçersiz ya da yetkili makamca onaylanmamış")
        profile.kurum = k.code
        buildNode()
        show("KURUM ${k.url} ad=${k.ad}")
    }

    private val triyaj by lazy {
        fun metin(ad: String) = assets.open(ad).use { it.readBytes().toString(Charsets.UTF_8) }
        // the encoder (~38 MB in memory) makes the message-level decisions; if it cannot load, the character model
        // still reads the message on its own
        val kod = try { Kodlayici(metin("kodlayici.json"), assets.open("kodlayici.bin").use { it.readBytes() }, assets.open("kodlayici-kelime.bin").use { it.readBytes() }) }
            catch (e: Throwable) { show("ERROR kodlayıcı yüklenemedi, harf modeli kullanılıyor: ${e.message}"); null }
        runCatching { Triyaj(metin("triyaj.json"), assets.open("triyaj.bin").use { it.readBytes() }, kod) }
            .onFailure { show("ERROR triyaj modeli yüklenemedi: ${it.message}") }.getOrNull()
    }

    /**
     * `form`: "tur|kisi|ozel1,ozel2|metin" from the emergency screen; `tur` may be empty when only text was written.
     * What the caller tapped always wins; the on-device model fills in what is missing from the text (type, number
     * of people, address, who is present). Location is added here, if permitted.
     */
    private fun emergency(form: String, tekrar: Boolean = false) {
        val p = form.split("|", limit = 4)
        if (p.size < 4 || (p[0] !in Cagri.TURLER && p[3].isBlank())) return show("ERROR acil formu eksik")
        if (kurum == null) return show("ERROR kurum kodu yok: çağrının gideceği kurum tanımlı değil")
        // a second tap within a few seconds is the same call, not a new one
        if (System.currentTimeMillis() - sonCagri < 10_000) return show("ACIL_ZATEN id=- saat=${saat()}")
        sonCagri = System.currentTimeMillis()
        if (!tekrar) { profile.bekleyenForm = form; profile.formTekrarlandi = false }
        // reading the text, a fresh GPS fix and the proof of work take a moment: off the main thread, then queued
        // under the node lock
        Thread {
            try {
                show("KONUM_ALINIYOR")
                val loc = konum()
                val t0 = System.nanoTime()
                val k = if (p[3].isNotBlank()) triyaj?.coz(p[3]) else null
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (k != null) show("TRIYAJ tur=${k.tur} aciliyet=${k.aciliyet} kisi=${k.kisi} il=${k.il} ms=$ms")
                val secim = JSONObject().apply {
                    p[0].takeIf { it in Cagri.TURLER }?.let { put("tur", it) }
                    p[1].toIntOrNull()?.let { put("kisi", it) }
                    put("ozel", JSONArray(p[2].split(",").filter { it in Cagri.OZEL }))
                }
                val okuma = k?.let {
                    JSONObject().apply {
                        put("model", triyaj?.modelAdi); put("ms", ms); put("tur", it.tur); put("aciliyet", it.aciliyet)
                        it.kisi?.let { v -> put("kisi", v) }
                        for ((a, v) in listOf("il" to it.il, "ilce" to it.ilce, "mahalle" to it.mahalle, "sokak" to it.sokak, "bina" to it.bina))
                            if (!v.isNullOrBlank()) put(a, v)
                        put("ozel", JSONArray(it.ozel))
                    }
                }
                // a message the model could not place still goes out, as "another emergency" (urgency 2): missing a
                // call costs more than a needless one, but nonsense must not jump to the top as "under rubble"
                val tur = p[0].takeIf { it in Cagri.TURLER } ?: k?.tur?.takeIf { it in Cagri.TURLER } ?: "yardim"
                val ozel = p[2].split(",").filter { it in Cagri.OZEL }.toSet() + (k?.ozel?.filter { it in Cagri.OZEL } ?: emptyList())
                val json = Cagri.rapor(tur, p[1].toIntOrNull() ?: k?.kisi, ozel, p[3],
                    loc?.latitude, loc?.longitude, loc?.accuracy, loc?.time ?: System.currentTimeMillis(),
                    adres = k?.let { mapOf("il" to it.il, "ilce" to it.ilce, "mahalle" to it.mahalle, "sokak" to it.sokak, "bina" to it.bina) } ?: emptyMap(),
                    // A call typed on the emergency screen is a call for help: the model fills in the details but
                    // may not rank it below 2. The encoder still ranks about 3% of life-threatening calls too low
                    // (triyaj/model/kodlayici-olcum.json); a needless "urgent" costs the dispatcher less than a missed one.
                    aciliyet = k?.aciliyet?.let { if (p[0] !in Cagri.TURLER) minOf(it, 2) else it },
                    model = k != null && p[0] !in Cagri.TURLER, secim = secim, okuma = okuma, cihaz = cihaz())
                val id = synchronized(this) { node.sendEmergency(json).also { kaydet() } }
                // "I am safe" (or a new call) replaces the pending call: kept, so a mistaken tap can be undone
                val onceki = profile.bekleyenAcil.takeIf { tur == "guvende" }
                profile.oncekiAcil = onceki
                profile.bekleyenAcil = json // on disk before the screen says it was sent
                profile.bekleyenZaman = System.currentTimeMillis()
                profile.bekleyenIdEkle(id)
                profile.bekleyenForm = null
                konumTakip(loc)
                show("ACIL_GONDERILDI id=$id tur=$tur konum=${if (loc != null) "var" else "yok"} saat=${saat()}" +
                    (if (!transport.acik) " bluetooth=kapali" else "") + (if (onceki != null) " onceki=var" else ""))
            } catch (e: Throwable) { profile.bekleyenForm = null; show("ERROR çağrı gönderilemedi: ${e.message}") }
        }.start()
    }

    /** Undoes a correction or "I am safe": the call it replaced is pending again and goes out once more at once. */
    private fun geriAl() {
        val onceki = profile.oncekiAcil ?: return
        profile.oncekiAcil = null
        profile.bekleyenAcil = onceki
        yenidenGonder("GERI_ALINDI")
    }

    @Volatile private var sonCagri = 0L

    /** The caller sent a call by mistake, or no longer needs help: a correction for the earlier call [ref]. */
    private fun duzelt(ref: String) {
        if (kurum == null) return show("ERROR kurum kodu yok: çağrının gideceği kurum tanımlı değil")
        Thread {
            try {
                val id = synchronized(this) { node.sendEmergency(Cagri.duzeltme(ref, System.currentTimeMillis())).also { kaydet() } }
                val onceki = profile.bekleyenAcil
                profile.oncekiAcil = onceki
                profile.bekleyenAcil = null // the earlier call is no longer resent (unless the correction is undone)
                show("DUZELTME_GONDERILDI id=$id ref=$ref saat=${saat()}" + if (onceki != null) " onceki=var" else "")
            } catch (e: RuntimeException) { show("ERROR düzeltme gönderilemedi: ${e.message}") }
        }.start()
    }

    /**
     * A fresh fix when one comes within [KONUM_MS] (GPS works without a network; under rubble it may not come at all),
     * otherwise the freshest last known fix from any provider. Blocks: call off the main thread.
     */
    private fun konumIzni() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The sending phone, for the dispatcher: model, system, app version, battery. */
    private fun cihaz() = JSONObject().apply {
        put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim()); put("sistem", "Android ${Build.VERSION.RELEASE}")
        put("uygulama", BuildConfig.VERSION_NAME)
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
            put("pil", 100 * it.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
            put("sarj", it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }
    }

    /**
     * A call must never stay without a location. If it went with none, an old one or a coarse one, the phone keeps
     * listening to GPS for a few minutes and sends the first good fix as a follow-up the dashboard shows with the call.
     */
    private var konumDinleyici: android.location.LocationListener? = null

    private fun konumTakip(ilk: Location?) {
        if (!konumIzni()) return show("KONUM_IZNI_YOK")
        if (ilk == null) show("KONUM_YOK")
        val taze = ilk != null && System.currentTimeMillis() - ilk.time < 60_000 && ilk.hasAccuracy()
        if (taze && ilk!!.accuracy <= 50f) return
        // decided now, not when a fix arrives: only a clearly more precise fix is worth a follow-up (the same
        // network fix again and again was sent as "updates")
        val esik = if (taze) ilk!!.accuracy * 0.7f else Float.MAX_VALUE
        val lm = getSystemService(LocationManager::class.java)
        konumDinleyici?.let { lm.removeUpdates(it) } // a new call replaces the previous follow-up
        // a location service for these minutes: without it, locking the screen stops the updates. Started while the
        // caller is on the screen (the while-in-use permission allows it then); refused, the search goes on as far
        // as Android lets it.
        runCatching { startForeground(1, bildirim(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }
        val bitti = { runCatching { startForeground(1, bildirim(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) } }
        val dinleyici = object : android.location.LocationListener {
            override fun onLocationChanged(l: Location) {
                if (!l.hasAccuracy() || l.accuracy >= esik) return
                lm.removeUpdates(this)
                konumDinleyici = null
                bitti()
                Thread {
                    try {
                        synchronized(this@MeshService) { node.sendEmergency(Cagri.konumGuncel(l.latitude, l.longitude, l.accuracy, l.time)) }
                        show("KONUM_GUNCELLENDI saat=${saat()}")
                    } catch (e: RuntimeException) { show("ERROR konum güncellemesi gönderilemedi: ${e.message}") }
                }.start()
            }
        }
        try {
            for (s in listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER))
                if (runCatching { lm.isProviderEnabled(s) }.getOrDefault(false)) lm.requestLocationUpdates(s, 2000L, 0f, dinleyici, main.looper)
            konumDinleyici = dinleyici
            main.postDelayed({ lm.removeUpdates(dinleyici); if (konumDinleyici === dinleyici) { konumDinleyici = null; bitti() } }, KONUM_TAKIP_MS)
        } catch (e: SecurityException) { bitti(); show("KONUM_IZNI_YOK") }
    }

    private fun konum(): Location? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = getSystemService(LocationManager::class.java)
        val son = try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        } catch (e: SecurityException) { null }
        if (son != null && System.currentTimeMillis() - son.time < 60_000) return son
        val saglayici = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return son
        val bekle = java.util.concurrent.CountDownLatch(1)
        var taze: Location? = null
        val iptal = android.os.CancellationSignal()
        try {
            lm.getCurrentLocation(saglayici, iptal, java.util.concurrent.Executors.newSingleThreadExecutor()) { taze = it; bekle.countDown() }
            bekle.await(KONUM_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: SecurityException) { return son } finally { iptal.cancel() }
        return taze ?: son
    }

    private var lastTick = 0L
    private var sonAg = ""

    private val ticker = object : Runnable {
        override fun run() {
            lastTick = System.currentTimeMillis()
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.let { 100 * it.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } ?: 100
            val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val power = getSystemService(PowerManager::class.java)
            // Carrying for others is a favour: it stops when the owner needs the battery, and resumes on the charger.
            val relay = charging || (!power.isPowerSaveMode && level > LOW_BATTERY)
            // fastest discovery only while someone is looking at the screen and the battery can afford it: under
            // rubble the caller's phone must last for hours
            transport.setLowPower(!charging && (!power.isInteractive || !relay || level < 50))
            val (carrying, duyulan) = synchronized(this@MeshService) { node.relay = relay; node.tick(); kaydet(); node.carrying to node.yakin() }
            transport.temizle()
            if (kurum != null && profile.bekleyenAcil != null && System.currentTimeMillis() - profile.bekleyenZaman > YENIDEN_MS) yenidenGonder()
            if (carrying > 0 || profile.bekleyenAcil != null) uyandir()
            val peers = transport.peerCount
            // phones heard (their HELLO) or connected to, whichever is more: a phone we write to that has not yet
            // connected back to us is still a carrier in reach
            val yakin = maxOf(duyulan, peers)
            // HELLOs only matter to phones in range, and only often while there is mail to offer them
            val next = if (peers > 0 && carrying > 0) BUSY_MS else IDLE_MS
            val net = online()
            Log.i(BleTransport.TAG, "STATUS peers=$peers carrying=$carrying relay=$relay battery=$level online=$net next=${next / 1000}s")
            val ag = "AG yakin=$yakin internet=$net bluetooth=${transport.acik}"
            if (ag != sonAg) { sonAg = ag; show(ag) } // the screen's network line, only when it changes
            main.postDelayed(this, next)
        }
    }

    private var sonAlarm = 0L

    /**
     * With the screen off the CPU sleeps and this service's timer stops with it: while there is a call to carry or
     * resend, an alarm that fires even in Doze (at most every ~9 minutes there) wakes it for a round. Inexact, so it
     * needs no special permission.
     */
    private fun uyandir() {
        if (System.currentTimeMillis() - sonAlarm < 5 * 60_000L) return
        sonAlarm = System.currentTimeMillis()
        val pi = android.app.PendingIntent.getService(this, 7, Intent(this, MeshService::class.java).putExtra("uyan", 1),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        getSystemService(android.app.AlarmManager::class.java).setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + UYANMA_MS, pi)
    }

    private fun saat() = SimpleDateFormat("HH:mm", Locale("tr")).format(Date())

    private fun show(line: String) {
        Log.i(BleTransport.TAG, line)
        main.post {
            lines += line
            if (lines.size > 200) lines.removeAt(0) // the screen replays these on opening: days of carrying must not pile up
            listener?.invoke(line)
        }
    }

    companion object {
        private const val CHANNEL = "mesh"
        private const val UYARI = "uyari"
        private const val YENIDEN_MS = 6 * 3600_000L // well inside the mesh's one-day envelope lifetime
        private const val KONUM_MS = 6_000L // under rubble GPS rarely answers: never hold a call long for it
        private const val BUSY_MS = 2000L
        private const val IDLE_MS = 10_000L
        private const val LOW_BATTERY = 20 // percent
        private const val KONUM_TAKIP_MS = 5 * 60_000L
        private const val UYANMA_MS = 10 * 60_000L
        /** Log lines for the screen; touched only on the main thread. */
        val lines = mutableListOf<String>()
        var listener: ((String) -> Unit)? = null
    }
}
