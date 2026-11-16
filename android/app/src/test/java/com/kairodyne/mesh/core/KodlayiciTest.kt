package com.kairodyne.mesh.core

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

// The Kotlin tokenizer and encoder must reproduce PyTorch on the golden messages (triyaj/telefon/kodlayici-altin.json,
// written by `python triyaj/kodlayici.py telefon` with the same float16 weights).
class KodlayiciTest {
    private val dir = File("../../triyaj/telefon")
    private val kod = Kodlayici(File(dir, "kodlayici.json").readText(), File(dir, "kodlayici.bin").readBytes())
    private val altin = JSONArray(File(dir, "kodlayici-altin.json").readText())

    @Test fun tokenizesLikeTheTrainingTokenizer() {
        for (k in 0 until altin.length()) {
            val a = altin.getJSONObject(k)
            val want = a.getJSONArray("ids").let { j -> IntArray(j.length()) { j.getInt(it) } }
            assertArrayEquals(a.getString("metin"), want, kod.belirtecler(a.getString("metin")))
        }
    }

    @Test fun matchesPyTorchOnGoldenMessages() {
        for (k in 0 until altin.length()) {
            val a = altin.getJSONObject(k)
            val got = kod.ham(a.getString("metin"))
            listOf("tur", "aciliyet", "ozel", "il", "ilce").forEachIndexed { h, ad ->
                val e = a.getJSONArray(ad)
                assertEquals(ad, e.length(), got[h].size)
                for (i in got[h].indices) assertTrue("$ad $k[$i]: ${e.getDouble(i)} vs ${got[h][i]}", abs(e.getDouble(i) - got[h][i]) < 5e-3)
            }
        }
    }

    @Test fun halfFloatConversion() {
        assertEquals(1f, Kodlayici.yarim(0x3C00.toShort()))
        assertEquals(-2f, Kodlayici.yarim(0xC000.toShort()))
        assertEquals(65504f, Kodlayici.yarim(0x7BFF.toShort()))
        assertEquals(5.9604645e-8f, Kodlayici.yarim(0x0001.toShort()))
        assertTrue(abs(Kodlayici.erf(0.5) - 0.5204998778) < 2e-7)
    }

    @Test fun triageWithTheEncoderReadsWordsTheCharacterModelNeverSaw() {
        val t = Triyaj(File(dir, "triyaj.json").readText(), File(dir, "triyaj.bin").readBytes(), kod)
        val r = t.coz("harabenin altındayız ses veriyoruz")
        assertEquals("enkaz", r.tur)
        assertTrue("life-threatening: ${r.aciliyet}", r.aciliyet <= 2)
        // the address still comes from the character model
        val a = t.coz("hatay antakya ürgenpaşa mh enkaz altındayız 3 kişi bebek var")
        assertEquals("Hatay", a.il); assertEquals(3, a.kisi); assertTrue(a.ozel.contains("bebek"))
    }
}
