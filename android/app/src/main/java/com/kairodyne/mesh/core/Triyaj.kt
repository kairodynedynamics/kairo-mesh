package com.kairodyne.mesh.core

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/**
 * On-device triage: turns a rushed, misspelt Turkish help message into a structured record. Port of the forward
 * pass and decoding in triyaj/model.py (character embedding + case feature, 2-layer bidirectional GRU, per-character
 * span tags and pooled heads). No dependencies; about 3.4 MB of weights; checked against PyTorch outputs
 * (triyaj/telefon/altin.json).
 *
 * Text is handled as Unicode code points, as Python does, so spans line up with the original message even when it
 * contains emoji.
 */
class Triyaj(meta: String, weights: ByteArray, private val kodlayici: Kodlayici? = null) {
    /** Which model made the message-level decisions: the Turkish encoder, or the character model alone. */
    val modelAdi get() = if (kodlayici != null) "kodlayici" else "harf"
    class Kayit(
        val tur: String, val aciliyet: Int, val kisi: Int?, val il: String?, val ilce: String?,
        val mahalle: String?, val sokak: String?, val bina: String?, val ad: String?, val tel: String?, val ozel: List<String>,
    )

    private val m = JSONObject(meta)
    private val harfler = m.getJSONArray("harfler").let { a -> (0 until a.length()).associate { a.getString(it).codePointAt(0) to it + 2 } }
    private val uzun = m.getInt("uzun")
    private val alanlar = m.getJSONArray("alanlar").strings()
    private val turler = m.getJSONArray("turler").strings()
    private val ozeller = m.getJSONArray("ozeller").strings()
    private val iller = m.getJSONArray("iller").strings()
    private val ciftler = m.getJSONArray("ciftler").strings()
    private val temel = m.getJSONObject("temel_aciliyet").let { o -> o.keys().asSequence().associateWith { o.getInt(it) } }
    private val sayiYazi = m.getJSONObject("sayi_yazi").let { o -> o.keys().asSequence().associateWith { o.getInt(it) } }
    private val w: Map<String, FloatArray>
    private val harfBoy: Int
    private val tipBoy: Int
    private val gizli2: Int // 2 x GRU hidden size: width of each layer's output

    init {
        val fb = ByteBuffer.wrap(weights).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val t = m.getJSONArray("tensorler")
        w = (0 until t.length()).associate { i ->
            val o = t.getJSONObject(i)
            val sekil = o.getJSONArray("sekil")
            val n = (0 until sekil.length()).fold(1) { a, j -> a * sekil.getInt(j) }
            val arr = FloatArray(n)
            fb.position(o.getInt("ofs")); fb.get(arr)
            o.getString("ad") to arr
        }
        harfBoy = w.getValue("harf.weight").size / (harfler.size + 2)
        tipBoy = w.getValue("tip.weight").size / 3
        gizli2 = 2 * w.getValue("gru.bias_ih_l0").size / 3
    }

    private fun org.json.JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun kucuk(cp: Int): Int = when (cp) {
        'İ'.code -> 'i'.code
        'I'.code -> 'ı'.code
        else -> Character.toLowerCase(cp)
    }

    private fun tip(cp: Int) = if (Character.isUpperCase(cp)) 2 else if (Character.isLetter(cp)) 1 else 0

    /** Raw outputs: per-character tag logits [n][17] and the five head logit vectors. Public for the golden test. */
    fun ham(metin: String): List<Any> {
        val cps = metin.codePoints().toArray().take(uzun)
        val n = cps.size
        val x = Array(n) { i ->
            val h = harfler[kucuk(cps[i])] ?: 1 // 0 padding, 1 unknown character
            val tp = tip(cps[i])
            FloatArray(harfBoy + tipBoy).also { v ->
                System.arraycopy(w.getValue("harf.weight"), h * harfBoy, v, 0, harfBoy)
                System.arraycopy(w.getValue("tip.weight"), tp * tipBoy, v, harfBoy, tipBoy)
            }
        }
        val l0 = katman(x, "l0")
        val y = katman(l0, "l1")
        val etiket = Array(n) { lin(y[it], "etiket") }
        // pooled: mean and max over characters
        val p = FloatArray(2 * gizli2)
        for (j in 0 until gizli2) {
            var s = 0f; var mx = Float.NEGATIVE_INFINITY
            for (i in 0 until n) { s += y[i][j]; mx = max(mx, y[i][j]) }
            p[j] = if (n > 0) s / n else 0f
            p[gizli2 + j] = if (n > 0) mx else -1e4f
        }
        return listOf(etiket, lin(p, "tur"), lin(p, "aciliyet"), lin(p, "ozel"), lin(p, "il"), lin(p, "ilce"))
    }

    private fun lin(v: FloatArray, ad: String): FloatArray {
        val W = w.getValue("$ad.weight"); val b = w.getValue("$ad.bias")
        val out = b.size
        return FloatArray(out) { o -> var s = b[o]; val off = o * v.size; for (i in v.indices) s += W[off + i] * v[i]; s }
    }

