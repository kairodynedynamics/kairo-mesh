package com.kairodyne.mesh.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Base64

// Codes approved by the test root in JS (test/kurum-kodu-ornek.json): Kotlin must accept and reject the same ones.
class KurumKoduTest {
    private val o = JSONObject(File("../../test/kurum-kodu-ornek.json").readText())
    private val kok = Base64.getDecoder().decode(o.getString("kok"))

    @Test fun acceptsCodeApprovedByRoot() {
        val k = KurumKodu.ac(o.getString("gecerli"), listOf(kok))
        assertNotNull(k); k!!
        assertEquals("https://kurum.example", k.url)
        assertEquals("Test Kurumu (yalnız test)", k.ad)
    }

    @Test fun rejectsForgedOrUnapprovedCodes() {
        val g = o.getJSONObject("gecersiz")
        for (ad in g.keys()) assertNull(ad, KurumKodu.ac(g.getString(ad), listOf(kok)))
        assertNull("unknown root", KurumKodu.ac(o.getString("gecerli"), listOf(ByteArray(32) { 9 })))
    }
}
