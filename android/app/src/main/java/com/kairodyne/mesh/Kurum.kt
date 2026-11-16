package com.kairodyne.mesh

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * The authority this phone sends emergency calls to: its sealing key, its announcement-signing key and its server.
 * Given as one "authority code" by the authority (112, AFAD, a municipality, a pilot partner):
 *   <sealing key, base64> ; <signing key, base64> ; <https server URL>
 */
class Kurum(val pub: ByteArray, val signPub: ByteArray, val url: String) {
    val code get() = listOf(b64(pub), b64(signPub), url).joinToString(";")

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
        fun parse(code: String): Kurum? = runCatching {
            val p = code.trim().split(";")
            require(p.size == 3 && p[2].startsWith("http"))
            Kurum(b64(p[0]).also { require(it.size == 32) }, b64(p[1]).also { require(it.size == 32) }, p[2].trimEnd('/'))
        }.getOrNull()

        private fun b64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
        private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}

/** What the caller chose on the emergency screen, as the JSON record the authority receives. */
object Cagri {
    // Same table and rule as triyaj/uret.py and the on-device model: base urgency per type,
    // one level more urgent when a baby, an injured person or a pregnant woman is present.
    val TURLER = linkedMapOf(
        "enkaz" to "Enkaz altındayım", "saglik" to "Yaralı / hasta var", "yangin" to "Yangın",
        "barinma" to "Açıkta kaldık", "gida_su" to "Su / yiyecek yok", "kayip" to "Yakınımı arıyorum",
        "guvende" to "Güvendeyim",
    )
    val OZEL = linkedMapOf(
        "bebek" to "Bebek", "cocuk" to "Çocuk", "yasli" to "Yaşlı", "yarali" to "Yaralı",
        "hamile" to "Hamile", "engelli" to "Engelli",
    )
    private val TEMEL = mapOf("enkaz" to 1, "yangin" to 1, "saglik" to 2, "kayip" to 2, "barinma" to 3, "gida_su" to 3, "guvende" to 4)

    fun aciliyet(tur: String, ozel: Set<String>): Int {
        val a = TEMEL[tur] ?: 4
        return if (a > 1 && tur != "guvende" && ozel.any { it in setOf("bebek", "yarali", "hamile") }) a - 1 else a
    }

    /** `adres`: il, ilçe, mahalle, sokak, bina read from the message by the on-device model, when there was text. */
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
