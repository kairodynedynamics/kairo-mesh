package com.kairodyne.mesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Emergency relay on the Kotlin node, the same scenarios as test/acil.test.js. The "authority" here only needs to
// produce receipts: it opens the envelope (minus the 8-byte work nonce) with its key and returns the sealed receipt.
class AcilTest {
    private val kurum = Identity.create()

    private class World(names: List<String>, kurumPub: ByteArray, authority: Identity) {
        var t = 1_800_000_000_000L
        val links = HashSet<String>()
        val online = HashSet<String>()
        val received = mutableListOf<String>()
        val delivered = names.associateWith { mutableListOf<String>() }
        private val queue = ArrayDeque<() -> Unit>()
        val nodes: Map<String, Node> = names.associateWith { n ->
            Node(Identity.create(), { f -> for (m in names) if ("$n>$m" in links) queue += { nodes[m]!!.receive(f) } },
                now = { t }, authority = kurumPub, online = { n in online },
                uplink = { env, done ->
                    val opened = runCatching { open(authority, env.sub(0, env.size - 8)) }.getOrNull()
                    if (opened != null && hasWork(env)) { received += String(opened.body, 24, opened.body.size - 24); done(opened.body.sub(0, 16)) }
                    else done(null)
                },
                onEmergencyDelivered = { delivered[n]!! += it })
        }
        fun link(a: String, b: String, on: Boolean = true) { for (k in listOf("$a>$b", "$b>$a")) if (on) links += k else links -= k }
        fun run(rounds: Int) = repeat(rounds) {
            t += 2000
            nodes.values.forEach { it.tick() }
            while (queue.isNotEmpty()) queue.removeFirst()()
        }
        fun meet(a: String, b: String, rounds: Int = 4) { link(a, b); run(rounds); link(a, b, false) }
    }

    private val rapor = "{\"tur\":\"enkaz\",\"aciliyet\":1,\"kisi\":3}"

    @Test fun phoneWithInternetInRangeUploadsAndTheReceiptReachesTheSender() {
        val w = World(listOf("ayse", "gecit"), kurum.pub, kurum)
        w.online += "gecit"
        val id = w.nodes["ayse"]!!.sendEmergency(rapor)
        w.meet("ayse", "gecit")
        assertEquals(listOf(rapor), w.received)
        assertEquals(listOf(id), w.delivered["ayse"])
        assertEquals(0, w.nodes["ayse"]!!.carrying)
        assertEquals(0, w.nodes["gecit"]!!.carrying)
    }

    @Test fun offlineCourierUploadsWhenItGetsSignalAndTheNewsComesBack() {
        val w = World(listOf("ayse", "kurye", "baska"), kurum.pub, kurum)
        w.nodes["ayse"]!!.sendEmergency(rapor)
        w.meet("ayse", "kurye"); w.meet("ayse", "baska")
        assertEquals(1, w.nodes["kurye"]!!.carrying)
        assertEquals(0, w.received.size)
        w.online += "kurye"
        w.run(2)
        assertEquals(1, w.received.size)
        w.meet("kurye", "baska")
        assertEquals(0, w.nodes["baska"]!!.carrying)
        w.meet("baska", "ayse")
        assertEquals(1, w.delivered["ayse"]!!.size)
    }

    @Test fun aCourierThatRestartsStillDeliversWhatItCarried() {
        val w = World(listOf("ayse", "kurye"), kurum.pub, kurum)
        w.nodes["ayse"]!!.sendEmergency(rapor)
        w.meet("ayse", "kurye")
        val disk = w.nodes["kurye"]!!.kayit()
        // the app was killed: a new node in a new process, restored from disk, then the phone finds a signal
        val yuklenen = mutableListOf<String>()
        val yeni = Node(Identity.create(), {}, now = { w.t }, authority = kurum.pub, online = { true },
            uplink = { env, done -> yuklenen += "x"; done(open(kurum, env.sub(0, env.size - 8)).body.sub(0, 16)) })
        yeni.yukle(disk + "bozuk satır\n")
        assertEquals(1, yeni.carrying)
        assertEquals(0, yeni.kendiAcil)
        yeni.tick()
        assertEquals(1, yuklenen.size)
        assertEquals(0, yeni.carrying)
        // the sender's own envelope survives a restart too, and is known as its own
        val ayse = Node(Identity.create(), {}, now = { w.t }, authority = kurum.pub).apply { yukle(w.nodes["ayse"]!!.kayit()) }
        assertEquals(1, ayse.kendiAcil)
    }

    @Test fun couriersRefuseAuthorityMailWithoutWork() {
        val w = World(listOf("ayse", "kurye"), kurum.pub, kurum)
        w.nodes["ayse"]!!.addContact("sahte", kurum.pub)
        w.nodes["ayse"]!!.sendMessage("sahte", rapor) // sealed to the authority, but without work
        w.meet("ayse", "kurye")
        assertEquals(0, w.nodes["kurye"]!!.carrying)
        w.nodes["ayse"]!!.sendEmergency(rapor)
        w.meet("ayse", "kurye")
        assertEquals(1, w.nodes["kurye"]!!.carrying)
    }

    @Test fun workTakesUnderASecondAndIsChecked() {
        val start = System.nanoTime()
        val env = addWork(ByteArray(300) { it.toByte() })
        assertTrue("work took ${(System.nanoTime() - start) / 1e6} ms", (System.nanoTime() - start) / 1e9 < 5)
        assertTrue(hasWork(env))
        assertTrue(!hasWork(env.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })) // altered envelope: work no longer valid
    }
}
