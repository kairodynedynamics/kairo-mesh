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
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The screen: an emergency call that needs no typing (tap the situation, optionally the number of people and who is
 * with you), the authority's announcements, and below them settings and the plain chat. The mesh runs in MeshService.
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
    private lateinit var duyurular: TextView
    private var waiting: Intent? = null // extras that arrived before Bluetooth permission was granted
    private var secilenTur: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
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
        super.onDestroy()
    }

    private fun onLine(line: String) {
        log.append(line + "\n")
        when {
            line.startsWith("ACIL_GONDERILDI") -> durum.text = "Çağrınız gönderildi (${saat(line)}). Yakındaki telefonlar taşıyor; internete ulaşan ilk telefon kuruma iletecek."
            line.startsWith("ACIL_ULASTI") -> durum.text = "✓ KURUM ÇAĞRINIZI ALDI (${saat(line)})."
            line.startsWith("DUYURU ") -> duyurular.text = line.removePrefix("DUYURU ") + "\n\n" + duyurular.text.toString().removePrefix("Henüz duyuru yok.")
            line.startsWith("KURUM ") -> durum.text = "Çağrılarınız şu kuruma gider: ${line.substringAfter(" ad=")}"
            line.startsWith("ERROR kurum kodu geçersiz") -> durum.text =
                "Bu kurum kodu yetkili makamca onaylanmamış ya da bozuk; kaydedilmedi. Kodu yalnızca kurumun resmî kanalından alın."
            line.startsWith("ERROR kurum") -> durum.text = "Kurum kodu tanımlı değil: çağrının gideceği kurumun kodunu Ayarlar'a girin."
        }
    }

    private fun saat(line: String) = line.substringAfter("saat=", "").substringBefore(" ")

    // This activity is exported, so any installed app can start it with extras. Honouring "add" there would let it
    // swap a contact's key for its own and read that contact's mail: the extras exist for test automation only.
    private fun automation(intent: Intent?) =
        intent?.takeIf { applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 }

    private fun granted() = BLUETOOTH.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun toService(extras: Intent? = null) {
        if (!granted()) { waiting = extras; return }
        startForegroundService(Intent(this, MeshService::class.java).apply { extras?.extras?.let(::putExtras) })
    }

    private fun requestPermissionsOnce() {
        if (granted()) toService(waiting)
        // notifications and location are optional: without location the call goes without coordinates
        else requestPermissions(BLUETOOTH + Manifest.permission.POST_NOTIFICATIONS + Manifest.permission.ACCESS_FINE_LOCATION +
            Manifest.permission.ACCESS_COARSE_LOCATION, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (granted()) toService(waiting)
        else log.append("ERROR Bluetooth izni verilmedi; ağ çalışamaz\n")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun kutu(renk: Int, kenar: Int) = GradientDrawable().apply { setColor(renk); cornerRadius = dp(12).toFloat(); setStroke(dp(2), kenar) }

    private fun buildUi(code: String, kurumVar: Boolean) {
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        fun field(hint: String, sayi: Boolean = false) = EditText(this).apply {
            this.hint = hint; isSingleLine = true; if (sayi) inputType = InputType.TYPE_CLASS_NUMBER
        }
        fun extra(key: String, value: String) = toService(Intent().putExtra(key, value))
        fun baslik(t: String) = TextView(this).apply { text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(20), 0, dp(6)) }

        root.addView(TextView(this).apply { text = "Kairo Mesh"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })

        // --- emergency call ---------------------------------------------------
        val acil = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); background = kutu(Color.rgb(251, 233, 231), Color.rgb(179, 38, 30))
        }
        acil.addView(TextView(this).apply { text = "ACİL YARDIM ÇAĞRISI"; textSize = 20f; setTextColor(Color.rgb(179, 38, 30)); setTypeface(typeface, Typeface.BOLD) })
        acil.addView(TextView(this).apply {
            text = if (kurumVar) "İnternet olmasa da çalışır: çağrınız yakındaki telefonlar üzerinden kuruma taşınır. Konumunuz eklenir."
            else "Çağrının gideceği kurum tanımlı değil. Kurumdan aldığınız kodu aşağıda Ayarlar'a girin."
            setPadding(0, dp(4), 0, dp(8))
        })
        val turlar = GridLayout(this).apply { columnCount = 2 }
        val turDugmeleri = mutableListOf<Button>()
        for ((anahtar, ad) in Cagri.TURLER) {
            val b = Button(this).apply {
                text = ad; isAllCaps = false; textSize = 16f
                setOnClickListener {
                    secilenTur = anahtar
                    turDugmeleri.forEach { d -> d.background = kutu(Color.WHITE, Color.LTGRAY); d.setTextColor(Color.BLACK) }
                    background = kutu(Color.rgb(179, 38, 30), Color.rgb(179, 38, 30)); setTextColor(Color.WHITE)
                }
                background = kutu(Color.WHITE, Color.LTGRAY)
            }
            turDugmeleri += b
            turlar.addView(b, GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        acil.addView(turlar)
        val kisi = field("Kaç kişisiniz? (isteğe bağlı)", sayi = true)
        acil.addView(kisi)
        acil.addView(TextView(this).apply { text = "Yanınızda:"; setPadding(0, dp(8), 0, 0) })
        val ozelKutular = Cagri.OZEL.map { (anahtar, ad) -> anahtar to CheckBox(this).apply { text = ad } }
        val ozelSatir = GridLayout(this).apply { columnCount = 3 }
        ozelKutular.forEach { ozelSatir.addView(it.second) }
        acil.addView(ozelSatir)
        val metin = EditText(this).apply { hint = "Ya da yazın: adres, durum (ör. antakya ürgenpaşa mh enkazdayız 3 kişi)"; minLines = 2 }
        acil.addView(metin)
        acil.addView(Button(this).apply {
            text = "ÇAĞRIYI GÖNDER"; textSize = 20f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            background = kutu(Color.rgb(179, 38, 30), Color.rgb(179, 38, 30)); setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                // a situation or some text is enough: the phone reads the text when nothing was tapped
                val tur = secilenTur ?: if (metin.text.isNotBlank()) "" else return@setOnClickListener run { durum.text = "Durumunuzu seçin ya da kısaca yazın." }
                val ozel = ozelKutular.filter { it.second.isChecked }.joinToString(",") { it.first }
                extra("acil", "$tur|${kisi.text.toString().trim()}|$ozel|${metin.text.toString().replace("|", " ")}")
                durum.text = "Çağrı hazırlanıyor…"
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        durum = TextView(this).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(10), 0, 0); gravity = Gravity.START }
        acil.addView(durum)
        root.addView(acil)

        // --- announcements ------------------------------------------------------
        root.addView(baslik("Kurum duyuruları"))
        duyurular = TextView(this).apply { text = "Henüz duyuru yok."; textSize = 15f }
        root.addView(duyurular)

        // --- settings and chat ---------------------------------------------------
        root.addView(baslik("Ayarlar"))
        val kurumKodu = field("Kurum kodu")
        root.addView(kurumKodu)
        root.addView(Button(this).apply { text = "Kurum kodunu kaydet"; isAllCaps = false; setOnClickListener { extra("kurum", kurumKodu.text.toString().trim()) } })
        root.addView(TextView(this).apply { text = "Eşleşme kodunuz (kopyalamak için dokunun):\n$code"; setTextIsSelectable(true); setPadding(0, dp(8), 0, 0)
            setOnClickListener { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Kairo kodu", code)) } })
        val name = field("Kişi adı")
        val pairing = field("Kişinin eşleşme kodu")
        root.addView(name); root.addView(pairing)
        root.addView(Button(this).apply { text = "Kişi ekle"; isAllCaps = false; setOnClickListener { extra("add", "${name.text.toString().trim()}=${pairing.text.toString().trim()}") } })
        root.addView(baslik("Mesajlaşma"))
        val to = field("Alıcı (kişi adı)")
        val message = field("Mesaj")
        root.addView(to); root.addView(message)
        root.addView(Button(this).apply { text = "Gönder"; isAllCaps = false; setOnClickListener { extra("send", "${to.text.toString().trim()}=${message.text}"); message.text.clear() } })
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setPadding(0, dp(16), 0, 0); visibility = View.VISIBLE }
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root) }, android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private companion object {
        val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    }
}
