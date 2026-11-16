package com.kairodyne.mesh.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

// The Kotlin forward pass must reproduce PyTorch's outputs on the golden messages (triyaj/telefon/altin.json,
// written by `python triyaj/model.py agirlik`), and decode them to the same record.
class TriyajTest {
    private val dir = File("../../triyaj/telefon")
    private val model = Triyaj(File(dir, "triyaj.json").readText(), File(dir, "triyaj.bin").readBytes())
    private val altin = JSONArray(File(dir, "altin.json").readText())

    private fun close(expected: JSONArray, got: FloatArray, what: String) {
        assertEquals(what, expected.length(), got.size)
        for (i in got.indices) assertTrue("$what[$i]: ${expected.getDouble(i)} vs ${got[i]}", abs(expected.getDouble(i) - got[i]) < 2e-3)
    }

    @Test fun matchesPyTorchOnGoldenMessages() {
        for (k in 0 until altin.length()) {
            val a = altin.getJSONObject(k)
            val ham = model.ham(a.getString("metin"))
            @Suppress("UNCHECKED_CAST") val etiket = ham[0] as Array<FloatArray>
            val e = a.getJSONArray("etiket")
            assertEquals(e.length(), etiket.size)
            for (i in etiket.indices) close(e.getJSONArray(i), etiket[i], "etiket $k/$i")
            for ((j, ad) in listOf("tur", "aciliyet", "ozel", "il", "ilce").withIndex()) close(a.getJSONArray(ad), ham[j + 1] as FloatArray, "$ad $k")
        }
    }

    @Test fun decodesToTheSameRecord() {
        for (k in 0 until altin.length()) {
            val a = altin.getJSONObject(k)
            val want = a.getJSONObject("kayit")
            val got = model.coz(a.getString("metin"))
            fun s(key: String) = if (want.isNull(key)) null else want.get(key).toString()
            assertEquals(s("tur"), got.tur)
            assertEquals(s("aciliyet"), got.aciliyet.toString())
            assertEquals(s("kisi"), got.kisi?.toString())
            for ((key, v) in listOf("il" to got.il, "ilce" to got.ilce, "mahalle" to got.mahalle, "sokak" to got.sokak,
                    "bina" to got.bina, "ad" to got.ad, "tel" to got.tel)) assertEquals("$key in ${a.getString("metin")}", s(key), v)
            val oz = want.getJSONArray("ozel")
            assertEquals((0 until oz.length()).map { oz.getString(it) }, got.ozel)
        }
    }

    @Test fun emojiDoesNotShiftSpans() {
        val r = model.coz("🙏🙏 hatay antakya ürgenpaşa mh enkaz altındayız 3 kişi")
        assertEquals("enkaz", r.tur)
        assertTrue("mahalle: ${r.mahalle}", r.mahalle == null || r.mahalle!!.contains("ürgenpaşa"))
    }
}
