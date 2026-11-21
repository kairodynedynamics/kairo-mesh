package com.kairodyne.mesh

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * KairoMesh's one screen. Built for someone hurt, in the dark, under rubble, in panic (docs/08-acil-ekran.md):
 *  - only what is vital: four large life-threatening situations, "I am safe" below (no messaging, no settings to
 *    fill in: the authority's code is built into the app);
 *  - what is selected is always written out, with a way to undo it; typing alone is enough;
 *  - a summary of exactly what will be sent, then a 5-second countdown with a large CANCEL, so a stray tap sends
 *    nothing; after sending, "I sent this by mistake" sends a correction to the authority;
 *  - a sound signal for rescuers, and the screen stays on while the app is open.
 * Light grey, slightly glossy theme with near-black text: readable on a cracked or dim screen without dazzling.
 *
 * In debug builds only (BuildConfig.OTOMASYON; never in the demo or release build handed out), test automation drives the same actions through intent extras:
 *   adb shell am start -n com.kairodyne.mesh/.MainActivity --es add "bora=<pairing code>"
 *   adb shell am start -n com.kairodyne.mesh/.MainActivity --es send "bora=hello"
 *   adb shell am start -n com.kairodyne.mesh/.MainActivity --es kurum "<authority code>"
 *   adb shell am start -n com.kairodyne.mesh/.MainActivity --es acil "enkaz|3|bebek|antakya ürgenpaşa mh"
 * and reads the "Kairo" log tag: READY, PAIRED, QUEUED, RECV, KURUM, ACIL_GONDERILDI, ACIL_ULASTI, DUYURU, STATUS.
 */
class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var durum: TextView
    private lateinit var durumKart: LinearLayout
    private lateinit var duzeltDugme: Button
    private lateinit var geriAlDugme: Button
    private lateinit var duyurular: TextView
    private lateinit var agSatiri: TextView
    private lateinit var kurumSatiri: TextView
    private lateinit var secimSatiri: TextView
    private lateinit var secimKaldir: Button
    private lateinit var metinNotu: TextView
    private lateinit var ozet: TextView
    private lateinit var kisiYazi: TextView
    private lateinit var metin: EditText
    private lateinit var geriSayim: FrameLayout
    private lateinit var geriSayimYazi: TextView
    private val turDugmeleri = linkedMapOf<String, LinearLayout>()
    private val ozelDugmeleri = linkedMapOf<String, Button>()
    private var waiting: Intent? = null // extras that arrived before Bluetooth permission was granted
    private var secilenTur: String? = null
    private var kisi: Int? = null
    private val ozel = linkedSetOf<String>()
    private var sonCagriId: String? = null
    private val ana = Handler(Looper.getMainLooper())
    private var ton: ToneGenerator? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // under rubble the screen must not go dark
        val profile = Profile(this)
        buildUi(profile.kurum != null)
        // replayed lines draw the screen as it was; they must not ask for permissions or Bluetooth again
        MeshService.lines.toList().forEach { onLine(it, canli = false) }
        MeshService.listener = { onLine(it) }
        waiting = automation(intent)
        requestPermissionsOnce()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        automation(intent)?.let(::toService)
    }

    private var arkaPlan = 0L

    override fun onStop() { super.onStop(); arkaPlan = System.currentTimeMillis() }

    override fun onStart() {
        super.onStart()
        // back after a while: nothing left selected from before (a short switch keeps a half-written call)
        if (arkaPlan != 0L && System.currentTimeMillis() - arkaPlan > 120_000 && geriSayim.visibility != View.VISIBLE) formuTemizle()
    }

    private fun formuTemizle() {
        secilenTur = null; kisi = null; ozel.clear(); metin.setText("")
        yenile()
    }

    override fun onDestroy() {
        MeshService.listener = null
        sinyalDurdur()
        ana.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // --- service lines -> screen ------------------------------------------------------------------------------------

    // the call on its way, for the status card: redrawn as phones come and go, until the authority confirms it
    private var bekleyenSaat: String? = null
    private var bluetoothKapali = false
    private var sonYakin = 0
    private var sonInternet = false

    private fun bekleyenGoster() {
        val s = bekleyenSaat ?: return
        durumGoster(KIRMIZI_ZEMIN, when {
            sonInternet -> "Çağrınız kuruma gönderiliyor · $s\nİnternet var. Kurum alınca burada yeşil onay görürsünüz."
            bluetoothKapali -> "Çağrınız telefonunuzda hazır, henüz kimseye ulaşmadı · $s\nBluetooth kapalı. Bluetooth'u açın; açılınca kendiliğinden gider."
            sonYakin > 0 -> "Çağrınız yakındaki telefonlara veriliyor · $s\nYakında $sonYakin KairoMesh'li telefon var; internete ilk ulaşan kuruma iletecek. " +
                "Kurum alınca burada yeşil onay görürsünüz."
            else -> "Çağrınız telefonunuzda hazır, henüz kimseye ulaşmadı · $s\nYakında KairoMesh'li telefon ve internet yok. Biri yaklaşınca ya da " +
                "internet gelince kendiliğinden gider; uygulamayı kapatmayın."
        })
    }

    private fun onLine(line: String, canli: Boolean = true) {
        if (::log.isInitialized) log.append(line + "\n")
        when {
            line.startsWith("ACIL_GONDERILDI") && line.contains("tur=guvende") -> {
                bekleyenSaat = null
                durumGoster(YESIL_ZEMIN, "Güvende olduğunuz kuruma bildiriliyor · ${saat(line)}" +
                    if (line.contains("onceki=var")) "\nÖnceki yardım çağrınız artık yeniden gönderilmeyecek." else "")
                duzeltDugme.visibility = View.GONE
                geriAlDugme.visibility = if (line.contains("onceki=var")) View.VISIBLE else View.GONE
                duyur("Güvende olduğunuz bildiriliyor")
                formuTemizle()
            }
            line.startsWith("ACIL_GONDERILDI") -> {
                sonCagriId = line.substringAfter("id=").substringBefore(" ")
                bekleyenSaat = saat(line)
                if (line.contains("bluetooth=kapali")) bluetoothKapali = true
                bekleyenGoster()
                duzeltDugme.visibility = View.VISIBLE
                geriAlDugme.visibility = View.GONE
                duyur("Çağrınız kaydedildi")
                formuTemizle() // what went out is in the status card; the next call starts from nothing selected
                if (canli) ekranBirAzDahaAcik()
            }
            line.startsWith("ACIL_YENIDEN") -> sonCagriId = line.substringAfter("id=").substringBefore(" ")
            line.startsWith("GERI_ALINDI") -> {
                sonCagriId = line.substringAfter("id=").substringBefore(" ")
                bekleyenSaat = saat(line)
                bekleyenGoster()
                duzeltDugme.visibility = View.VISIBLE
                geriAlDugme.visibility = View.GONE
                duyur("Yardım çağrınız yeniden gönderiliyor")
            }
            line.startsWith("ACIL_ULASTI") -> {
                bekleyenSaat = null
                durumGoster(YESIL_ZEMIN, "✓ Kurum çağrınızı aldı · ${saat(line)}\nYardım ekipleri bilgilendirildi. Mümkünse yerinizde kalın ve sesli sinyali kullanın.")
                duyur("Kurum çağrınızı aldı")
            }
            line.startsWith("DUZELTME_GONDERILDI") -> {
                bekleyenSaat = null
                durumGoster(GRI_ZEMIN, "Düzeltme gönderildi · ${saat(line)}\nKuruma önceki çağrının yanlışlıkla gönderildiği bildiriliyor.")
                duzeltDugme.visibility = View.GONE
                geriAlDugme.visibility = if (line.contains("onceki=var")) View.VISIBLE else View.GONE
            }
            line.startsWith("KONUM_ALINIYOR") && durum.text.startsWith("Çağrı hazırlanıyor") ->
                durum.text = "Çağrı hazırlanıyor… konumunuz alınıyor (en çok 6 sn)."
            line.startsWith("KONUM_IZNI_YOK") && canli -> {
                durumGoster(KIRMIZI_ZEMIN, durum.text.toString() + "\n\nKonum izni kapalı: kurtarıcılar yerinizi bilemez. " +
                    "Ayarlar > Uygulamalar > KairoMesh > İzinler > Konum'u açın.")
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
            }
            line.startsWith("KONUM_YOK") -> durum.append("\n\nKonum henüz alınamadı; telefon aramayı sürdürüyor ve bulunca kuruma ayrıca gönderecek. Mümkünse açık alana yakın durun.")
            line.startsWith("KONUM_GUNCELLENDI") -> durum.append("\n\n✓ Konumunuz bulundu ve kuruma gönderildi (${saat(line)}).")
            line.startsWith("ACIL_ZATEN") -> durumGoster(KIRMIZI_ZEMIN, "Çağrınız zaten gönderiliyor; tekrar basmanız gerekmez.")
            line.startsWith("DUYURU ") -> duyurular.text = line.removePrefix("DUYURU ") + "\n\n" + duyurular.text.toString().removePrefix("Henüz duyuru yok.")
            line.startsWith("KURUM ") -> kurumSatiri.text = "Çağrılar şu kuruma gider: ${line.substringAfter(" ad=")}"
            line.startsWith("AG ") -> agGoster(line)
            line.startsWith("BLUETOOTH_KAPALI") -> {
                bluetoothKapali = true
                agSatiri.text = "● Bluetooth kapalı · çağrınız yakındaki telefonlara ulaşamaz, lütfen açın"
                agSatiri.setTextColor(KIRMIZI); if (canli) bluetoothIste()
                bekleyenGoster()
            }
            line.startsWith("BLUETOOTH_ACIK") -> { bluetoothKapali = false; bekleyenGoster() }
                        line.startsWith("ERROR kurum") -> {
                kurumSatiri.text = "Bu sürümde çağrıların gideceği kurum tanımlı değil (kurumla sözleşme öncesi sürüm)."
                if (durum.text.startsWith("Çağrı hazırlanıyor")) durumGoster(KIRMIZI_ZEMIN, "Çağrı gönderilemedi: Bu sürümde çağrıların gideceği kurum tanımlı değil (kurumla sözleşme öncesi sürüm).")
            }
            // any other failure while a call is being prepared must reach the screen, not only the log
            line.startsWith("ERROR") && durum.text.startsWith("Çağrı hazırlanıyor") -> durumGoster(KIRMIZI_ZEMIN, "Çağrı gönderilemedi: ${line.removePrefix("ERROR ")}")
        }
    }

    private fun agGoster(line: String) {
        val yakin = line.substringAfter("yakin=").substringBefore(" ").toIntOrNull() ?: 0
        val internet = line.contains("internet=true")
        val bt = !line.contains("bluetooth=false")
        sonYakin = yakin; sonInternet = internet; bluetoothKapali = !bt
        bekleyenGoster()
        agSatiri.text = when {
            !bt -> "● Bluetooth kapalı · lütfen açın"
            internet -> "● İnternet var · çağrılar doğrudan kuruma gider"
            yakin > 0 -> "● İnternet yok · yakında çağrınızı taşıyabilecek $yakin telefon var"
            else -> "● İnternet yok · yakında KairoMesh'li telefon yok; biri yaklaşınca çağrınız gider"
        }
        agSatiri.setTextColor(if (!bt) KIRMIZI else if (internet || yakin > 0) YESIL else AMBER)
    }

    private fun durumGoster(zemin: Int, yazi: String) {
        durum.text = yazi
        durumKart.background = kutu(zemin, zemin, 14)
        durumKart.visibility = View.VISIBLE
    }

    private fun duyur(yazi: String) = durum.announceForAccessibility(yazi)

    private fun saat(line: String) = line.substringAfter("saat=", "").substringBefore(" ")

    // --- permissions and the service --------------------------------------------------------------------------------

    // This activity is exported, so any installed app can start it with extras. Honouring "add" there would let it
    // swap a contact's key for its own and read that contact's mail: the extras exist for test automation only.
    private fun automation(intent: Intent?) =
        intent?.takeIf { BuildConfig.OTOMASYON }

    private fun granted() = BLUETOOTH.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    // The service runs without the Bluetooth permission too: a phone with internet still sends its own call. The
    // permission is asked for alongside, and the service starts the radio once it is granted.
    private fun toService(extras: Intent? = null) {
        startForegroundService(Intent(this, MeshService::class.java).apply { extras?.extras?.let(::putExtras) })
    }

    private fun requestPermissionsOnce() {
        toService(waiting); waiting = null
        // notifications and location are optional: without location the call goes without coordinates
        if (!granted()) requestPermissions(BLUETOOTH + Manifest.permission.POST_NOTIFICATIONS + Manifest.permission.ACCESS_FINE_LOCATION +
            Manifest.permission.ACCESS_COARSE_LOCATION, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (requestCode != 1) return
        if (granted()) return toService() // the service starts the radio now
        log.append("ERROR Bluetooth izni verilmedi; ağ çalışamaz\n")
        durumGoster(KIRMIZI_ZEMIN, "\"Yakındaki cihazlar\" izni verilmedi: internet yokken çağrınız başka telefonlara ulaşamaz. " +
            "Açmak için buraya dokunun (Ayarlar > İzinler > Yakındaki cihazlar).")
        durumKart.setOnClickListener {
            durumKart.setOnClickListener(null)
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))) }
        }
    }

    private var ilkAcilis = true

    override fun onResume() {
        super.onResume()
        if (!ilkAcilis && granted()) toService() // granted in Settings meanwhile: the radio starts
        ilkAcilis = false
    }

    // Under rubble the screen must not go dark while the caller fills in the call; once it has gone, the battery
    // matters more: two more minutes, then the screen may sleep (the sound signal keeps it on while it plays).
    private val ekranKapanabilir = Runnable { if (!sinyalAcik) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    private fun ekranBirAzDahaAcik() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ana.removeCallbacks(ekranKapanabilir)
        ana.postDelayed(ekranKapanabilir, 120_000)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (bekleyenSaat != null) ekranBirAzDahaAcik() else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private var bluetoothSoruldu = false

    /** Asks once per launch to switch Bluetooth on (the system dialog); the network line stays as the reminder. */
    private fun bluetoothIste() {
        if (bluetoothSoruldu || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        bluetoothSoruldu = true
        runCatching { startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
    }

    // --- the call ----------------------------------------------------------------------------------------------------

    private fun turSec(anahtar: String?) {
        // tapping the selected situation again clears it: a mistaken tap is undone where it happened
        secilenTur = if (secilenTur == anahtar) null else anahtar
        yenile()
        duyur(secilenTur?.let { "${Cagri.TURLER[it]} seçildi" } ?: "Seçim kaldırıldı")
    }

    /** Redraws the selection, the notes and the summary from the state: what is shown is always what will be sent. */
    private fun yenile() {
        for ((k, v) in turDugmeleri) {
            val secili = k == secilenTur
            val hayati = k in Cagri.HAYATI
            v.background = if (secili) kutu(KIRMIZI, KIRMIZI, 16) else kutu(OZET_ZEMIN, if (hayati) KIRMIZI_KENAR else CIZGI, 16)
            v.isSelected = secili
            v.stateDescription = if (secili) "seçili" else "seçili değil"
            (v.getChildAt(0) as TextView).setTextColor(if (secili) Color.WHITE else if (hayati) KIRMIZI else METIN)
            (v.getChildAt(1) as TextView).apply {
                setTextColor(if (secili) Color.WHITE else IKINCIL)
                textSize = if (hayati) 14f else if (secili) 12f else 1f // small buttons only show it when selected
                text = if (secili) "✓ Seçildi · kaldırmak için yine dokunun" else Cagri.ALT_YAZI[k] ?: ""
            }
        }
        for ((k, b) in ozelDugmeleri) {
            val secili = k in ozel
            b.background = if (secili) kutu(KIRMIZI_ZEMIN, KIRMIZI, 24) else kutu(OZET_ZEMIN, CIZGI, 24)
            b.setTextColor(if (secili) KIRMIZI else METIN)
            b.text = (if (secili) "✓ " else "") + Cagri.OZEL[k]
            b.stateDescription = if (secili) "seçili" else "seçili değil"
        }
        val tur = secilenTur
        secimSatiri.text = if (tur != null) "Seçili: ${Cagri.TURLER[tur]}" else "Durum seçilmedi"
        secimSatiri.setTextColor(if (tur != null) KIRMIZI else IKINCIL)
        secimKaldir.visibility = if (tur != null) View.VISIBLE else View.GONE
        kisiYazi.text = kisi?.toString() ?: "?"
        val yazili = metin.text.isNotBlank()
        metinNotu.visibility = if (yazili) View.VISIBLE else View.GONE
        metinNotu.text = if (tur != null) "“${Cagri.TURLER[tur]}” seçimi de yazınızla birlikte gönderilecek. İstemiyorsanız seçimi kaldırın."
        else "Durum seçmediniz: telefon yazınızdan anlayacak ve çağrıyı acil olarak gönderecek."
        ozet.text = buildString {
            append("Gönderilecekler\n")
            append("• Durum: ${tur?.let { Cagri.TURLER[it] } ?: if (yazili) "yazdığınızdan anlaşılacak" else "—"}\n")
            append("• Kişi sayısı: ${kisi ?: "belirtilmedi"}\n")
            append("• Yanınızda: ${if (ozel.isEmpty()) "belirtilmedi" else ozel.joinToString(", ") { Cagri.OZEL[it] ?: it }}\n")
            if (yazili) append("• Mesaj: \"${metin.text.toString().trim().take(80)}\"\n")
            append("• Konum: izin verildiyse GPS eklenir")
        }
    }

    private fun gonderBas() {
        val tur = secilenTur
        if (tur == null && metin.text.isBlank()) {
            durumGoster(KIRMIZI_ZEMIN, "Önce durumunuzu seçin ya da kısaca yazın.")
            return
        }
        if (Profile(this).kurum == null) {
            durumGoster(KIRMIZI_ZEMIN, "Çağrı gönderilemez: Bu sürümde çağrıların gideceği kurum tanımlı değil (kurumla sözleşme öncesi sürüm).")
            return
        }
        if (tur == "guvende") return gonder() // "I am safe" has no countdown: nothing urgent can go out by mistake
        // a stray tap must not send a call: five seconds with a large CANCEL, then it goes by itself
        geriSayim.visibility = View.VISIBLE
        var kalan = 5
        val adim = object : Runnable {
            override fun run() {
                if (geriSayim.visibility != View.VISIBLE) return
                if (kalan == 0) { geriSayim.visibility = View.GONE; gonder(); return }
                geriSayimYazi.text = "Çağrı $kalan saniye içinde\ngönderilecek"
                geriSayimYazi.announceForAccessibility("$kalan")
                kalan--
                ana.postDelayed(this, 1000)
            }
        }
        adim.run()
    }

    private fun gonder() {
        geriSayim.visibility = View.GONE
        val tur = secilenTur ?: "" // empty: the phone reads the situation from the text
        toService(Intent().putExtra("acil", "$tur|${kisi ?: ""}|${ozel.joinToString(",")}|${metin.text.toString().replace("|", " ")}"))
        durumGoster(KIRMIZI_ZEMIN, "Çağrı hazırlanıyor…")
    }

    // --- sound signal: helps rescuers find a person they cannot see -----------------------------------------------

    private var sinyalAcik = false
    private val sinyalAdim = object : Runnable {
        override fun run() {
            if (!sinyalAcik) return
            ton?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 700)
            ana.postDelayed(this, 1600)
        }
    }

    private fun sinyal(dugme: Button) {
        sinyalAcik = !sinyalAcik
        if (sinyalAcik) {
            ton = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
            sinyalAdim.run()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            dugme.text = "■ Sesli sinyali durdur"
        } else { sinyalDurdur(); dugme.text = "♪ Sesli sinyal · kurtarıcılar sizi duysun" }
    }

    private fun sinyalDurdur() { sinyalAcik = false; ton?.release(); ton = null }

    // --- layout ------------------------------------------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun kutu(renk: Int, kenar: Int, r: Int = 12) = GradientDrawable().apply {
        setColor(renk); cornerRadius = dp(r).toFloat(); if (kenar != renk) setStroke(dp(1), kenar) // a hairline, never a heavy frame
    }

    private fun yazi(t: String, boy: Float, renk: Int = METIN, kalin: Boolean = false) = TextView(this).apply {
        text = t; textSize = boy; setTextColor(renk); if (kalin) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    private fun kart(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16))
        background = kutu(KART, KART, 16); elevation = dp(1).toFloat()
    }

    private fun yer(ust: Int = 12) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ust) }

    private fun dugme(t: String, zemin: Int, yaziRenk: Int, kenar: Int = zemin, boy: Float = 18f) = Button(this).apply {
        text = t; isAllCaps = false; textSize = boy; setTextColor(yaziRenk); setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        background = kutu(zemin, kenar, 14); minHeight = dp(56); setPadding(dp(16), dp(10), dp(16), dp(10))
        stateListAnimator = null; elevation = 0f // flat: no Material shadow under every button
    }

    private fun buildUi(kurumVar: Boolean) {
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(18), pad, dp(40)) }

        // header: name, where calls go, the network
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(View(this@MainActivity).apply { background = kutu(KIRMIZI, KIRMIZI, 8) }, LinearLayout.LayoutParams(dp(14), dp(14)))
            addView(yazi("KairoMesh", 26f, METIN, true).apply { setPadding(dp(10), 0, 0, 0) })
        })
        kurumSatiri = yazi(if (kurumVar) "" else "Bu sürümde çağrıların gideceği kurum tanımlı değil (kurumla sözleşme öncesi sürüm).", 15f, IKINCIL)
        root.addView(kurumSatiri, yer(4))
        agSatiri = yazi("● Ağ hazırlanıyor…", 16f, AMBER, true)
        root.addView(agSatiri, yer(6))

        // 1. what happened: four life-threatening situations, large
        val acil = kart()
        acil.addView(yazi("Acil yardım çağrısı", 22f, METIN, true))
        acil.addView(yazi("İnternet olmasa da çalışır. Durumunuzu seçin ya da yazın, sonra gönderin.", 15f, IKINCIL), yer(4))
        val izgara = GridLayout(this).apply { columnCount = 2 }
        for (k in Cagri.HAYATI) {
            val kutuK = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); minimumHeight = dp(104)
                isClickable = true; isFocusable = true; contentDescription = Cagri.TURLER[k]
                // read out as a button (TalkBack), like the other choices
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(v: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(v, info); info.className = Button::class.java.name
                    }
                }
                setOnClickListener { turSec(k) }
                addView(yazi(Cagri.TURLER[k]!!, 20f, KIRMIZI, true))
                addView(yazi(Cagri.ALT_YAZI[k] ?: "", 14f, IKINCIL).apply { setPadding(0, dp(4), 0, 0) })
            }
            turDugmeleri[k] = kutuK
            izgara.addView(kutuK, GridLayout.LayoutParams().apply {
                width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(4), dp(4), dp(4), dp(4))
                setGravity(Gravity.FILL) // both buttons in a row as tall as the taller one
            })
        }
        acil.addView(izgara, yer(10))

        // the selection, said in words, with an undo
        val secim = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        secimSatiri = yazi("", 17f, IKINCIL, true)
        secim.addView(secimSatiri, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        secimKaldir = dugme("Kaldır", KIRMIZI_ZEMIN, KIRMIZI, KIRMIZI_ZEMIN, 16f).apply { setOnClickListener { turSec(null) }; contentDescription = "Seçimi kaldır" }
        secim.addView(secimKaldir)
        acil.addView(secim, yer(12))

        // 2. how many, who is with you: no keyboard needed
        acil.addView(yazi("Kaç kişisiniz?", 17f, METIN, true), yer(16))
        val sayac = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sayac.addView(dugme("−", KART, METIN, CIZGI, 26f).apply {
            contentDescription = "Bir azalt"; setOnClickListener { kisi = kisi?.let { if (it <= 1) null else it - 1 }; yenile() }
        }, LinearLayout.LayoutParams(dp(72), WRAP_CONTENT))
        kisiYazi = yazi("?", 28f, METIN, true).apply { gravity = Gravity.CENTER }
        sayac.addView(kisiYazi, LinearLayout.LayoutParams(dp(80), WRAP_CONTENT))
        sayac.addView(dugme("+", KART, METIN, CIZGI, 26f).apply {
            contentDescription = "Bir artır"; setOnClickListener { kisi = minOf(99, (kisi ?: 0) + 1); yenile() }
        }, LinearLayout.LayoutParams(dp(72), WRAP_CONTENT))
        sayac.addView(yazi("  ? = bilinmiyor", 15f, IKINCIL))
        acil.addView(sayac, yer(6))
        acil.addView(yazi("Yanınızda", 17f, METIN, true), yer(16))
        val ozelIzgara = GridLayout(this).apply { columnCount = 3 }
        for ((k, ad) in Cagri.OZEL) {
            val b = dugme(ad, KART, METIN, CIZGI, 16f).apply { setOnClickListener { if (!ozel.remove(k)) ozel += k; yenile() } }
            ozelDugmeleri[k] = b
            ozelIzgara.addView(b, GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        acil.addView(ozelIzgara, yer(4))

        // 3. or write it
        acil.addView(yazi("Ya da kısaca yazın", 17f, METIN, true), yer(16))
        metin = EditText(this).apply {
            hint = "Adres ve durum: ör. Antakya Ürgenpaşa Mh., enkazdayız, 3 kişi"; minLines = 2; textSize = 17f
            setTextColor(METIN); setHintTextColor(IKINCIL); background = kutu(Color.WHITE, CIZGI, 12); setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = yenile()
            })
        }
        acil.addView(metin, yer(4))
        metinNotu = yazi("", 15f, AMBER, true)
        acil.addView(metinNotu, yer(6))

        // 4. exactly what will go, then send
        ozet = yazi("", 16f, METIN).apply { background = kutu(OZET_ZEMIN, CIZGI, 12); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        acil.addView(ozet, yer(14))
        acil.addView(dugme("Çağrıyı gönder", KIRMIZI, Color.WHITE, KIRMIZI, 22f).apply {
            minHeight = dp(72); setOnClickListener { gonderBas() }
        }, yer(12))
        durumKart = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); visibility = View.GONE }
        durum = yazi("", 18f, METIN, true)
        durumKart.addView(durum)
        duzeltDugme = dugme("Yanlışlıkla gönderdim · yardım gerekmiyor", KART, METIN, CIZGI, 16f).apply {
            visibility = View.GONE
            setOnClickListener {
                val id = sonCagriId ?: return@setOnClickListener
                // one tap, no delay: if it was itself a mistake, the call can be sent again at once from the form above
                toService(Intent().putExtra("iptal", id))
            }
        }
        durumKart.addView(duzeltDugme, yer(10))
        // one tap undoes a correction or "I am safe" sent by mistake: the earlier call goes out again at once
        geriAlDugme = dugme("Geri al · hâlâ yardım gerekiyor", KIRMIZI, Color.WHITE, KIRMIZI, 17f).apply {
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE; toService(Intent().putExtra("geriAl", "1")) }
        }
        durumKart.addView(geriAlDugme, yer(10))
        acil.addView(durumKart, yer(12))
        root.addView(acil, yer(12))

        // sound signal and "I am safe"
        root.addView(dugme("♪ Sesli sinyal · kurtarıcılar sizi duysun", KART, METIN, CIZGI).apply { setOnClickListener { sinyal(this) } }, yer(16))
        root.addView(dugme("Güvendeyim · kuruma bildir", YESIL_ZEMIN, YESIL, YESIL_ZEMIN).apply {
            setOnClickListener {
                secilenTur = "guvende"; yenile(); gonderBas() // one tap: "I am safe" never sends anything urgent
            }
        }, yer(8))

        // announcements
        val duyuru = kart()
        duyuru.addView(yazi("Kurum duyuruları", 20f, METIN, true))
        duyurular = yazi("Henüz duyuru yok.", 16f, IKINCIL)
        duyuru.addView(duyurular, yer(6))
        root.addView(duyuru, yer(14))

        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(IKINCIL); setPadding(0, dp(16), 0, 0)
            // the technical log is for development only: users see plain Turkish above
            visibility = if (BuildConfig.OTOMASYON) View.VISIBLE else View.GONE
        }
        root.addView(log)

        // countdown over everything: large CANCEL, or send now
        geriSayim = FrameLayout(this).apply {
            visibility = View.GONE; setBackgroundColor(Color.argb(250, 14, 26, 34)); isClickable = true
            val ic = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), 0) }
            geriSayimYazi = yazi("", 28f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
            ic.addView(geriSayimYazi)
            ic.addView(dugme("İptal et", Color.WHITE, KIRMIZI, Color.WHITE, 30f).apply {
                minHeight = dp(96); setOnClickListener { geriSayim.visibility = View.GONE; durumGoster(GRI_ZEMIN, "Çağrı iptal edildi. Hiçbir şey gönderilmedi.") }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(28) })
            ic.addView(dugme("Hemen gönder", KIRMIZI, Color.WHITE, Color.WHITE, 20f).apply { setOnClickListener { gonder() } },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(ic, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        val ekran = FrameLayout(this).apply {
            // light grey with a soft sheen: neither dazzling in the dark nor dim
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(ZEMIN_UST, ZEMIN_ALT))
            addView(ScrollView(this@MainActivity).apply { addView(root) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(geriSayim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(ekran)
        // Android 15+ draws the app under the system bars: the ground runs to the edges, the content stays clear of
        // the status bar, the navigation bar and the keyboard
        ekran.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }
        val acikCubuk = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(acikCubuk, acikCubuk)
        yenile()
    }

    private companion object {
        val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        // theme (Apple's grouped light surfaces): a light grey ground with a soft sheen, white cards without frames,
        // near-black text (16:1 on the cards; secondary text 9:1, readable on a cracked screen), one accent: red
        val ZEMIN_UST = Color.rgb(242, 242, 247); val ZEMIN_ALT = Color.rgb(226, 226, 232)
        val KART = Color.WHITE; val CIZGI = Color.rgb(209, 209, 214); val OZET_ZEMIN = Color.rgb(242, 242, 247)
        val METIN = Color.rgb(28, 28, 30); val IKINCIL = Color.rgb(72, 72, 74)
        val KIRMIZI = Color.rgb(179, 38, 30); val KIRMIZI_KENAR = Color.rgb(240, 190, 186); val KIRMIZI_ZEMIN = Color.rgb(253, 236, 234)
        val YESIL = Color.rgb(11, 110, 79); val YESIL_ZEMIN = Color.rgb(221, 240, 231); val AMBER = Color.rgb(138, 90, 0)
        val GRI_ZEMIN = Color.rgb(228, 232, 236)
    }
}
