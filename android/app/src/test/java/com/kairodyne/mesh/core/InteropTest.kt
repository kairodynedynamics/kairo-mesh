package com.kairodyne.mesh.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Checks the Kotlin port byte-for-byte against vectors from the JavaScript reference (android/vectors.mjs),
// and writes frames the other way for android/interop-check.mjs.
class InteropTest {
    private val v = JSONObject(javaClass.getResource("/vectors.json")!!.readText())
    private val keys = v.getJSONObject("keys")
    private val ali = Identity.create(keys.getString("aliSecret").unhex())
    private val bora = Identity.create(keys.getString("boraSecret").unhex())

    @Test fun rng() {
        val r = v.getJSONObject("rng")
        val rng = Rng(r.getLong("seed"))
        val out = r.getJSONArray("out")
        for (i in 0 until out.length()) assertEquals(out.getDouble(i), (rng.next().toLong() and 0xffffffffL) / 4294967296.0, 0.0)
    }

    @Test fun fountain() {
        val f = v.getJSONObject("fountain")
        val message = f.getString("message").unhex()
        val enc = Encoder(message)
        assertEquals(f.getInt("k"), enc.k)
        val packets = f.getJSONArray("packets")
        val dec = Decoder(enc.k)
        for (i in 0 until packets.length()) {
            val p = packets.getJSONObject(i)
            assertArrayEquals(p.getString("data").unhex(), enc.packet(p.getLong("seed")))
            if (i > 0) dec.add(p.getLong("seed"), p.getString("data").unhex()) // decode without the first plain fragment
        }
        assertArrayEquals(message, dec.result())
    }

    @Test fun identitiesAndTags() {
        assertArrayEquals(keys.getString("aliPub").unhex(), ali.pub)
        assertArrayEquals(keys.getString("boraPub").unhex(), bora.pub)
        assertArrayEquals(keys.getString("boraTag").unhex(), recipientTag(bora.pub, v.getJSONObject("exchange").getLong("time")))
        assertArrayEquals(keys.getString("boraRk").unhex(), recognitionKey(bora.pub))
        val ids = v.getJSONObject("ids")
        assertArrayEquals(ids.getString("eid").unhex(), ephemeralId(ids.getString("key").unhex(), ids.getLong("time")))
    }

    @Test fun opensAnEnvelopeSealedInJavaScript() {
        val s = v.getJSONObject("seal")
        val opened = open(bora, s.getString("envelope").unhex())
        assertArrayEquals(ali.pub, opened.senderPub)
        assertArrayEquals(s.getString("body").unhex(), opened.body)
    }

    @Test fun receivesAMessageSentByTheJavaScriptNode() {
        val x = v.getJSONObject("exchange")
        val inbox = mutableListOf<Pair<String, String>>()
        val out = mutableListOf<ByteArray>()
        val node = Node(bora, { out += it }, { from, text -> inbox += from to text }, now = { x.getLong("time") })
        node.addContact("ali", ali.pub)
        val frames = x.getJSONArray("frames")
        for (i in 0 until frames.length()) node.receive(frames.getString(i).unhex())
        assertEquals(listOf("ali" to x.getString("text")), inbox)
        assertTrue("delivery receipt sent", out.any { it[0].toInt() == 4 })
    }

    @Test fun writesFramesForTheJavaScriptNode() {
        val t = v.getJSONObject("exchange").getLong("time")
        val out = mutableListOf<ByteArray>()
        val node = Node(ali, { out += it }, now = { t })
        node.addContact("bora", bora.pub)
        node.sendMessage("bora", "Kotlin'den merhaba ✓")
        node.tick()
        val hello = out.last()
        val boraEid = ephemeralId(recognitionKey(bora.pub), t)
        node.receive(cat(u8(2), boraEid, hello.sub(1, 9), hello.sub(10, 18), u8(4), ByteArray(8)))
        val data = out.filter { it[0].toInt() == 3 }
        assertTrue(data.isNotEmpty())
        val json = JSONObject().put("time", t).put("text", "Kotlin'den merhaba ✓")
            .put("envelope", seal(ali, bora.pub, "merhaba".toByteArray()).hex())
            .put("frames", org.json.JSONArray(data.map { it.hex() }))
        File("build/interop-from-kotlin.json").writeText(json.toString())
    }
}
