package com.kairodyne.mesh.core

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

// The built-in verifier (used where the phone's provider lacks Ed25519) against RFC 8032 test vectors and the
// codes the JS root signs.
class Ed25519Test {
    private fun h(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test fun rfc8032Vectors() {
        // TEST 1 (empty message) and TEST 2 (one byte), RFC 8032 section 7.1
        assertTrue(Ed25519.dogrula(h("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"), ByteArray(0),
            h("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")))
        assertTrue(Ed25519.dogrula(h("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"), h("72"),
            h("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00")))
        assertFalse("altered message", Ed25519.dogrula(h("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"), h("73"),
            h("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00")))
    }

    @Test fun agreesWithJsRootOnAuthorityCodes() {
        val o = JSONObject(File("../../test/kurum-kodu-ornek.json").readText())
        val kok = Base64.getDecoder().decode(o.getString("kok"))
        val p = o.getString("gecerli").split(";")
        val msg = ("kairo-kurum-1\n" + p.subList(0, 4).joinToString(";")).toByteArray()
        assertTrue(Ed25519.dogrula(kok, msg, Base64.getDecoder().decode(p[4])))
        assertFalse(Ed25519.dogrula(kok, msg + 1, Base64.getDecoder().decode(p[4])))
    }
}
