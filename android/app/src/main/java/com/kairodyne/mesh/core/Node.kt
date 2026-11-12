package com.kairodyne.mesh.core

import java.security.SecureRandom

// Port of src/node.js. Frame layouts, limits and hand-over rules are identical; see the comments there.
// Not thread-safe: callers serialise access (the Android transport does it with one lock).

private const val HELLO = 1
private const val SCORE = 2
private const val DATA = 3
private const val ACK = 4
private const val MINE = 4
private const val SYM = 400
private const val MAX_K = 164
private const val MAX_STORE = 40
private const val MAX_PARTIAL = 16
private const val MAX_TOKENS = 8
private const val HELLO_SLOTS = 24
private const val MAX_SEEN = 1000
private const val STRANGER_STORE = MAX_STORE / 2
private const val STRANGER_PARTIAL = MAX_PARTIAL / 2
private const val SCORE_BUDGET = 48
private const val DATA_BUDGET = 400
private const val TTL_MS = DAY_MS
private const val SKEW_MS = 3_600_000L
private const val PARTIAL_MS = 60_000L
private const val HALF_LIFE_MS = 7_200_000L
private const val REENCOUNTER_MS = 300_000L

private fun blind(value: ByteArray, eid: ByteArray) = sha256(value, eid).sub(0, 8)
private fun proofOf(env: ByteArray, eid: ByteArray) = sha256(env, "ack", eid).sub(0, 16)
private fun knows(tag: ByteArray, eid: ByteArray) = sha256(tag, "knows", eid).sub(0, 8)

