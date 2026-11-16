package com.kairodyne.mesh

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
 *  - four large life-threatening situations; needs and "I am safe" smaller, below;
 *  - what is selected is always written out, with a way to undo it; typing alone is enough;
 *  - a summary of exactly what will be sent, then a 5-second countdown with a large CANCEL, so a stray tap sends
 *    nothing; after sending, "I sent this by mistake" sends a correction to the authority;
 *  - a sound signal for rescuers, and the screen stays on while the app is open.
 * Light grey, slightly glossy theme with near-black text: readable on a cracked or dim screen without dazzling.
 *
 * In debug builds only, test automation drives the same actions through intent extras:
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
        buildUi(profile.code, profile.kurum != null)
        MeshService.lines.forEach(::onLine)
        MeshService.listener = ::onLine
        waiting = automation(intent)
        requestPermissionsOnce()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        automation(intent)?.let(::toService)
    }

    override fun onDestroy() {
        MeshService.listener = null
        sinyalDurdur()
        ana.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // --- service lines -> screen ------------------------------------------------------------------------------------

    private fun onLine(line: String) {
        if (::log.isInitialized) log.append(line + "\n")
        when {
            line.startsWith("ACIL_GONDERILDI") -> {
                sonCagriId = line.substringAfter("id=").substringBefore(" ")
                durumGoster(KIRMIZI_ZEMIN, if (line.contains("bluetooth=kapali"))
                    "Çağrınız kaydedildi (${saat(line)}) ama Bluetooth kapalı: yakındaki telefonlara ulaşamıyor. Bluetooth'u açın; açınca kendiliğinden gönderilir."
                else "Çağrınız gönderildi (${saat(line)}).\nYakındaki telefonlar taşıyor; internete ulaşan ilk telefon kuruma iletecek. Uygulamayı kapatmayın.")
                duzeltDugme.visibility = View.VISIBLE
                duyur("Çağrınız gönderildi")
            }
            line.startsWith("ACIL_YENIDEN") -> sonCagriId = line.substringAfter("id=").substringBefore(" ")
            line.startsWith("ACIL_ULASTI") -> {
                durumGoster(YESIL_ZEMIN, "✓ KURUM ÇAĞRINIZI ALDI (${saat(line)}).\nYardım ekipleri bilgilendirildi. Mümkünse yerinizde kalın, sesli sinyali kullanın.")
                duyur("Kurum çağrınızı aldı")
            }
            line.startsWith("DUZELTME_GONDERILDI") -> {
                durumGoster(GRI_ZEMIN, "Düzeltme gönderildi (${saat(line)}): kuruma önceki çağrının yanlışlıkla gönderildiği bildiriliyor.")
                duzeltDugme.visibility = View.GONE
            }
            line.startsWith("KONUM_ALINIYOR") && durum.text.startsWith("Çağrı hazırlanıyor") ->
                durum.text = "Çağrı hazırlanıyor… konumunuz alınıyor (en çok 6 saniye)."
            line.startsWith("ACIL_ZATEN") -> durumGoster(KIRMIZI_ZEMIN, "Çağrınız zaten gönderiliyor. Tekrar basmanıza gerek yok.")
            line.startsWith("DUYURU ") -> duyurular.text = line.removePrefix("DUYURU ") + "\n\n" + duyurular.text.toString().removePrefix("Henüz duyuru yok.")
            line.startsWith("KURUM ") -> kurumSatiri.text = "Çağrılarınız şu kuruma gider: ${line.substringAfter(" ad=")}"
            line.startsWith("AG ") -> agGoster(line)
            line.startsWith("BLUETOOTH_KAPALI") -> {
                agSatiri.text = "● Bluetooth kapalı — çağrınız yakındaki telefonlara ulaşamaz. Lütfen açın."
                agSatiri.setTextColor(KIRMIZI); bluetoothIste()
            }
            line.startsWith("ERROR kurum kodu geçersiz") -> kurumSatiri.text =
                "Bu kurum kodu yetkili makamca onaylanmamış ya da bozuk; kaydedilmedi. Kodu yalnızca kurumun resmî kanalından alın."
            line.startsWith("ERROR kurum") -> {
                kurumSatiri.text = "Kurum kodu tanımlı değil: aşağıdaki Ayarlar bölümüne kurumun kodunu girin."
                if (durum.text.startsWith("Çağrı hazırlanıyor")) durumGoster(KIRMIZI_ZEMIN, "Çağrı gönderilemedi: çağrının gideceği kurum tanımlı değil. Ayarlar'a kurumun kodunu girin.")
            }
            // any other failure while a call is being prepared must reach the screen, not only the log
            line.startsWith("ERROR") && durum.text.startsWith("Çağrı hazırlanıyor") -> durumGoster(KIRMIZI_ZEMIN, "Çağrı gönderilemedi: ${line.removePrefix("ERROR ")}")
        }
    }

    private fun agGoster(line: String) {
        val yakin = line.substringAfter("yakin=").substringBefore(" ").toIntOrNull() ?: 0
        val internet = line.contains("internet=true")
        val bt = !line.contains("bluetooth=false")
        agSatiri.text = when {
            !bt -> "● Bluetooth kapalı — lütfen açın"
            internet -> "● İnternet var · çağrılar doğrudan kuruma gider · yakında $yakin telefon"
            yakin > 0 -> "● İnternet yok · yakında $yakin telefon taşıyabilir"
            else -> "● İnternet yok · yakında telefon görünmüyor; çağrınız biri yaklaşınca gider"
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
        intent?.takeIf { applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 }

    private fun granted() = BLUETOOTH.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun toService(extras: Intent? = null) {
        // without the Bluetooth permission the request waits, and the permission dialog is shown again (not silently)
        if (!granted()) { waiting = extras; requestPermissionsOnce(); return }
        startForegroundService(Intent(this, MeshService::class.java).apply { extras?.extras?.let(::putExtras) })
    }

    private fun requestPermissionsOnce() {
        if (granted()) toService(waiting)
        // notifications and location are optional: without location the call goes without coordinates
        else requestPermissions(BLUETOOTH + Manifest.permission.POST_NOTIFICATIONS + Manifest.permission.ACCESS_FINE_LOCATION +
            Manifest.permission.ACCESS_COARSE_LOCATION, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (granted()) return toService(waiting)
        log.append("ERROR Bluetooth izni verilmedi; ağ çalışamaz\n")
        durumGoster(KIRMIZI_ZEMIN, "\"Yakındaki cihazlar\" izni verilmedi: çağrınız başka telefonlara ulaşamaz. " +
            "Ayarlar > Uygulamalar > KairoMesh > İzinler'den açın, sonra tekrar gönderin.")
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
        }
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
            v.background = if (secili) kutu(KIRMIZI, KIRMIZI, 16) else kutu(KART, if (hayati) KIRMIZI_KENAR else CIZGI, 16)
            v.isSelected = secili
            v.stateDescription = if (secili) "seçili" else "seçili değil"
            (v.getChildAt(0) as TextView).setTextColor(if (secili) Color.WHITE else if (hayati) KIRMIZI else METIN)
            (v.getChildAt(1) as TextView).apply {
                setTextColor(if (secili) Color.WHITE else IKINCIL)
                textSize = if (hayati) 14f else if (secili) 12f else 1f // small buttons only show it when selected
                text = if (secili) "✓ SEÇİLİ — kaldırmak için tekrar dokunun" else Cagri.ALT_YAZI[k] ?: ""
            }
        }
        for ((k, b) in ozelDugmeleri) {
            val secili = k in ozel
            b.background = if (secili) kutu(KOYU, KOYU, 24) else kutu(KART, CIZGI, 24)
            b.setTextColor(if (secili) Color.WHITE else METIN)
            b.text = (if (secili) "✓ " else "") + Cagri.OZEL[k]
            b.stateDescription = if (secili) "seçili" else "seçili değil"
        }
        val tur = secilenTur
        secimSatiri.text = if (tur != null) "Seçiminiz: ${Cagri.TURLER[tur]}" else "Henüz durum seçmediniz."
        secimSatiri.setTextColor(if (tur != null) KIRMIZI else IKINCIL)
        secimKaldir.visibility = if (tur != null) View.VISIBLE else View.GONE
        kisiYazi.text = kisi?.toString() ?: "?"
        val yazili = metin.text.isNotBlank()
        metinNotu.visibility = if (yazili) View.VISIBLE else View.GONE
        metinNotu.text = if (tur != null) "Seçtiğiniz \"${Cagri.TURLER[tur]}\" de yazdığınızla birlikte gönderilecek. İstemiyorsanız yukarıdan kaldırın."
        else "Durum seçmediniz: yazdığınızdan durum telefonda anlaşılacak ve çağrı acil olarak gönderilecek."
        ozet.text = buildString {
            append("Gönderilecek:\n")
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
            durumGoster(KIRMIZI_ZEMIN, "Çağrı gönderilemez: çağrının gideceği kurum tanımlı değil. Aşağıdaki Ayarlar bölümüne kurumun kodunu girin.")
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
                geriSayimYazi.text = "Çağrı $kalan saniye içinde gönderilecek"
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
            dugme.text = "■ Sesli sinyali durdur"
        } else { sinyalDurdur(); dugme.text = "♪ Sesli sinyal (kurtarıcılar sizi duysun)" }
    }

    private fun sinyalDurdur() { sinyalAcik = false; ton?.release(); ton = null }

    // --- layout ------------------------------------------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun kutu(renk: Int, kenar: Int, r: Int = 12) = GradientDrawable().apply {
        setColor(renk); cornerRadius = dp(r).toFloat(); setStroke(dp(2), kenar)
    }

    private fun yazi(t: String, boy: Float, renk: Int = METIN, kalin: Boolean = false) = TextView(this).apply {
        text = t; textSize = boy; setTextColor(renk); if (kalin) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    private fun kart(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16))
        background = kutu(KART, CIZGI, 16); elevation = dp(2).toFloat()
    }

    private fun yer(ust: Int = 12) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ust) }

    private fun dugme(t: String, zemin: Int, yaziRenk: Int, kenar: Int = zemin, boy: Float = 18f) = Button(this).apply {
        text = t; isAllCaps = false; textSize = boy; setTextColor(yaziRenk); setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        background = kutu(zemin, kenar, 14); minHeight = dp(56); setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    private fun buildUi(code: String, kurumVar: Boolean) {
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(18), pad, dp(40)) }

        // header: name, where calls go, the network
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(View(this@MainActivity).apply { background = kutu(KIRMIZI, KIRMIZI, 8) }, LinearLayout.LayoutParams(dp(14), dp(14)))
            addView(yazi("KairoMesh", 26f, METIN, true).apply { setPadding(dp(10), 0, 0, 0) })
        })
        kurumSatiri = yazi(if (kurumVar) "" else "Kurum kodu tanımlı değil: aşağıdaki Ayarlar bölümüne kurumun kodunu girin.", 15f, IKINCIL)
        root.addView(kurumSatiri, yer(4))
        agSatiri = yazi("● Ağ hazırlanıyor…", 16f, AMBER, true)
        root.addView(agSatiri, yer(6))

        // 1. what happened: four life-threatening situations, large
        val acil = kart()
        acil.addView(yazi("ACİL YARDIM ÇAĞRISI", 22f, KIRMIZI, true))
        acil.addView(yazi("İnternet olmasa da çalışır. 1) Durumunuza dokunun ya da yazın, 2) Gönder'e basın.", 16f, IKINCIL), yer(4))
        val izgara = GridLayout(this).apply { columnCount = 2 }
        for (k in Cagri.HAYATI) {
            val kutuK = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); minimumHeight = dp(104)
                isClickable = true; isFocusable = true; contentDescription = Cagri.TURLER[k]
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
        acil.addView(yazi("Hayati değilse:", 15f, IKINCIL, true), yer(10))
        val ihtiyac = GridLayout(this).apply { columnCount = 3 }
        for (k in listOf("barinma", "gida_su", "kayip")) {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(6), dp(10), dp(6), dp(10)); minimumHeight = dp(64)
                isClickable = true; isFocusable = true; contentDescription = Cagri.TURLER[k]; setOnClickListener { turSec(k) }
                addView(yazi(Cagri.TURLER[k]!!, 15f, METIN, true).apply { gravity = Gravity.CENTER })
                addView(yazi("", 1f, METIN)) // keeps the same two-row shape as the large buttons for yenile()
            }
            turDugmeleri[k] = b
            ihtiyac.addView(b, GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(3), dp(3), dp(3), dp(3)); setGravity(Gravity.FILL) })
        }
        acil.addView(ihtiyac, yer(4))

        // the selection, said in words, with an undo
        val secim = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        secimSatiri = yazi("", 17f, IKINCIL, true)
        secim.addView(secimSatiri, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        secimKaldir = dugme("Kaldır", KART, KIRMIZI, KIRMIZI, 16f).apply { setOnClickListener { turSec(null) }; contentDescription = "Seçimi kaldır" }
        secim.addView(secimKaldir)
        acil.addView(secim, yer(12))

        // 2. how many, who is with you: no keyboard needed
        acil.addView(yazi("Kaç kişisiniz?", 17f, METIN, true), yer(14))
        val sayac = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sayac.addView(dugme("−", KART, METIN, CIZGI, 26f).apply {
            contentDescription = "Bir azalt"; setOnClickListener { kisi = kisi?.let { if (it <= 1) null else it - 1 }; yenile() }
        }, LinearLayout.LayoutParams(dp(72), dp(60)))
        kisiYazi = yazi("?", 28f, METIN, true).apply { gravity = Gravity.CENTER }
        sayac.addView(kisiYazi, LinearLayout.LayoutParams(dp(80), WRAP_CONTENT))
        sayac.addView(dugme("+", KART, METIN, CIZGI, 26f).apply {
            contentDescription = "Bir artır"; setOnClickListener { kisi = minOf(99, (kisi ?: 0) + 1); yenile() }
        }, LinearLayout.LayoutParams(dp(72), dp(60)))
        sayac.addView(yazi("  ? = bilmiyorum", 14f, IKINCIL))
        acil.addView(sayac, yer(6))
        acil.addView(yazi("Yanınızda:", 17f, METIN, true), yer(14))
        val ozelIzgara = GridLayout(this).apply { columnCount = 3 }
        for ((k, ad) in Cagri.OZEL) {
            val b = dugme(ad, KART, METIN, CIZGI, 16f).apply { setOnClickListener { if (!ozel.remove(k)) ozel += k; yenile() } }
            ozelDugmeleri[k] = b
            ozelIzgara.addView(b, GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        acil.addView(ozelIzgara, yer(4))

        // 3. or write it
        acil.addView(yazi("Ya da yazın (isteğe bağlı):", 17f, METIN, true), yer(14))
        metin = EditText(this).apply {
            hint = "Adres ve durum, ör. antakya ürgenpaşa mh enkazdayız 3 kişi"; minLines = 2; textSize = 17f
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
        acil.addView(dugme("ÇAĞRIYI GÖNDER", KIRMIZI, Color.WHITE, KIRMIZI, 24f).apply {
            minHeight = dp(72); setOnClickListener { gonderBas() }
        }, yer(12))
        durumKart = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); visibility = View.GONE }
        durum = yazi("", 18f, METIN, true)
        durumKart.addView(durum)
        duzeltDugme = dugme("Yanlışlıkla gönderdim / artık yardım gerekmiyor", KART, METIN, CIZGI, 16f).apply {
            visibility = View.GONE
            setOnClickListener {
                val id = sonCagriId ?: return@setOnClickListener
                // a second tap confirms: correcting a real call by accident would be as bad as a false one
                if (text.startsWith("Emin misiniz")) toService(Intent().putExtra("iptal", id))
                else text = "Emin misiniz? Onaylamak için tekrar dokunun"
            }
        }
        durumKart.addView(duzeltDugme, yer(10))
        acil.addView(durumKart, yer(12))
        root.addView(acil, yer(12))

        // sound signal and "I am safe"
        root.addView(dugme("♪ Sesli sinyal (kurtarıcılar sizi duysun)", KOYU, Color.WHITE, KOYU).apply { setOnClickListener { sinyal(this) } }, yer(12))
        root.addView(dugme("Güvendeyim — kuruma bildir", YESIL_ZEMIN, YESIL, YESIL).apply {
            setOnClickListener {
                if (text.startsWith("Emin misiniz")) { secilenTur = "guvende"; yenile(); gonderBas() }
                else text = "Emin misiniz? Güvende olduğunuzu bildirmek için tekrar dokunun"
            }
        }, yer(8))

        // announcements
        val duyuru = kart()
        duyuru.addView(yazi("Kurum duyuruları", 19f, METIN, true))
        duyurular = yazi("Henüz duyuru yok.", 16f, IKINCIL)
        duyuru.addView(duyurular, yer(6))
        root.addView(duyuru, yer(14))

        // settings and messaging: folded away, they are not for the emergency
        val ayarlar = kart().apply { visibility = View.GONE }
        root.addView(dugme("Ayarlar ve mesajlaşma ▾", KART, METIN, CIZGI, 16f).apply {
            setOnClickListener { ayarlar.visibility = if (ayarlar.visibility == View.GONE) View.VISIBLE else View.GONE }
        }, yer(14))
        root.addView(ayarlar, yer(6))
        fun alan(ipucu: String, tekSatir: Boolean = true) = EditText(this).apply {
            hint = ipucu; isSingleLine = tekSatir; textSize = 16f; setTextColor(METIN); setHintTextColor(IKINCIL)
            background = kutu(Color.WHITE, CIZGI, 10); setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        fun extra(key: String, value: String) = toService(Intent().putExtra(key, value))
        ayarlar.addView(yazi("Kurum kodu", 17f, METIN, true))
        ayarlar.addView(yazi("Kurumun resmî kanalından aldığınız kodu yapıştırın. Onaysız kod kabul edilmez.", 14f, IKINCIL))
        val kurumKodu = alan("Kurum kodu", tekSatir = false).apply { minLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        ayarlar.addView(kurumKodu, yer(6))
        ayarlar.addView(dugme("Kurum kodunu kaydet", KOYU, Color.WHITE, KOYU, 16f).apply { setOnClickListener { extra("kurum", kurumKodu.text.toString().trim()) } }, yer(6))
        ayarlar.addView(yazi("Eşleşme kodunuz (kopyalamak için dokunun)", 17f, METIN, true), yer(16))
        ayarlar.addView(yazi(code, 13f, IKINCIL).apply {
            typeface = Typeface.MONOSPACE
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("KairoMesh", code))
                append("  (kopyalandı)")
            }
        })
        val name = alan("Kişi adı"); val pairing = alan("Kişinin eşleşme kodu")
        ayarlar.addView(name, yer(8)); ayarlar.addView(pairing, yer(6))
        ayarlar.addView(dugme("Kişi ekle", KART, METIN, CIZGI, 16f).apply { setOnClickListener { extra("add", "${name.text.toString().trim()}=${pairing.text.toString().trim()}") } }, yer(6))
        ayarlar.addView(yazi("Mesajlaşma", 17f, METIN, true), yer(16))
        val to = alan("Alıcı (kişi adı)"); val message = alan("Mesaj")
        ayarlar.addView(to, yer(6)); ayarlar.addView(message, yer(6))
        ayarlar.addView(dugme("Gönder", KART, METIN, CIZGI, 16f).apply { setOnClickListener { extra("send", "${to.text.toString().trim()}=${message.text}"); message.text.clear() } }, yer(6))
        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(IKINCIL); setPadding(0, dp(16), 0, 0)
            // the technical log is for development only: users see plain Turkish above
            visibility = if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) View.VISIBLE else View.GONE
        }
        ayarlar.addView(log)

        // countdown over everything: large CANCEL, or send now
        geriSayim = FrameLayout(this).apply {
            visibility = View.GONE; setBackgroundColor(Color.argb(250, 14, 26, 34)); isClickable = true
            val ic = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), 0) }
            geriSayimYazi = yazi("", 28f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
            ic.addView(geriSayimYazi)
            ic.addView(dugme("İPTAL", Color.WHITE, KIRMIZI, Color.WHITE, 30f).apply {
                minHeight = dp(96); setOnClickListener { geriSayim.visibility = View.GONE; durumGoster(GRI_ZEMIN, "Çağrı iptal edildi; hiçbir şey gönderilmedi.") }
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
        window.statusBarColor = ZEMIN_UST
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        yenile()
    }

    private companion object {
        val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        // theme: light grey with sheen; text near-black (contrast over 14:1 on the cards)
        val ZEMIN_UST = Color.rgb(236, 239, 242); val ZEMIN_ALT = Color.rgb(214, 220, 225)
        val KART = Color.rgb(248, 249, 250); val CIZGI = Color.rgb(192, 200, 207); val OZET_ZEMIN = Color.rgb(236, 240, 243)
        val METIN = Color.rgb(14, 26, 34); val IKINCIL = Color.rgb(58, 72, 83); val KOYU = Color.rgb(38, 52, 62)
        val KIRMIZI = Color.rgb(179, 38, 30); val KIRMIZI_KENAR = Color.rgb(214, 140, 132); val KIRMIZI_ZEMIN = Color.rgb(251, 228, 225)
        val YESIL = Color.rgb(11, 110, 79); val YESIL_ZEMIN = Color.rgb(221, 240, 231); val AMBER = Color.rgb(138, 90, 0)
        val GRI_ZEMIN = Color.rgb(228, 232, 236)
    }
}
