package com.kairodyne.mesh.core

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The pre-trained Turkish encoder (ELECTRA-small, dbmdz, MIT; fine-tuned in triyaj/kodlayici.py) that makes the
 * message-level decisions: type, urgency, special status, province, district. It knows words it never saw in
 * training ("harabenin altındayız" -> enkaz), which the character model cannot. No dependencies: a cased BERT
 * WordPiece tokenizer and the encoder's forward pass. Weights are float16 on disk (27.5 MB); everything except the
 * word embeddings is widened to float32 on load (~38 MB), word embedding rows are widened as they are used.
 * Checked against PyTorch (triyaj/telefon/kodlayici-altin.json).
 */
class Kodlayici(meta: String, weights: ByteArray) {
    private val m = JSONObject(meta)
    private val uzun = m.getInt("uzun")
    private val katman = m.getInt("katman")
    private val bas = m.getInt("bas")
    private val eps = m.getDouble("eps").toFloat()
    private val sozluk = HashMap<String, Int>()
    private val unk: Int
    private val cls: Int
    private val sep: Int
    private val w = HashMap<String, FloatArray>()
    private val kelime: ShortArray // word embeddings, float16 bits
    private val gomme: Int // embedding width (128)

    init {
        val s = m.getJSONArray("sozluk")
        for (i in 0 until s.length()) sozluk[s.getString(i)] = i
        unk = sozluk.getValue("[UNK]"); cls = sozluk.getValue("[CLS]"); sep = sozluk.getValue("[SEP]")
        val sb = ByteBuffer.wrap(weights).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val t = m.getJSONArray("tensorler")
        var kel = ShortArray(0)
        var g = 0
        for (i in 0 until t.length()) {
            val o = t.getJSONObject(i)
            val sekil = o.getJSONArray("sekil")
            val n = (0 until sekil.length()).fold(1) { a, j -> a * sekil.getInt(j) }
            val ham = ShortArray(n)
            sb.position(o.getInt("ofs")); sb.get(ham)
            val ad = o.getString("ad").removePrefix("govde.")
            if (ad == "embeddings.word_embeddings.weight") { kel = ham; g = sekil.getInt(1) }
            else w[ad] = FloatArray(n) { yarim(ham[it]) }
        }
        kelime = kel; gomme = g
    }

    // ---- tokenizer: BertNormalizer(clean_text, chinese chars, no lowercasing, no accent stripping),
    // BertPreTokenizer (whitespace, punctuation isolated), WordPiece (greedy longest match, "##", 100 chars max)