    private fun katman(x: Array<FloatArray>, l: String): Array<FloatArray> {
        val ileri = yon(x, "gru.weight_ih_$l", "gru.weight_hh_$l", "gru.bias_ih_$l", "gru.bias_hh_$l", false)
        val geri = yon(x, "gru.weight_ih_${l}_reverse", "gru.weight_hh_${l}_reverse", "gru.bias_ih_${l}_reverse", "gru.bias_hh_${l}_reverse", true)
        return Array(x.size) { i -> ileri[i] + geri[i] }
    }

    private fun sig(v: Float) = (1.0 / (1.0 + exp(-v.toDouble()))).toFloat()

    /** One direction of a PyTorch GRU layer (gate order r, z, n). */
    private fun yon(x: Array<FloatArray>, wi: String, wh: String, bi: String, bh: String, ters: Boolean): Array<FloatArray> {
        val Wi = w.getValue(wi); val Wh = w.getValue(wh); val Bi = w.getValue(bi); val Bh = w.getValue(bh)
        val h3 = Bi.size; val hs = h3 / 3; val inp = Wi.size / h3
        var h = FloatArray(hs)
        val out = Array(x.size) { FloatArray(hs) }
        val gi = FloatArray(h3); val gh = FloatArray(h3)
        for (s in x.indices) {
            val t = if (ters) x.size - 1 - s else s
            val xt = x[t]
            for (o in 0 until h3) {
                var a = Bi[o]; val oi = o * inp; for (i in 0 until inp) a += Wi[oi + i] * xt[i]; gi[o] = a
                var b = Bh[o]; val oh = o * hs; for (i in 0 until hs) b += Wh[oh + i] * h[i]; gh[o] = b
            }
            val yeni = FloatArray(hs)
            for (k in 0 until hs) {
                val r = sig(gi[k] + gh[k])
                val z = sig(gi[hs + k] + gh[hs + k])
                val nn = tanh((gi[2 * hs + k] + r * gh[2 * hs + k]).toDouble()).toFloat()
                yeni[k] = (1 - z) * nn + z * h[k]
            }
            h = yeni
            out[t] = yeni
        }
        return out
    }

    private fun argmax(v: FloatArray): Int { var b = 0; for (i in v.indices) if (v[i] > v[b]) b = i; return b }

    fun coz(metin: String): Kayit {
        @Suppress("UNCHECKED_CAST")
        val ham = ham(metin)
        val etiket = ham[0] as Array<FloatArray>
        val cps = metin.codePoints().toArray()
        fun dilim(a: Int, b: Int) = String(cps, a, b - a).trim()
        val etiketler = etiket.map { argmax(it) }
        val aralik = HashMap<String, Pair<Int, Int>>()
        var j = 0
        while (j < etiketler.size) {
            val k = etiketler[j]
            if (k != 0 && k % 2 == 1) {
                val alan = alanlar[(k - 1) / 2]
                val s = j++
                while (j < etiketler.size && etiketler[j] == k + 1) j++
                aralik.putIfAbsent(alan, s to j)
            } else j++
        }
        fun al(a: String) = aralik[a]?.let { dilim(it.first, it.second) }
        val kisi = al("kisi")?.let { k ->
            k.toIntOrNull() ?: run {
                val kn = k.map { if (it == 'İ') 'i' else if (it == 'I') 'ı' else it.lowercaseChar() }.joinToString("")
                sayiYazi[kn] ?: sayiYazi[ascii(kn)]
            }
        }
        val tel = Regex("0?5\\d{2}\\s?\\d{3}\\s?\\d{2}\\s?\\d{2}").find(metin)?.let { "0" + it.value.filter(Char::isDigit).trimStart('0') }
        // message-level decisions: the encoder when present (triyaj/model/kodlayici-olcum.json), else the character model
        val bas = kodlayici?.ham(metin) ?: listOf(ham[1], ham[2], ham[3], ham[4], ham[5]).map { it as FloatArray }
        val ilI = argmax(bas[3])
        if (kodlayici != null && ilI > 0) { // the district only among the predicted province's districts, as measured
            val ilAd = iller[ilI - 1]
            for (x in 1..ciftler.size) if (!ciftler[x - 1].startsWith("$ilAd/")) bas[4][x] = -1e9f
        }
        val ilceI = argmax(bas[4])
        var il = if (ilI > 0) iller[ilI - 1] else null
        var ilce: String? = null
        if (ilceI > 0) {
            val (il2, ic) = ciftler[ilceI - 1].split("/", limit = 2)
            if (il == null || il == il2) { il = il2; ilce = ic }
        }
        val tur = turler[argmax(bas[0])]
        val ozel = bas[2].withIndex().filter { it.value > 0 }.map { ozeller[it.index] }.sorted()
        val kural = (temel[tur] ?: 4).let { a ->
            if (a > 1 && tur !in setOf("guvende", "bilgi", "alakasiz") && ozel.any { it in setOf("bebek", "yarali", "hamile") }) a - 1 else a
        }
        // safety rule: the model may never rank a call as less urgent than the rule does
        val aciliyet = min(argmax(bas[1]) + 1, kural)
        return Kayit(tur, aciliyet, kisi, il, ilce, al("mahalle"), al("sokak"), al("bina"), al("ad"), tel, ozel)
    }

    private fun ascii(s: String) = s.map { "çğıöşüÇĞİÖŞÜâÂîû".indexOf(it).let { i -> if (i < 0) it else "cgiosuCGIOSUaAiu"[i] } }.joinToString("")
}
