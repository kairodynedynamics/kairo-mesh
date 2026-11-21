package com.kairodyne.mesh.core

import java.security.SecureRandom

// Port of src/node.js. Frame layouts, limits and hand-over rules are identical; see the comments there.
// Not thread-safe: callers serialise access (the Android transport does it with one lock).

private const val HELLO = 1
private const val SCORE = 2
private const val DATA = 3
private const val ACK = 4
private const val NOTICE = 5
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
private const val KURUM = "#kurum"
private const val ACIL_TOKENS = 8
private const val ACIL_STORE = 24
private const val MAX_NOTICES = 32
private const val NOTICE_TEXT = 600
private const val NOTICE_EVERY_MS = 60_000L
private const val NOTICES_PER_TICK = 2

/** An announcement from the authority, after its signature checked out. */
class Notice(val id: String, val issued: Long, val expires: Long, val text: String)

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
    // Emergency relay (see src/node.js): reports sealed to the authority's key, uploaded by whichever phone has internet.
    private val authority: ByteArray? = null,
    private val online: () -> Boolean = { false },
    /** Uploads an envelope; calls back with the authority's receipt, or null to retry later. The callback must run
     *  under the same lock as every other call into this node. */
    private val uplink: ((ByteArray, (ByteArray?) -> Unit) -> Unit)? = null,
    private val onEmergencyDelivered: (String) -> Unit = {},
    private val authoritySign: ByteArray? = null,
    /** Fetches the authority's current announcement frames; same locking rule as `uplink`. */
    private val downlink: (((List<ByteArray>) -> Unit) -> Unit)? = null,
    private val onAnnouncement: (Notice) -> Unit = {},
) {
    private interface Trust { val trusted: Boolean; val own: Boolean }
    private class Msg(val id: ByteArray, val tag: ByteArray, val rh: ByteArray, val env: ByteArray, var tokens: Int,
                      val at: Long, override val own: Boolean, override val trusted: Boolean, val acil: Boolean = false) : Trust {
        var uploading = false
    }
    private class Held(val frame: ByteArray, val expires: Long, var sent: Long = Long.MIN_VALUE / 2)
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
    // mail I carried and saw delivered: passing the receipt on stops the sender and other couriers carrying it.
    // Kept apart from `seen` so a flood of genuine receipts cannot push my own deliveries out and reopen replays.
    private val cleared = LinkedHashMap<String, ByteArray>()
    private val score = HashMap<String, Score>()
    private val burst = HashSet<String>()
    private val random = SecureRandom()
    private var cursor = 0
    private var scoreBudget = SCORE_BUDGET
    private var dataBudget = DATA_BUDGET
    // peer id -> delivered handles blinded for that peer; rebuilding it costs a hash per entry on every HELLO
    private val doneCache = HashMap<String, Map<String, Map.Entry<String, ByteArray>>>()
    private val notices = LinkedHashMap<String, Held>()
    private var lastDownlink = Long.MIN_VALUE / 2

    /** False on low battery: the phone still sends and receives its own mail but takes on nobody else's. */
    var relay = true

    private val myRk = recognitionKey(identity.pub)
    private fun eid() = ephemeralId(myRk, now())
    private fun isMe(id: ByteArray) = same(id, eid()) || same(id, ephemeralId(myRk, now() - EPOCH_MS))
    private fun tagsOf(pub: ByteArray) = listOf(recipientTag(pub, now()), recipientTag(pub, now() - DAY_MS))
    private fun known(peer: ByteArray) = recognise(peer, recognition, now()) != null
    private fun isKurumTag(tag: ByteArray) = authority != null && tagsOf(authority).any { same(it, tag) }
    private fun gateway() = authority != null && uplink != null && online()

    val pub get() = identity.pub
    val carrying get() = store.size
    val delivered get() = seen.map { it.key to it.value.hex() }
    val copiesHeld get() = store.values.sumOf { it.tokens }
    val noticeFrames get() = notices.values.map { it.frame }

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
        // The authority counts as a contact everyone has; "meeting" it means having had internet.
        val all = contacts.entries.map { it.key to it.value } + (if (authority != null) listOf(KURUM to authority) else emptyList())
        for ((name, pub) in all) for (tag in tagsOf(pub)) if (matches(tag)) {
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

    private fun remember(table: LinkedHashMap<String, ByteArray>, key: String, receipt: ByteArray) {
        if (table.size >= MAX_SEEN) table.remove(table.keys.first())
        table[key] = receipt
        doneCache.clear()
    }

    private fun doneFor(peer: ByteArray) = doneCache.getOrPut(peer.hex()) {
        if (doneCache.size >= 16) doneCache.clear() // peers rotate their ids every 15 minutes
        (seen.entries + cleared.entries).associate { blind(it.key.unhex(), peer).hex() to it }
    }

    private fun ack(to: ByteArray, id: ByteArray, proof: ByteArray) = send(cat(u8(ACK), eid(), to, id, proof))

    /** Drops a carried or own message whose receipt checks out, and remembers the receipt to pass on. */
    private fun settle(key: String, receipt: ByteArray): Boolean {
        val m = store[key] ?: return false
        if (!same(sha256(receipt).sub(0, 16), m.rh)) return false
        store.remove(key)
        remember(cleared, key, receipt)
        if (m.own && m.acil) onEmergencyDelivered(key)
        return true
    }

    private fun upload(key: String, m: Msg) {
        val up = uplink ?: return
        if (m.uploading) return
        m.uploading = true
        // an empty receipt: the authority refused the envelope for good (see src/node.js): stop carrying it
        up(m.env) { receipt ->
            if (receipt != null && receipt.isEmpty()) store.remove(key)
            else if (receipt != null && settle(key, receipt)) ack(ByteArray(8), m.id, receipt) else m.uploading = false
        }
    }

    private fun onHello(b: ByteArray) {
        val from = b.sub(1, 9)
        val n = b[9].toInt() and 255
        if (n > HELLO_SLOTS || b.size != 10 + n * 16 || isMe(from)) return
        yakinlar[from.hex()] = now()
        recognise(from, recognition, now())?.let { met(it) }
        if (n == 0) return
        val have = store.values.map { blind(it.id, from).hex() }.toSet()
        val done = doneFor(from)
        val mine = tagsOf(identity.pub).map { blind(it, from).hex() }.toMutableList()
        // A phone with internet answers for the authority: it will upload the envelope at once.
        if (gateway()) mine += tagsOf(authority!!).map { blind(it, from).hex() }
        for (i in 0 until n) {
            val h = b.sub(10 + i * 16, 18 + i * 16)
            val bt = b.sub(18 + i * 16, 26 + i * 16)
            if (h.hex() in have) continue
            val delivered = done[h.hex()]
            if (delivered != null) { ack(from, delivered.key.unhex(), delivered.value); continue }
            val forMe = bt.hex() in mine
            if (!forMe && !relay) continue
            if (scoreBudget-- <= 0) return
            val hit = if (forMe) Hit(null, MINE) else lookup { same(blind(it, from), bt) }
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
            // Emergency mail goes to anyone willing to carry it.
            val friend = m.acil || known(from) || (q > 0 && same(knows(m.tag, to), b.sub(26, 34)))
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
        (seen[key] ?: cleared[key])?.let { return ack(from, id, it) }
        val forMe = { tagsOf(identity.pub).any { same(it, tag) } }
        val acil = isKurumTag(tag)
        if (!relay && !forMe() && !(acil && gateway())) return
        val trusted = known(from) || acil
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
        if (acil && !hasWork(env)) return
        if (!forMe()) {
            if (acil) { // others' emergency mail has its own quota; when full, the oldest one makes room
                val others = store.entries.filter { it.value.acil && !it.value.own }
                if (others.size >= ACIL_STORE) store.remove(others.minByOrNull { it.value.at }!!.key)
            }
            if (!makeRoom(store, MAX_STORE, STRANGER_STORE, trusted)) return
            val m = Msg(id, tag, rh, env, tokens.coerceIn(1, MAX_TOKENS), now(), own = false, trusted = trusted, acil = acil)
            store[key] = m
            ack(from, id, proofOf(env, eid()))
            if (acil && gateway()) upload(key, m)
            return
        }
        val msg = runCatching { open(identity, env) }.getOrNull() ?: return
        if (msg.body.size < 24) return
        val sentAt = msg.body.u64(16)
        // compared against bounds, not subtracted: a sender-chosen timestamp must not overflow the arithmetic
        if (sentAt < now() - TTL_MS - SKEW_MS || sentAt > now() + SKEW_MS) return
        val receipt = msg.body.sub(0, 16)
        remember(seen, key, receipt)
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
        if (settle(key, proof)) return
        if (!isMe(b.sub(9, 17))) return
        val give = pending[key + from.hex()]
        if (give == null || give == 0 || !same(proofOf(m.env, from), proof)) return
        pending.remove(key + from.hex())
        if (m.tokens > 1) m.tokens -= minOf(give, m.tokens - 1) else if (!m.own) store.remove(key)
    }

    fun sendMessage(name: String, text: String): String {
        val pub = contacts[name] ?: throw IllegalArgumentException("unknown contact: $name")
        return post(pub, text.toByteArray(Charsets.UTF_8), copies, false)
    }

    /** `reportJson`: the structured emergency record (triage fields, GPS location). Returns the message id;
     *  `onEmergencyDelivered(id)` fires when the authority has it. Takes under a second (proof of work). */
    fun sendEmergency(reportJson: String): String {
        val pub = authority ?: throw IllegalStateException("no authority key configured")
        return post(pub, reportJson.toByteArray(Charsets.UTF_8), ACIL_TOKENS, true)
    }

    private fun post(pub: ByteArray, body: ByteArray, tokens: Int, acil: Boolean): String {
        val receipt = ByteArray(16).also { random.nextBytes(it) }
        val rh = sha256(receipt).sub(0, 16)
        val sealed = seal(identity, pub, cat(receipt, be64(now()), body))
        val env = if (acil) addWork(sealed) else sealed
        require((env.size + 4 + SYM - 1) / SYM <= MAX_K) { "message too large" }
        check(makeRoom(store, MAX_STORE, STRANGER_STORE, true)) { "outbox full" }
        val id = sha256(env, rh).sub(0, 8)
        store[id.hex()] = Msg(id, recipientTag(pub, now()), rh, env, tokens, now(), own = true, trusted = true, acil = acil)
        return id.hex()
    }

    // NOTICE: [5][id 8][issued 8][expires 8][len 2][text][sig 64]; signed over everything before the signature.
    private fun onNotice(b: ByteArray) {
        val signer = authoritySign ?: return
        if (b.size < 27 + 64) return
        val len = b.u16(25)
        if (len > NOTICE_TEXT || b.size != 27 + len + 64) return
        val key = b.sub(1, 9).hex()
        if (key in notices) return
        val issued = b.u64(9)
        val expires = b.u64(17)
        if (expires <= now() || issued > now() + SKEW_MS) return
        if (!verifySignature(signer, b.sub(0, 27 + len), b.sub(27 + len, b.size))) return
        if (notices.size >= MAX_NOTICES) {
            val soonest = notices.entries.minByOrNull { it.value.expires }!!
            if (soonest.value.expires > expires) return
            notices.remove(soonest.key)
        }
        notices[key] = Held(b.copyOf(), expires)
        onAnnouncement(Notice(key, issued, expires, String(b, 27, len, Charsets.UTF_8)))
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
                NOTICE -> onNotice(frame)
            }
        } catch (_: RuntimeException) {}
    }

    /** Call every few seconds: expires old state, refills transmit budgets and announces what we carry. */
    // ephemeral id -> last HELLO heard: counts phones, not Bluetooth connections (an Android phone changes its
    // Bluetooth address every ~15 min, so one phone could hold two connections and be counted twice)
    private val yakinlar = HashMap<String, Long>()

    /** Phones running KairoMesh heard from in the last [pencereMs] (each greets every few seconds). */
    fun yakin(pencereMs: Long = 30_000): Int {
        val t = now() - pencereMs
        yakinlar.values.removeAll { it < t }
        return yakinlar.size
    }

    /**
     * Everything carried (own and others' envelopes) and the receipts seen for carried mail, as text for the app to
     * keep on disk: the OS killing the app or a reboot must not drop calls strangers handed over. [kayitIzi] changes
     * whenever this does, so the app writes only after a change.
     */
    fun kayit(): String = buildString {
        for (m in store.values) append("m ${m.id.hex()} ${m.tag.hex()} ${m.rh.hex()} ${m.env.hex()} ${m.tokens} ${m.at} ${m.own} ${m.trusted} ${m.acil}\n")
        for ((k, r) in cleared) append("c $k ${r.hex()}\n")
    }
    val kayitIzi get() = store.entries.joinToString(",") { it.key + ":" + it.value.tokens } + "|" + cleared.size
    /** Own emergency envelopes still on their way to this node's authority (after [yukle], the app need not send its
     *  pending call again). */
    val kendiAcil get() = store.values.count { it.own && it.acil && isKurumTag(it.tag) }

    /**
     * Restores what [kayit] wrote; expired envelopes go on the next [tick]. Unreadable lines are skipped. The
     * authority may have changed since (the demo code replaced by a real authority's): the caller's own envelopes
     * for the old one are dropped, so the app sends its pending call to the new one; strangers' are still carried
     * (a phone that has the old code may yet deliver them) but no longer uploaded here, where they would be refused.
     */
    fun yukle(kayit: String) {
        for (s in kayit.lineSequence()) runCatching {
            val p = s.split(" ")
            if (p[0] == "m" && p.size == 10 && p[1] !in store && store.size < MAX_STORE) {
                val tag = p[2].unhex()
                val buKurum = isKurumTag(tag)
                val own = p[7].toBoolean()
                val acil = p[9].toBoolean()
                if (own && acil && !buKurum) return@runCatching
                store[p[1]] = Msg(p[1].unhex(), tag, p[3].unhex(), p[4].unhex(), p[5].toInt().coerceIn(1, MAX_TOKENS),
                    p[6].toLong(), own = own, trusted = p[8].toBoolean(), acil = acil && buKurum)
            } else if (p[0] == "c" && p.size == 3) remember(cleared, p[1], p[2].unhex())
        }
    }

    fun tick() {
        store.entries.removeIf { now() - it.value.at > TTL_MS }
        partial.entries.removeIf { now() - it.value.at > PARTIAL_MS }
        if (pending.size > 1000) pending.clear()
        burst.clear()
        scoreBudget = SCORE_BUDGET
        dataBudget = DATA_BUDGET
        if (gateway()) {
            met(KURUM)
            for ((k, m) in store.entries.toList()) if (m.acil) upload(k, m) // includes mail picked up while offline
        }
        val down = downlink
        if (down != null && online() && now() - lastDownlink > 5 * NOTICE_EVERY_MS) {
            lastDownlink = now()
            down { frames -> for (f in frames) onNotice(f) }
        }
        notices.entries.removeIf { it.value.expires <= now() }
        var sent = 0
        for (n in notices.values) if (sent < NOTICES_PER_TICK && now() - n.sent >= NOTICE_EVERY_MS) { n.sent = now(); sent++; send(n.frame) }
        // emergency mail is announced first, so it wins the HELLO slots and the transmit budget
        val all = store.values.sortedByDescending { if (it.acil) 1 else 0 }
        val n = minOf(all.size, HELLO_SLOTS)
        val me = eid()
        val parts = mutableListOf(u8(HELLO), me, u8(n))
        for (i in 0 until n) { val m = all[(cursor + i) % all.size]; parts += blind(m.id, me); parts += blind(m.tag, me) }
        cursor += n
        send(cat(*parts.toTypedArray()))
    }
}
