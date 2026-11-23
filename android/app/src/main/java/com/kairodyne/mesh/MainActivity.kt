package com.kairodyne.mesh

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
    private lateinit var geriSayimSayi: TextView
    private val turDugmeleri = linkedMapOf<String, LinearLayout>()
    private val turBasliklari = mutableMapOf<String, TextView>()
    private val turAltlari = mutableMapOf<String, TextView>()
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
        if (durumKart.visibility != View.VISIBLE) bekleyeniCiz(profile) // the app was killed or the phone restarted
        state?.let(::geriYukle)
        waiting = automation(intent)
        requestPermissionsOnce()
    }

    /** The call still on its way, and the undo, from disk: after a restart there are no service lines to replay. */
    private fun bekleyeniCiz(profile: Profile) {
        val bekleyen = profile.bekleyenAcil
        val saat = java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr")).format(java.util.Date(profile.bekleyenZaman))
        if (bekleyen != null) {
            if (runCatching { org.json.JSONObject(bekleyen).optString("tur") }.getOrNull() == "guvende")
                durumGoster(YESIL_ZEMIN, "Güvende olduğunuz kuruma bildiriliyor · $saat")
            else {
                bekleyenSaat = saat
                sonCagriId = profile.bekleyenIdler.firstOrNull()
                bekleyenGoster()
                duzeltDugme.visibility = if (sonCagriId != null) View.VISIBLE else View.GONE
            }
        }
        if (profile.oncekiAcil != null) {
            if (bekleyen == null) durumGoster(GRI_ZEMIN, "Önceki yardım çağrınızı geri çektiniz.")
            geriAlDugme.visibility = View.VISIBLE
        }
    }

    // Activity recreated (font size, language): the form and a running countdown carry on where they were
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("tur", secilenTur); out.putInt("kisi", kisi ?: -1); out.putStringArrayList("ozel", ArrayList(ozel))
        out.putString("metin", metin.text.toString()); out.putInt("kalan", if (geriSayim.visibility == View.VISIBLE) kalan else -1)
        out.putLong("an", android.os.SystemClock.elapsedRealtime())
    }

    private fun geriYukle(b: Bundle) {
        secilenTur = b.getString("tur"); kisi = b.getInt("kisi", -1).takeIf { it >= 0 }
        ozel.clear(); ozel += b.getStringArrayList("ozel") ?: emptyList<String>()
        metin.setText(b.getString("metin") ?: "")
        yenile()
        // a countdown resumes only if the screen came back at once (font size, language); after the app was killed
        // and reopened much later the form stays filled in and nothing goes out by itself
        if (android.os.SystemClock.elapsedRealtime() - b.getLong("an", 0) < 30_000) b.getInt("kalan", -1).takeIf { it >= 0 }?.let(::geriSay)
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
                agRenk(KIRMIZI); if (canli) bluetoothIste()
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
        agRenk(if (!bt) KIRMIZI else if (internet || yakin > 0) YESIL else AMBER)
    }

    private fun durumGoster(zemin: Int, yazi: String) {
        durum.text = yazi
        durumKart.background = kutu(zemin, zemin, 16)
        if (durumKart.visibility != View.VISIBLE && hareket) { // the card arrives: fades in and rises a little
            durumKart.alpha = 0f; durumKart.translationY = dp(8).toFloat()
            durumKart.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        }
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
        turDugmeleri[secilenTur]?.let { sic(it); it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
        duyur(secilenTur?.let { "${Cagri.TURLER[it]} seçildi" } ?: "Seçim kaldırıldı")
    }

    /** Redraws the selection, the notes and the summary from the state: what is shown is always what will be sent. */
    private fun yenile() {
        for ((k, v) in turDugmeleri) {
            val secili = k == secilenTur
            // tinted when idle, filled and lifted when selected
            v.background = dalga(if (secili) kutu(KIRMIZI, KIRMIZI, 18) else kutu(KIRMIZI_ZEMIN, KIRMIZI_ZEMIN, 18), 18, koyu = secili)
            v.elevation = if (secili) dp(4).toFloat() else 0f
            v.outlineSpotShadowColor = KIRMIZI
            v.isSelected = secili
            v.stateDescription = if (secili) "seçili" else "seçili değil"
            turBasliklari[k]?.setTextColor(if (secili) Color.WHITE else KIRMIZI)
            turAltlari[k]?.apply {
                setTextColor(if (secili) Color.argb(235, 255, 255, 255) else IKINCIL)
                text = if (secili) "✓ Seçildi · kaldırmak için yine dokunun" else Cagri.ALT_YAZI[k] ?: ""
            }
        }
        for ((k, b) in ozelDugmeleri) {
            val secili = k in ozel
            b.background = dalga(if (secili) kutu(KIRMIZI_ZEMIN, KIRMIZI, 99, 1.5f) else kutu(GRI_DOLGU, GRI_DOLGU, 99), 99)
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
        ozet.text = SpannableStringBuilder("Gönderilecekler\n").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length - 1, 0)
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
        geriSay(5) // a stray tap must not send a call: five seconds with a large CANCEL, then it goes by itself
    }

    private var kalan = 5
    private var geriSayimAdimi: Runnable? = null

    private fun geriSay(saniye: Int) {
        geriSayimAdimi?.let(ana::removeCallbacks) // cancel and send again within a second: one countdown, not two
        if (geriSayim.visibility != View.VISIBLE && hareket) { geriSayim.alpha = 0f; geriSayim.animate().alpha(1f).setDuration(180).start() }
        geriSayim.visibility = View.VISIBLE
        kalan = saniye
        val adim = object : Runnable {
            override fun run() {
                if (geriSayim.visibility != View.VISIBLE) return
                if (kalan == 0) { geriSayim.visibility = View.GONE; gonder(); return }
                geriSayimSayi.text = "$kalan"; sic(geriSayimSayi)
                geriSayimYazi.text = "Çağrı $kalan saniye içinde\ngönderilecek"
                geriSayimYazi.announceForAccessibility("$kalan")
                kalan--
                ana.postDelayed(this, 1000)
            }
        }
        geriSayimAdimi = adim
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

    // motion follows the system: "remove animations" in accessibility settings turns every move off
    private val hareket get() = ValueAnimator.areAnimatorsEnabled()

    private fun kutu(renk: Int, kenar: Int, r: Int = 12, cizgi: Float = 1f) = GradientDrawable().apply {
        setColor(renk); cornerRadius = dp(r).toFloat(); if (kenar != renk) setStroke((cizgi * resources.displayMetrics.density).toInt(), kenar)
    }

    // the system press highlight, clipped to the rounded shape (also on transparent buttons)
    private fun dalga(icerik: GradientDrawable, r: Int, koyu: Boolean = false) = RippleDrawable(
        ColorStateList.valueOf(if (koyu) Color.argb(70, 255, 255, 255) else Color.argb(30, 14, 26, 34)), icerik, kutu(Color.WHITE, Color.WHITE, r))

    /** Press: a quick squeeze to 96 %, then a spring back on release (Apple's custom-button feel). */
    @SuppressLint("ClickableViewAccessibility")
    private fun basinc(v: View) = v.setOnTouchListener { _, e ->
        if (hareket) when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(2.2f)).start()
        }
        false // the click itself is handled as usual
    }

    /** A selected item answers once: grows a little and settles (snappy spring). */
    private fun sic(v: View) {
        if (!hareket) return
        v.scaleX = 1.04f; v.scaleY = 1.04f
        v.animate().scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(OvershootInterpolator(3f)).start()
    }

    private fun yazi(t: String, boy: Float, renk: Int = METIN, kalin: Boolean = false) = TextView(this).apply {
        text = t; textSize = boy; setTextColor(renk); typeface = if (kalin) AGIR else NORMAL
        setLineSpacing(0f, 1.15f)
    }

    private fun kart(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
        background = kutu(KART, KART, 20) // grouped card: white, no frame, no shadow
    }

    /** Step heading inside a card: a small numbered disc, then the question. */
    private fun adim(no: Int, t: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(yazi("$no", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = kutu(METIN, METIN, 99); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(26), dp(26)))
        addView(yazi(t, 19f, METIN, true).apply { setPadding(dp(10), 0, 0, 0); isAccessibilityHeading = true })
    }

    private fun yer(ust: Int = 12) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ust) }

    // a grid inside a card: the cells' own margins line their edges up with the card's padding
    private fun izgaraYeri(ust: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ust); leftMargin = -dp(4); rightMargin = -dp(4) }

    private fun dugme(t: String, zemin: Int, yaziRenk: Int, kenar: Int = zemin, boy: Float = 18f, r: Int = 16) = Button(this).apply {
        text = t; isAllCaps = false; textSize = boy; setTextColor(yaziRenk); typeface = AGIR; letterSpacing = 0f
        background = dalga(kutu(zemin, kenar, r, 1.5f), r, koyu = zemin == KIRMIZI || zemin == KOYU)
        minHeight = dp(56); minimumHeight = dp(56); minWidth = 0; minimumWidth = 0; setPadding(dp(16), dp(12), dp(16), dp(12))
        stateListAnimator = null; elevation = 0f // flat: no Material shadow under every button
        basinc(this)
    }

    /** Network status as a tinted capsule: the colour of the text is the colour of the pill. */
    private fun agRenk(renk: Int) {
        agSatiri.setTextColor(renk)
        agSatiri.background = kutu(Color.argb(28, Color.red(renk), Color.green(renk), Color.blue(renk)), Color.TRANSPARENT, 14, 0f)
    }

    private fun buildUi(kurumVar: Boolean) {
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(20), pad, dp(40)) }

        // header: a signal mark and the large title, where calls go, the network as a capsule
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(this@MainActivity).apply {
                background = kutu(KIRMIZI_ZEMIN, KIRMIZI_ZEMIN, 99)
                addView(View(this@MainActivity).apply { background = kutu(KIRMIZI, KIRMIZI, 99) },
                    FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(yazi("KairoMesh", 30f, METIN, true).apply { typeface = KALIN; letterSpacing = -0.01f; setPadding(dp(10), 0, 0, 0); isAccessibilityHeading = true })
        })
        kurumSatiri = yazi(if (kurumVar) "" else "Bu sürümde çağrıların gideceği kurum tanımlı değil (kurumla sözleşme öncesi sürüm).", 15f, IKINCIL)
        root.addView(kurumSatiri, yer(6))
        agSatiri = yazi("● Ağ hazırlanıyor…", 15f, AMBER, true).apply { setPadding(dp(12), dp(7), dp(12), dp(7)) }
        root.addView(agSatiri, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        agRenk(AMBER)
        root.addView(yazi("Afet yardım çağrısı", 24f, METIN, true).apply { typeface = KALIN; isAccessibilityHeading = true }, yer(22))
        root.addView(yazi("Doğal afette şebeke çöktüğünde kullanın; günlük acil durumlarda 112'yi arayın. Durumunuzu seçin ya da yazın, sonra gönderin.", 16f, IKINCIL), yer(4))

        // 1. what happened: four life-threatening situations, large
        val ne = kart()
        ne.addView(adim(1, "Ne oldu?"))
        val izgara = GridLayout(this).apply { columnCount = 2 }
        for (k in Cagri.HAYATI) {
            val baslik = yazi(Cagri.TURLER[k]!!, 19f, KIRMIZI, true)
            val alt = yazi(Cagri.ALT_YAZI[k] ?: "", 14f, IKINCIL).apply { setPadding(0, dp(4), 0, 0) }
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
                addView(baslik); addView(alt)
                basinc(this)
            }
            turDugmeleri[k] = kutuK; turBasliklari[k] = baslik; turAltlari[k] = alt
            izgara.addView(kutuK, GridLayout.LayoutParams().apply {
                width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(4), dp(4), dp(4), dp(4))
                setGravity(Gravity.FILL) // both buttons in a row as tall as the taller one
            })
        }
        ne.addView(izgara, izgaraYeri(10))

        // the selection, said in words, with an undo
        val secim = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        secimSatiri = yazi("", 17f, IKINCIL, true)
        secim.addView(secimSatiri, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        secimKaldir = dugme("Kaldır", KIRMIZI_ZEMIN, KIRMIZI, KIRMIZI_ZEMIN, 16f, 99).apply { setOnClickListener { turSec(null) }; contentDescription = "Seçimi kaldır" }
        secim.addView(secimKaldir)
        ne.addView(secim, yer(10))
        root.addView(ne, yer(16))

        // 2. how many: a value and a connected −/+ stepper, no keyboard needed
        val kac = kart()
        kac.addView(adim(2, "Kaç kişisiniz?"))
        val sayac = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val deger = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        kisiYazi = yazi("?", 34f, METIN, true).apply { typeface = KALIN }
        deger.addView(kisiYazi)
        deger.addView(yazi("? = bilinmiyor", 15f, IKINCIL))
        sayac.addView(deger, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        sayac.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = kutu(GRI_DOLGU, GRI_DOLGU, 16)
            addView(dugme("−", Color.TRANSPARENT, METIN, Color.TRANSPARENT, 28f).apply {
                contentDescription = "Bir azalt"
                setOnClickListener { kisi = kisi?.let { if (it <= 1) null else it - 1 }; yenile(); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            }, LinearLayout.LayoutParams(dp(72), dp(60)))
            addView(View(this@MainActivity).apply { setBackgroundColor(CIZGI) }, LinearLayout.LayoutParams(dp(1), dp(30)))
            addView(dugme("+", Color.TRANSPARENT, METIN, Color.TRANSPARENT, 28f).apply {
                contentDescription = "Bir artır"
                setOnClickListener { kisi = minOf(99, (kisi ?: 0) + 1); yenile(); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            }, LinearLayout.LayoutParams(dp(72), dp(60)))
        })
        kac.addView(sayac, yer(12))

        // 3. who is with you: capsules, several can be on
        kac.addView(adim(3, "Yanınızda"), yer(22))
        val ozelIzgara = GridLayout(this).apply { columnCount = 3 }
        for ((k, ad) in Cagri.OZEL) {
            val b = dugme(ad, GRI_DOLGU, METIN, GRI_DOLGU, 16f, 99).apply {
                setPadding(dp(6), dp(12), dp(6), dp(12))
                setOnClickListener { if (!ozel.remove(k)) { ozel += k; sic(this) }; yenile(); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            }
            ozelDugmeleri[k] = b
            ozelIzgara.addView(b, GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        kac.addView(ozelIzgara, izgaraYeri(8))
        root.addView(kac, yer(12))

        // 4. or write it
        val yaz = kart()
        yaz.addView(adim(4, "Ya da kısaca yazın"))
        metin = EditText(this).apply {
            hint = "Adres ve durum: ör. Antakya Ürgenpaşa Mh., enkazdayız, 3 kişi"; minLines = 2; textSize = 17f; typeface = NORMAL
            setTextColor(METIN); setHintTextColor(IKINCIL)
            // a filled field; focus draws a dark ring so the caller sees where the text goes
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), kutu(GRI_DOLGU, METIN, 14, 2f))
                addState(intArrayOf(), kutu(GRI_DOLGU, GRI_DOLGU, 14))
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = yenile()
            })
        }
        yaz.addView(metin, yer(12))
        metinNotu = yazi("", 15f, AMBER, true)
        yaz.addView(metinNotu, yer(8))
        root.addView(yaz, yer(12))

        // exactly what will go, then send
        val gonderKart = kart()
        ozet = yazi("", 16f, METIN).apply { background = kutu(GRI_DOLGU, GRI_DOLGU, 14); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        gonderKart.addView(ozet)
        gonderKart.addView(dugme("Çağrıyı gönder", KIRMIZI, Color.WHITE, KIRMIZI, 22f, 20).apply {
            minHeight = dp(72); minimumHeight = dp(72); typeface = KALIN
            elevation = dp(6).toFloat(); outlineSpotShadowColor = KIRMIZI; outlineAmbientShadowColor = KIRMIZI // the one lifted control
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CONFIRM); gonderBas() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14); bottomMargin = dp(6) })
        durumKart = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); visibility = View.GONE }
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
        durumKart.addView(duzeltDugme, yer(12))
        // one tap undoes a correction or "I am safe" sent by mistake: the earlier call goes out again at once
        geriAlDugme = dugme("Geri al · hâlâ yardım gerekiyor", KIRMIZI, Color.WHITE, KIRMIZI, 17f).apply {
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE; toService(Intent().putExtra("geriAl", "1")) }
        }
        durumKart.addView(geriAlDugme, yer(10))
        gonderKart.addView(durumKart, yer(12))
        root.addView(gonderKart, yer(12))

        // sound signal and "I am safe"
        root.addView(dugme("♪ Sesli sinyal · kurtarıcılar sizi duysun", KART, METIN, KART, 17f, 18).apply {
            minHeight = dp(60); minimumHeight = dp(60); setOnClickListener { sinyal(this) }
        }, yer(16))
        root.addView(dugme("✓ Güvendeyim · kuruma bildir", YESIL_ZEMIN, YESIL, YESIL_ZEMIN, 17f, 18).apply {
            minHeight = dp(60); minimumHeight = dp(60)
            setOnClickListener {
                secilenTur = "guvende"; yenile(); gonderBas() // one tap: "I am safe" never sends anything urgent
            }
        }, yer(10))

        // announcements
        val duyuru = kart()
        duyuru.addView(yazi("Kurum duyuruları", 19f, METIN, true).apply { isAccessibilityHeading = true })
        duyurular = yazi("Henüz duyuru yok.", 16f, IKINCIL)
        duyuru.addView(duyurular, yer(6))
        root.addView(duyuru, yer(16))

        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(IKINCIL); setPadding(0, dp(16), 0, 0)
            // the technical log is for development only: users see plain Turkish above
            visibility = if (BuildConfig.OTOMASYON) View.VISIBLE else View.GONE
        }
        root.addView(log)

        // countdown over everything: the seconds in a large ring, a large CANCEL, or send now
        geriSayim = FrameLayout(this).apply {
            visibility = View.GONE; setBackgroundColor(KOYU); isClickable = true
            val ic = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), 0) }
            geriSayimSayi = yazi("5", 76f, Color.WHITE, true).apply {
                typeface = KALIN; gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(40, 179, 38, 30)); setStroke(dp(4), KIRMIZI_ACIK) }
            }
            ic.addView(geriSayimSayi, LinearLayout.LayoutParams(dp(168), dp(168)))
            geriSayimYazi = yazi("", 24f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
            ic.addView(geriSayimYazi, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) })
            ic.addView(dugme("İptal et", Color.WHITE, KIRMIZI, Color.WHITE, 30f, 24).apply {
                minHeight = dp(96); minimumHeight = dp(96); typeface = KALIN
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.REJECT)
                    geriSayim.visibility = View.GONE; durumGoster(GRI_ZEMIN, "Çağrı iptal edildi. Hiçbir şey gönderilmedi.")
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(32) })
            ic.addView(dugme("Hemen gönder", KOYU, Color.WHITE, Color.argb(150, 255, 255, 255), 20f, 24).apply { setOnClickListener { gonder() } },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
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
        // filled grey controls, near-black text (16:1 on the cards; secondary text 9:1, readable on a cracked screen),
        // one accent: red, only on emergency elements; green only for good news
        val ZEMIN_UST = Color.rgb(242, 242, 247); val ZEMIN_ALT = Color.rgb(228, 228, 234)
        val KART = Color.WHITE; val CIZGI = Color.rgb(199, 199, 204); val GRI_DOLGU = Color.rgb(239, 239, 244)
        val METIN = Color.rgb(28, 28, 30); val IKINCIL = Color.rgb(72, 72, 74); val KOYU = Color.rgb(14, 26, 34)
        val KIRMIZI = Color.rgb(179, 38, 30); val KIRMIZI_ACIK = Color.rgb(255, 105, 97); val KIRMIZI_ZEMIN = Color.rgb(253, 236, 234)
        val YESIL = Color.rgb(11, 110, 79); val YESIL_ZEMIN = Color.rgb(221, 240, 231); val AMBER = Color.rgb(138, 90, 0)
        val GRI_ZEMIN = Color.rgb(228, 232, 236)
        val NORMAL: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)
        val AGIR: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        val KALIN: Typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    }
}
