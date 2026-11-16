package com.kairodyne.mesh

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.kairodyne.mesh.core.KurumKodu
import kotlin.concurrent.thread

/**
 * The authority this phone sends emergency calls to: its sealing key, its announcement-signing key, its server and
 * its name. Given as one "authority code", accepted only when approved by a root built into the app (KurumKodu).
 */
class Kurum(val pub: ByteArray, val signPub: ByteArray, val url: String, val ad: String, val code: String) {

    /** POST the envelope; calls back (on a worker thread) with the 16-byte receipt, or null to retry later. */
    fun upload(env: ByteArray, done: (ByteArray?) -> Unit) = thread(name = "kairo-uplink") {
        done(runCatching {
            (URL("$url/v1/acil").openConnection() as HttpURLConnection).run {
                requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("content-type", "application/octet-stream")
                outputStream.use { it.write(env) }
                if (responseCode == 200) inputStream.use { it.readBytes() }.takeIf { it.size == 16 } else null
            }
        }.getOrNull())
    }

    /** GET the authority's current signed announcements. */
    fun announcements(done: (List<ByteArray>) -> Unit) = thread(name = "kairo-downlink") {
        done(runCatching {
            (URL("$url/v1/duyuru").openConnection() as HttpURLConnection).run {
                connectTimeout = 10_000; readTimeout = 15_000
                if (responseCode != 200) return@run emptyList()
                val a = JSONArray(inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                (0 until a.length()).map { hex(a.getString(it)) }
            }
        }.getOrDefault(emptyList()))
    }

    companion object {
        private val KOKLER = BuildConfig.KOKLER.split(",").map { Base64.decode(it, Base64.NO_WRAP) }

        /** Null for a malformed code or one no trusted root approved (a fake code is never stored). */
        fun parse(code: String): Kurum? =
            KurumKodu.ac(code, KOKLER)
                // release builds talk to authority servers only over https (debug: http for the emulator test)
                ?.takeIf { BuildConfig.DEBUG || it.url.startsWith("https://") }
                ?.let { Kurum(it.pub, it.signPub, it.url, it.ad, code.trim()) }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}

/** What the caller chose on the emergency screen, as the JSON record the authority receives. */
object Cagri {
    // Same table and rule as triyaj/uret.py and the on-device model: base urgency per type,
    // one level more urgent when a baby, an injured person or a pregnant woman is present.
    // Life-threatening situations first: these are the four large buttons (docs/08-acil-ekran.md). The rest are
    // needs, not emergencies: smaller buttons below. "yardim" is "another emergency", told in the text.
    val TURLER = linkedMapOf(
        "enkaz" to "Enkaz altındayım", "saglik" to "Yaralı / hasta var", "yangin" to "Yangın / gaz",
        "yardim" to "Başka acil durum",
        "barinma" to "Açıkta kaldık", "gida_su" to "Su / yiyecek yok", "kayip" to "Yakınımı arıyorum",
        "guvende" to "Güvendeyim",
    )
    val HAYATI = listOf("enkaz", "saglik", "yangin", "yardim")
    val ALT_YAZI = mapOf("enkaz" to "Bina çöktü, çıkamıyorum", "saglik" to "Acil sağlık yardımı gerekiyor",
        "yangin" to "Ateş, duman ya da gaz kokusu", "yardim" to "Ne olduğunu yazarak anlatın")
    val OZEL = linkedMapOf(
        "bebek" to "Bebek", "cocuk" to "Çocuk", "yasli" to "Yaşlı", "yarali" to "Yaralı",
        "hamile" to "Hamile", "engelli" to "Engelli",
    )
    private val TEMEL = mapOf("enkaz" to 1, "yangin" to 1, "saglik" to 2, "yardim" to 2, "kayip" to 2, "barinma" to 3, "gida_su" to 3, "guvende" to 4)

    fun aciliyet(tur: String, ozel: Set<String>): Int {
        val a = TEMEL[tur] ?: 4
        return if (a > 1 && tur != "guvende" && ozel.any { it in setOf("bebek", "yarali", "hamile") }) a - 1 else a
    }

    /** `adres`: il, ilçe, mahalle, sokak, bina read from the message by the on-device model, when there was text. */
    /** "I sent that by mistake / I am safe now": points the authority at the earlier call [ref]. */
    fun duzeltme(ref: String, zaman: Long) = JSONObject().apply {
        put("v", 1); put("tur", "iptal"); put("aciliyet", 4); put("iptal_ref", ref); put("zaman", zaman)
        put("metin", "Önceki çağrı yanlışlıkla gönderildi ya da artık yardım gerekmiyor.")
    }.toString()

    fun rapor(tur: String, kisi: Int?, ozel: Set<String>, metin: String, enlem: Double?, boylam: Double?, dogruluk: Float?, zaman: Long,
              adres: Map<String, String?> = emptyMap(), aciliyet: Int? = null, model: Boolean = false) =
        JSONObject().apply {
            put("v", 1); put("tur", tur); put("aciliyet", minOf(aciliyet ?: 4, aciliyet(tur, ozel)))
            if (kisi != null) put("kisi", kisi)
            for ((k, v) in adres) if (!v.isNullOrBlank()) put(k, v)
            if (model) put("kaynak", "telefon-modeli")
            put("ozel", JSONArray(ozel.sorted()))
            if (metin.isNotBlank()) put("metin", metin.take(500))
            if (enlem != null && boylam != null) put("konum", JSONObject().apply {
                put("enlem", enlem); put("boylam", boylam); dogruluk?.let { put("dogruluk_m", it.toDouble()) }; put("zaman", zaman)
            })
        }.toString()
}