class Node(
    private val identity: Identity,
    private val send: (ByteArray) -> Unit,
    private val onMessage: (from: String, text: String) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
    private val copies: Int = 4,
    delivered: List<Pair<String, String>> = emptyList(),
) {
    private interface Trust { val trusted: Boolean; val own: Boolean }
    private class Msg(val id: ByteArray, val tag: ByteArray, val rh: ByteArray, val env: ByteArray, var tokens: Int,
                      val at: Long, override val own: Boolean, override val trusted: Boolean) : Trust
    private class Partial(val dec: Decoder, val at: Long, override val trusted: Boolean) : Trust { override val own = false }
    private class Score(val p: Double, val t: Long)
    private class Hit(val tag: ByteArray?, val q: Int)

    private val contacts = LinkedHashMap<String, ByteArray>()
    private val recognition = LinkedHashMap<String, ByteArray>()
    private val store = LinkedHashMap<String, Msg>()
    private val partial = LinkedHashMap<String, Partial>()
    private val pending = HashMap<String, Int>()
    // persist `delivered` so a restart does not reopen replays of recorded frames
    private val seen = LinkedHashMap<String, ByteArray>().apply { for ((k, r) in delivered) put(k, r.unhex()) }
    private val score = HashMap<String, Score>()
    private val burst = HashSet<String>()
    private val random = SecureRandom()
    private var cursor = 0
    private var scoreBudget = SCORE_BUDGET
    private var dataBudget = DATA_BUDGET

    private val myRk = recognitionKey(identity.pub)
    private fun eid() = ephemeralId(myRk, now())
    private fun isMe(id: ByteArray) = same(id, eid()) || same(id, ephemeralId(myRk, now() - EPOCH_MS))
    private fun tagsOf(pub: ByteArray) = listOf(recipientTag(pub, now()), recipientTag(pub, now() - DAY_MS))
    private fun known(peer: ByteArray) = recognise(peer, recognition, now()) != null

    val pub get() = identity.pub
    val carrying get() = store.size
    val delivered get() = seen.map { it.key to it.value.hex() }
    val copiesHeld get() = store.values.sumOf { it.tokens }

    fun addContact(name: String, pub: ByteArray) {
        require(pub.size == 32) { "contact key must be 32 bytes" }
        contacts[name] = pub
        recognition[name] = recognitionKey(pub)
    }

    private fun decayed(s: Score) = s.p * Math.pow(0.5, (now() - s.t).toDouble() / HALF_LIFE_MS)
    private fun met(name: String) {
        val s = score[name]
        if (s != null && now() - s.t < REENCOUNTER_MS) return
        val p = if (s != null) decayed(s) else 0.0
        score[name] = Score(p + (1 - p) * 0.75, now())
    }
    private fun lookup(matches: (ByteArray) -> Boolean): Hit {
        for ((name, pub) in contacts) for (tag in tagsOf(pub)) if (matches(tag)) {
            val s = score[name]
            return Hit(tag, if (s != null) Math.round(decayed(s) * 3).toInt() else 0)
        }
        return Hit(null, 0)
    }

    private fun <V : Trust> makeRoom(table: LinkedHashMap<String, V>, max: Int, strangerMax: Int, trusted: Boolean): Boolean {
        val strangers = table.entries.filter { !it.value.trusted }
        if (table.size < max && (trusted || strangers.size < strangerMax)) return true
        val victim = strangers.firstOrNull()?.key ?: (if (trusted) table.entries.firstOrNull { !it.value.own }?.key else null)
            ?: return false
        table.remove(victim)
        return true
    }

    private fun ack(to: ByteArray, id: ByteArray, proof: ByteArray) = send(cat(u8(ACK), eid(), to, id, proof))

    private fun onHello(b: ByteArray) {
        val from = b.sub(1, 9)
        val n = b[9].toInt() and 255
        if (n > HELLO_SLOTS || b.size != 10 + n * 16 || isMe(from)) return
        recognise(from, recognition, now())?.let { met(it) }
        if (n == 0) return
        val have = store.values.map { blind(it.id, from).hex() }.toSet()
        val done = seen.entries.associate { blind(it.key.unhex(), from).hex() to it }
        val mine = tagsOf(identity.pub).map { blind(it, from).hex() }
        for (i in 0 until n) {
            val h = b.sub(10 + i * 16, 18 + i * 16)
            val bt = b.sub(18 + i * 16, 26 + i * 16)
            if (h.hex() in have) continue
            val delivered = done[h.hex()]
            if (delivered != null) { ack(from, delivered.key.unhex(), delivered.value); continue }
            if (scoreBudget-- <= 0) return
            val hit = if (bt.hex() in mine) Hit(null, MINE) else lookup { same(blind(it, from), bt) }
            val proof = if (hit.tag != null && hit.q > 0) knows(hit.tag, from) else ByteArray(8)
            send(cat(u8(SCORE), eid(), from, h, u8(hit.q), proof))
        }
    }

    private fun onScore(b: ByteArray) {
        val to = b.sub(9, 17)
        if (b.size != 34 || !isMe(to)) return
        val from = b.sub(1, 9)
        val h = b.sub(17, 25)
        val q = b[25].toInt() and 255
        val m = if (q > MINE) null else store.values.firstOrNull { same(blind(it.id, to), h) } ?: return
        if (m == null) return
        val key = m.id.hex() + from.hex()
        if (key in burst) return
        var give = 0
        if (q != MINE) {
            val mq = lookup { same(it, m.tag) }.q
            val friend = known(from) || (q > 0 && same(knows(m.tag, to), b.sub(26, 34)))
            if (m.tokens > 1) {
                give = if (q + mq > 0) minOf(m.tokens - 1, Math.round(m.tokens.toDouble() * q / (q + mq)).toInt()) else m.tokens shr 1
                if (!friend) give = minOf(give, m.tokens shr 1)
            } else give = if (friend && !m.own && q > mq) 1 else 0
            if (give < 1) return
        }
        val enc = Encoder(m.env, SYM)
        if (dataBudget < enc.k + 2) return
        dataBudget -= enc.k + 2
        burst += key
        val retry = key in pending
        pending[key] = give
        val head = cat(u8(DATA), eid(), from, m.id, m.tag, m.rh, u8(give, enc.k shr 8, enc.k and 255))
        for (i in 0 until enc.k + 2) {
            // First attempt sends the plain fragments; retries send fresh combinations so nothing is a duplicate.
            val seed = if (!retry && i < enc.k) i.toLong() else enc.k + (random.nextLong() and Long.MAX_VALUE) % (0x100000000L - MAX_K - 1)
            send(cat(head, be32(seed), enc.packet(seed)))
        }
    }

    private fun onData(b: ByteArray) {
        if (b.size != 64 + SYM || !isMe(b.sub(9, 17))) return
        val from = b.sub(1, 9)
        val id = b.sub(17, 25)
        val key = id.hex()
        val tag = b.sub(25, 41)
        val rh = b.sub(41, 57)
        val tokens = b[57].toInt() and 255
        val k = b.u16(58)
        if (key in store) return
        seen[key]?.let { return ack(from, id, it) }
        val trusted = known(from)
        var p = partial[key]
        if (p == null) {
            if (k < 1 || k > MAX_K || !makeRoom(partial, MAX_PARTIAL, STRANGER_PARTIAL, trusted)) return
            p = Partial(Decoder(k, SYM), now(), trusted)
            partial[key] = p
        }
        p.dec.add(b.u32(60), b.sub(64, b.size))
        if (!p.dec.done) return
        partial.remove(key)
        val env = runCatching { p.dec.result() }.getOrNull() ?: return
        if (!same(sha256(env, rh).sub(0, 8), id)) return // corrupted or forged packets
        if (tagsOf(identity.pub).none { same(it, tag) }) {
            if (!makeRoom(store, MAX_STORE, STRANGER_STORE, trusted)) return
            store[key] = Msg(id, tag, rh, env, tokens.coerceIn(1, MAX_TOKENS), now(), own = false, trusted = trusted)
            return ack(from, id, proofOf(env, eid()))
        }
        val msg = runCatching { open(identity, env) }.getOrNull() ?: return
        if (msg.body.size < 24) return
        val sentAt = msg.body.u64(16)
        if (now() - sentAt > TTL_MS + SKEW_MS || sentAt - now() > SKEW_MS) return
        val receipt = msg.body.sub(0, 16)
        if (seen.size >= MAX_SEEN) seen.remove(seen.keys.first())
        seen[key] = receipt
        ack(from, id, receipt)
        val sender = contacts.entries.firstOrNull { same(it.value, msg.senderPub) }?.key ?: "unknown:${msg.senderPub.hex().take(8)}"
        onMessage(sender, String(msg.body, 24, msg.body.size - 24, Charsets.UTF_8))
    }

    private fun onAck(b: ByteArray) {
        if (b.size != 41) return
        val from = b.sub(1, 9)
        val proof = b.sub(25, 41)
        val key = b.sub(17, 25).hex()
        val m = store[key] ?: return
        // Anyone may relay a receipt: only the recipient could have produced it.
        if (same(sha256(proof).sub(0, 16), m.rh)) { store.remove(key); return }
        if (!isMe(b.sub(9, 17))) return
        val give = pending[key + from.hex()]
        if (give == null || give == 0 || !same(proofOf(m.env, from), proof)) return
        pending.remove(key + from.hex())
        if (m.tokens > 1) m.tokens -= minOf(give, m.tokens - 1) else if (!m.own) store.remove(key)
    }

    fun sendMessage(name: String, text: String) {
        val pub = contacts[name] ?: throw IllegalArgumentException("unknown contact: $name")
        val receipt = ByteArray(16).also { random.nextBytes(it) }
        val rh = sha256(receipt).sub(0, 16)
        val env = seal(identity, pub, cat(receipt, be64(now()), text.toByteArray(Charsets.UTF_8)))
        require((env.size + 4 + SYM - 1) / SYM <= MAX_K) { "message too large" }
        check(makeRoom(store, MAX_STORE, STRANGER_STORE, true)) { "outbox full" }
        val id = sha256(env, rh).sub(0, 8)
        store[id.hex()] = Msg(id, recipientTag(pub, now()), rh, env, copies, now(), own = true, trusted = true)
    }

    /** Feed every frame heard on the radio; malformed frames from untrusted peers are dropped. */
    fun receive(frame: ByteArray) {
        if (frame.size < 10) return
        try {
            when (frame[0].toInt()) {
                HELLO -> onHello(frame)
                SCORE -> onScore(frame)
                DATA -> onData(frame)
                ACK -> onAck(frame)
            }
        } catch (_: RuntimeException) {}
    }

    /** Call every few seconds: expires old state, refills transmit budgets and announces what we carry. */
    fun tick() {
        store.entries.removeIf { now() - it.value.at > TTL_MS }
        partial.entries.removeIf { now() - it.value.at > PARTIAL_MS }
        if (pending.size > 1000) pending.clear()
        burst.clear()
        scoreBudget = SCORE_BUDGET
        dataBudget = DATA_BUDGET
        val all = store.values.toList()
        val n = minOf(all.size, HELLO_SLOTS)
        val me = eid()
        val parts = mutableListOf(u8(HELLO), me, u8(n))
        for (i in 0 until n) { val m = all[(cursor + i) % all.size]; parts += blind(m.id, me); parts += blind(m.tag, me) }
        cursor += n
        send(cat(*parts.toTypedArray()))
    }
}
