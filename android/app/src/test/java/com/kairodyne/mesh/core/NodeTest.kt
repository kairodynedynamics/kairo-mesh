package com.kairodyne.mesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeTest {
    // Three phones on a bench; `links` says who can hear whom. Frames are delivered after the current call returns.
    private class World(names: List<String>) {
        var t = 1_800_000_000_000L
        val links = HashSet<String>()
        val inbox = names.associateWith { mutableListOf<Pair<String, String>>() }
        private val queue = ArrayDeque<() -> Unit>()
        private val ids = names.associateWith { Identity.create() }
        val nodes: Map<String, Node> = names.associateWith { n ->
            Node(ids[n]!!, { f -> for (m in names) if ("$n>$m" in links) queue += { nodes[m]!!.receive(f) } },
                { from, text -> inbox[n]!! += from to text }, now = { t })
        }
        init { for (a in names) for (b in names) if (a != b) nodes[a]!!.addContact(b, ids[b]!!.pub) }
        fun link(a: String, b: String, on: Boolean = true) { for (k in listOf("$a>$b", "$b>$a")) if (on) links += k else links -= k }
        fun run(rounds: Int) = repeat(rounds) {
            t += 2000
            nodes.values.forEach { it.tick() }
            while (queue.isNotEmpty()) queue.removeFirst()()
        }
    }

    private fun World.meet(a: String, b: String) { link(a, b); run(3); link(a, b, false) }
    private fun loaded(): World = World(listOf("ali", "k1", "k2", "bora")).apply {
        nodes["ali"]!!.sendMessage("bora", "okul bahçesi")
        meet("ali", "k1"); meet("ali", "k2")
        assertEquals(1, nodes["k1"]!!.carrying); assertEquals(1, nodes["k2"]!!.carrying)
    }

    @Test fun twoCouriersReachingTheRecipientTogetherDeliverOnce() {
        val w = loaded()
        w.link("k1", "bora"); w.link("k2", "bora"); w.run(3)
        assertEquals(listOf("ali" to "okul bahçesi"), w.inbox["bora"])
        assertEquals(0, w.nodes["k1"]!!.carrying); assertEquals(0, w.nodes["k2"]!!.carrying)
    }

    @Test fun newsOfDeliveryReachesTheSenderAndOtherCouriers() {
        val w = loaded()
        w.meet("k1", "bora"); w.meet("k1", "k2")
        assertEquals(0, w.nodes["k1"]!!.carrying); assertEquals(0, w.nodes["k2"]!!.carrying)
        w.meet("ali", "k1")
        assertEquals(0, w.nodes["ali"]!!.carrying)
    }

    @Test fun lowBatteryPhoneCarriesOnlyItsOwnMail() {
        val w = World(listOf("ali", "k1", "bora"))
        w.nodes["k1"]!!.relay = false
        w.nodes["ali"]!!.sendMessage("bora", "başkasının")
        w.meet("ali", "k1")
        assertEquals(0, w.nodes["k1"]!!.carrying)
        w.nodes["ali"]!!.sendMessage("k1", "sana")
        w.nodes["k1"]!!.sendMessage("bora", "kendi mesajım")
        w.meet("ali", "k1"); w.meet("k1", "bora")
        assertEquals(listOf("ali" to "sana"), w.inbox["k1"])
        assertEquals(listOf("k1" to "kendi mesajım"), w.inbox["bora"])
    }

    @Test fun courierCarriesBetweenPeopleWhoNeverMeet() {
        val w = World(listOf("ali", "kurye", "bora"))
        w.nodes["ali"]!!.sendMessage("bora", "okul bahçesinde buluşalım")
        w.link("ali", "kurye"); w.run(3)
        assertEquals(1, w.nodes["kurye"]!!.carrying)
        w.link("ali", "kurye", false); w.link("kurye", "bora"); w.run(3)
        assertEquals(listOf("ali" to "okul bahçesinde buluşalım"), w.inbox["bora"])
        assertEquals(0, w.nodes["kurye"]!!.carrying)
        w.run(3)
        assertEquals(1, w.inbox["bora"]!!.size)
    }

    @Test fun garbageDoesNotCrashOrDropMail() {
        val w = World(listOf("ali", "kurye", "bora"))
        w.nodes["ali"]!!.sendMessage("bora", "gizli")
        w.link("ali", "kurye"); w.run(3); w.link("ali", "kurye", false)
        val r = java.util.Random(9)
        repeat(500) { w.nodes["kurye"]!!.receive(ByteArray(1 + r.nextInt(500)).also(r::nextBytes)) }
        for (type in 1..4) for (len in listOf(26, 34, 41, 464)) w.nodes["kurye"]!!.receive(ByteArray(len).also { r.nextBytes(it); it[0] = type.toByte() })
        assertEquals(1, w.nodes["kurye"]!!.carrying)
        w.link("kurye", "bora"); w.run(3)
        assertEquals(listOf("ali" to "gizli"), w.inbox["bora"])
    }

    @Test fun replayAfterRestartIsNotDeliveredAgain() {
        val ali = Identity.create(); val bora = Identity.create()
        val heard = mutableListOf<ByteArray>() // everything ali transmits, as an eavesdropper would record it
        val inbox = mutableListOf<String>()
        val t = 1_800_000_000_000L
        lateinit var first: Node
        val sender = Node(ali, { heard += it; first.receive(it) }, now = { t }).apply { addContact("bora", bora.pub) }
        fun recipient(delivered: List<Pair<String, String>>) =
            Node(bora, { sender.receive(it) }, { _, text -> inbox += text }, now = { t }, delivered = delivered).apply { addContact("ali", ali.pub) }
        first = recipient(emptyList())
        sender.sendMessage("bora", "bir kez")
        sender.tick(); first.tick(); sender.tick()
        assertEquals(listOf("bir kez"), inbox)

        recipient(first.delivered).let { restarted -> heard.forEach(restarted::receive) }
        assertEquals(listOf("bir kez"), inbox)
        // without the persisted set the same replay works: this is the attack the persistence closes
        recipient(emptyList()).let { forgetful -> heard.forEach(forgetful::receive) }
        assertEquals(listOf("bir kez", "bir kez"), inbox)
    }

    // A contact seals a timestamp that wrapped arithmetic would read as "now": it must be refused like any stale one.
    @Test fun sealedTimestampCannotOverflowTheFreshnessCheck() {
        val ali = Identity.create(); val bora = Identity.create()
        val t = 1_800_000_000_000L
        val inbox = mutableListOf<String>()
        val node = Node(bora, {}, { _, text -> inbox += text }, now = { t }).apply { addContact("ali", ali.pub) }
        fun deliver(sentAt: Long, text: String) {
            val receipt = ByteArray(16) { it.toByte() }
            val rh = sha256(receipt).sub(0, 16)
            val env = seal(ali, bora.pub, cat(receipt, be64(sentAt), text.toByteArray()))
            val enc = Encoder(env, 400)
            val head = cat(u8(3), ByteArray(8), ephemeralId(recognitionKey(bora.pub), t), sha256(env, rh).sub(0, 8),
                recipientTag(bora.pub, t), rh, u8(1, enc.k shr 8, enc.k and 255))
            for (i in 0 until enc.k) node.receive(cat(head, be32(i.toLong()), enc.packet(i.toLong())))
        }
        deliver(t + Long.MIN_VALUE, "wrapped")
        deliver(Long.MAX_VALUE, "far future")
        deliver(t, "fresh")
        assertEquals(listOf("fresh"), inbox)
    }
}