    private fun kontrol(cp: Int) = when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.UNASSIGNED, Character.PRIVATE_USE, Character.SURROGATE -> true
        else -> false
    }

    private fun bosluk(cp: Int) = cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
        Character.getType(cp).toByte() == Character.SPACE_SEPARATOR

    private fun noktalama(cp: Int) = (cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126) ||
        when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
            else -> false
        }

    private fun cince(cp: Int) = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F ||
        cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

    private fun kelimeler(metin: String): List<IntArray> {
        val out = ArrayList<IntArray>()
        val cur = ArrayList<Int>()
        fun bitir() { if (cur.isNotEmpty()) { out.add(cur.toIntArray()); cur.clear() } }
        for (cp in metin.codePoints().toArray()) {
            when {
                cp == 0 || cp == 0xFFFD -> {}
                bosluk(cp) -> bitir()
                kontrol(cp) -> {}
                noktalama(cp) || cince(cp) -> { bitir(); out.add(intArrayOf(cp)) }
                else -> cur.add(cp)
            }
        }
        bitir()
        return out
    }

    /** Token ids as the training tokenizer gives them: [CLS] pieces… [SEP], cut to [uzun]. Public for the golden test. */
    fun belirtecler(metin: String): IntArray {
        val ids = ArrayList<Int>().apply { add(cls) }
        for (k in kelimeler(metin)) {
            if (k.size > 100) { ids.add(unk); continue }
            val parca = ArrayList<Int>()
            var bas = 0
            var bilinmez = false
            while (bas < k.size) {
                var son = k.size
                var bulunan = -1
                while (son > bas) {
                    val alt = (if (bas > 0) "##" else "") + String(k, bas, son - bas)
                    val id = sozluk[alt]
                    if (id != null) { bulunan = id; break }
                    son--
                }
                if (bulunan < 0) { bilinmez = true; break }
                parca.add(bulunan); bas = son
            }
            if (bilinmez) ids.add(unk) else ids.addAll(parca)
        }
        val kes = ids.take(uzun - 1).toMutableList()
        kes.add(sep)
        return kes.toIntArray()
    }

    // ---- forward pass

    private fun lin(x: FloatArray, ad: String): FloatArray {
        val W = w.getValue("$ad.weight"); val b = w.getValue("$ad.bias")
        val n = x.size
        return FloatArray(b.size) { o -> var s = b[o]; val off = o * n; for (i in 0 until n) s += W[off + i] * x[i]; s }
    }

    private fun norm(x: FloatArray, ad: String): FloatArray {
        val g = w.getValue("$ad.weight"); val b = w.getValue("$ad.bias")
        var ort = 0f; for (v in x) ort += v; ort /= x.size
        var var_ = 0f; for (v in x) var_ += (v - ort) * (v - ort); var_ /= x.size
        val r = 1f / sqrt(var_ + eps)
        return FloatArray(x.size) { (x[it] - ort) * r * g[it] + b[it] }
    }

    private fun topla(a: FloatArray, b: FloatArray) = FloatArray(a.size) { a[it] + b[it] }

    private fun gelu(x: Float): Float = (0.5 * x * (1 + erf(x / 1.4142135623730951))).toFloat()

    /** Raw logits of the five heads (tür, aciliyet, özel, il, ilçe) for [metin]. */
    fun ham(metin: String): List<FloatArray> {
        val ids = belirtecler(metin)
        val n = ids.size
        val pos = w.getValue("embeddings.position_embeddings.weight"); val tip = w.getValue("embeddings.token_type_embeddings.weight")
        var x = Array(n) { i ->
            val e = FloatArray(gomme) { j -> yarim(kelime[ids[i] * gomme + j]) + pos[i * gomme + j] + tip[j] }
            lin(norm(e, "embeddings.LayerNorm"), "embeddings_project")
        }
        val h = x[0].size; val d = h / bas
        val olcek = 1f / sqrt(d.toFloat())
        for (l in 0 until katman) {
            val p = "encoder.layer.$l"
            val q = Array(n) { lin(x[it], "$p.attention.self.query") }
            val k = Array(n) { lin(x[it], "$p.attention.self.key") }
            val v = Array(n) { lin(x[it], "$p.attention.self.value") }
            val baglam = Array(n) { FloatArray(h) }
            val skor = FloatArray(n)
            for (b in 0 until bas) {
                val o = b * d
                for (i in 0 until n) {
                    var mx = Float.NEGATIVE_INFINITY
                    for (j in 0 until n) { var s = 0f; for (c in 0 until d) s += q[i][o + c] * k[j][o + c]; s *= olcek; skor[j] = s; if (s > mx) mx = s }
                    var top = 0f
                    for (j in 0 until n) { skor[j] = exp(skor[j] - mx); top += skor[j] }
                    for (j in 0 until n) { val a = skor[j] / top; for (c in 0 until d) baglam[i][o + c] += a * v[j][o + c] }
                }
            }
            x = Array(n) { i ->
                val a = norm(topla(lin(baglam[i], "$p.attention.output.dense"), x[i]), "$p.attention.output.LayerNorm")
                val ara = lin(a, "$p.intermediate.dense"); for (c in ara.indices) ara[c] = gelu(ara[c])
                norm(topla(lin(ara, "$p.output.dense"), a), "$p.output.LayerNorm")
            }
        }
        // pooled as in training: the [CLS] vector and the mean over all tokens
        val havuz = FloatArray(2 * h)
        for (c in 0 until h) { havuz[c] = x[0][c]; var s = 0f; for (i in 0 until n) s += x[i][c]; havuz[h + c] = s / n }
        return listOf("tur", "aciliyet", "ozel", "il", "ilce").map { lin(havuz, it) }
    }

    companion object {
        /** IEEE 754 half to float. */
        fun yarim(bits: Short): Float {
            val hb = bits.toInt() and 0xFFFF
            val isaret = (hb ushr 15) shl 31
            val us = (hb ushr 10) and 0x1F
            val kesir = hb and 0x3FF
            val f = when {
                us == 0 && kesir == 0 -> isaret
                us == 0 -> { // subnormal
                    var e = 127 - 15 + 1; var k = kesir
                    while (k and 0x400 == 0) { k = k shl 1; e-- }
                    isaret or (e shl 23) or ((k and 0x3FF) shl 13)
                }
                us == 31 -> isaret or 0x7F800000 or (kesir shl 13)
                else -> isaret or ((us - 15 + 127) shl 23) or (kesir shl 13)
            }
            return Float.fromBits(f)
        }

        /** erf, |error| < 1.2e-7 (Numerical Recipes erfc, Chebyshev fit): GELU as PyTorch computes it. */
        fun erf(x: Double): Double {
            val z = abs(x); val t = 1 / (1 + 0.5 * z)
            val r = t * exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 +
                t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))))
            return if (x >= 0) 1 - r else r - 1
        }
    }
}
