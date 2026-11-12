import Foundation

// Port of android/.../core/Node.kt. Frame layouts, limits and hand-over rules are identical; see src/node.js.
// Not thread-safe: callers serialise access. Every frame field is length-checked before it is read,
// because an out-of-range index traps in Swift instead of throwing.

private let HELLO: UInt8 = 1, SCORE: UInt8 = 2, DATA: UInt8 = 3, ACK: UInt8 = 4
private let MINE = 4
private let SYM = 400
private let MAX_K = 164
private let MAX_STORE = 40, MAX_PARTIAL = 16, MAX_TOKENS = 8, HELLO_SLOTS = 24, MAX_SEEN = 1000
private let STRANGER_STORE = MAX_STORE / 2, STRANGER_PARTIAL = MAX_PARTIAL / 2
private let SCORE_BUDGET = 48, DATA_BUDGET = 400
private let TTL_MS = dayMs, SKEW_MS: Int64 = 3_600_000, PARTIAL_MS: Int64 = 60_000
private let HALF_LIFE_MS = 7_200_000.0, REENCOUNTER_MS: Int64 = 300_000

private func blind(_ value: Bytes, _ eid: Bytes) -> Bytes { sha256(value, eid).sub(0, 8) }
private func proofOf(_ env: Bytes, _ eid: Bytes) -> Bytes { sha256(env, "ack", eid).sub(0, 16) }
private func knows(_ tag: Bytes, _ eid: Bytes) -> Bytes { sha256(tag, "knows", eid).sub(0, 8) }

/// Insertion-ordered map: eviction and announcement order must match the other ports.
struct Ordered<V> {
    private(set) var keys: [String] = []
    private var dict: [String: V] = [:]
    var count: Int { keys.count }
    var values: [V] { keys.map { dict[$0]! } }
    var pairs: [(String, V)] { keys.map { ($0, dict[$0]!) } }
    subscript(key: String) -> V? {
        get { dict[key] }
        set {
            if let v = newValue {
                if dict[key] == nil { keys.append(key) }
                dict[key] = v
            } else if dict.removeValue(forKey: key) != nil {
                keys.removeAll { $0 == key }
            }
        }
    }
    mutating func removeAll(where drop: (V) -> Bool) {
        var kept: [String] = []
        for key in keys { if drop(dict[key]!) { dict[key] = nil } else { kept.append(key) } }
        keys = kept
    }
}

private protocol Trust { var trusted: Bool { get }; var own: Bool { get } }

private final class Msg: Trust {
    let id: Bytes, tag: Bytes, rh: Bytes, env: Bytes
    var tokens: Int
    let at: Int64, own: Bool, trusted: Bool
    init(_ id: Bytes, _ tag: Bytes, _ rh: Bytes, _ env: Bytes, tokens: Int, at: Int64, own: Bool, trusted: Bool) {
        self.id = id; self.tag = tag; self.rh = rh; self.env = env; self.tokens = tokens; self.at = at; self.own = own; self.trusted = trusted
    }
}

private final class Partial: Trust {
    let dec: Decoder, at: Int64, trusted: Bool
    let own = false
    init(_ dec: Decoder, _ at: Int64, _ trusted: Bool) { self.dec = dec; self.at = at; self.trusted = trusted }
}

public final class Node {
    private let identity: Identity
    private let send: (Bytes) -> Void
    private let onMessage: (String, String) -> Void
    private let now: () -> Int64
    private let copies: Int

    private var contacts = Ordered<Bytes>()
    private var recognition = Ordered<Bytes>()
    private var store = Ordered<Msg>()
    private var partial = Ordered<Partial>()
    private var pending: [String: Int] = [:]
    private var seen = Ordered<Bytes>()
    private var score: [String: (p: Double, t: Int64)] = [:]
    private var burst = Set<String>()
    private var cursor = 0
    private var scoreBudget = SCORE_BUDGET
    private var dataBudget = DATA_BUDGET
    private let myRk: Bytes

    /// `delivered` is what a previous run returned from `delivered`: persist it so a restart does not reopen replays.
    public init(identity: Identity, send: @escaping (Bytes) -> Void, onMessage: @escaping (String, String) -> Void = { _, _ in },
                now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }, copies: Int = 4,
                delivered: [(String, String)] = []) {
        self.identity = identity; self.send = send; self.onMessage = onMessage; self.now = now; self.copies = copies
        myRk = recognitionKey(identity.pub)
        for (key, receipt) in delivered { if let r = receipt.unhex { seen[key] = r } }
    }

    private func eid() -> Bytes { ephemeralId(myRk, now()) }
    private func isMe(_ id: Bytes) -> Bool { same(id, eid()) || same(id, ephemeralId(myRk, now() - epochMs)) }
    private func tagsOf(_ pub: Bytes) -> [Bytes] { [recipientTag(pub, now()), recipientTag(pub, now() - dayMs)] }
    private func known(_ peer: Bytes) -> Bool { recognise(peer, recognition.pairs, now()) != nil }

    public var pub: Bytes { identity.pub }
    public var carrying: Int { store.count }
    public var copiesHeld: Int { store.values.reduce(0) { $0 + $1.tokens } }
    public var delivered: [(String, String)] { seen.pairs.map { ($0.0, $0.1.hex) } }

    public func addContact(_ name: String, _ pub: Bytes) throws {
        guard pub.count == 32 else { throw KairoError.malformed }
        contacts[name] = pub
        recognition[name] = recognitionKey(pub)
    }

    private func decayed(_ s: (p: Double, t: Int64)) -> Double { s.p * pow(0.5, Double(now() - s.t) / HALF_LIFE_MS) }
    private func met(_ name: String) {
        let s = score[name]
        if let s = s, now() - s.t < REENCOUNTER_MS { return }
        let p = s.map(decayed) ?? 0
        score[name] = (p + (1 - p) * 0.75, now())
    }
    private func lookup(_ matches: (Bytes) -> Bool) -> (tag: Bytes?, q: Int) {
        for (name, pub) in contacts.pairs {
            for tag in tagsOf(pub) where matches(tag) {
                return (tag, score[name].map { Int((decayed($0) * 3).rounded()) } ?? 0)
            }
        }
        return (nil, 0)
    }

    private func makeRoom<V: Trust>(_ table: inout Ordered<V>, _ max: Int, _ strangerMax: Int, _ trusted: Bool) -> Bool {
        let strangers = table.pairs.filter { !$0.1.trusted }.map { $0.0 }
        if table.count < max && (trusted || strangers.count < strangerMax) { return true }
        guard let victim = strangers.first ?? (trusted ? table.pairs.first { !$0.1.own }?.0 : nil) else { return false }
        table[victim] = nil
        return true
    }

    private func ack(_ to: Bytes, _ id: Bytes, _ proof: Bytes) { send(cat(u8(Int(ACK)), eid(), to, id, proof)) }

    private func onHello(_ b: Bytes) {
        let from = b.sub(1, 9)
        let n = Int(b[9])
        guard n <= HELLO_SLOTS, b.count == 10 + n * 16, !isMe(from) else { return }
        if let name = recognise(from, recognition.pairs, now()) { met(name) }
        if n == 0 { return }
        let have = Set(store.values.map { blind($0.id, from).hex })
        var done: [String: (Bytes, Bytes)] = [:]
        for (key, receipt) in seen.pairs { if let id = key.unhex { done[blind(id, from).hex] = (id, receipt) } }
        let mine = Set(tagsOf(identity.pub).map { blind($0, from).hex })
        for i in 0..<n {
            let h = b.sub(10 + i * 16, 18 + i * 16)
            let bt = b.sub(18 + i * 16, 26 + i * 16)
            if have.contains(h.hex) { continue }
            if let d = done[h.hex] { ack(from, d.0, d.1); continue }
            scoreBudget -= 1
            if scoreBudget < 0 { return }
            let hit = mine.contains(bt.hex) ? (tag: nil, q: MINE) : lookup { same(blind($0, from), bt) }
            let proof = hit.tag.flatMap { hit.q > 0 ? knows($0, from) : nil } ?? Bytes(repeating: 0, count: 8)
            send(cat(u8(Int(SCORE)), eid(), from, h, u8(hit.q), proof))
        }
    }

    private func onScore(_ b: Bytes) {
        guard b.count == 34, isMe(b.sub(9, 17)) else { return }
        let to = b.sub(9, 17)
        let from = b.sub(1, 9)
        let h = b.sub(17, 25)
        let q = Int(b[25])
        guard q <= MINE, let m = store.values.first(where: { same(blind($0.id, to), h) }) else { return }
        let key = m.id.hex + from.hex
        if burst.contains(key) { return }
        var give = 0
        if q != MINE {
            let mq = lookup { same($0, m.tag) }.q
            let friend = known(from) || (q > 0 && same(knows(m.tag, to), b.sub(26, 34)))
            if m.tokens > 1 {
                give = q + mq > 0 ? min(m.tokens - 1, Int((Double(m.tokens) * Double(q) / Double(q + mq)).rounded())) : m.tokens >> 1
                if !friend { give = min(give, m.tokens >> 1) }
            } else {
                give = friend && !m.own && q > mq ? 1 : 0
            }
            if give < 1 { return }
        }
        guard let enc = try? Encoder(m.env, symbolSize: SYM), dataBudget >= enc.k + 2 else { return }
        dataBudget -= enc.k + 2
        burst.insert(key)
        let retry = pending[key] != nil
        pending[key] = give
        let head = cat(u8(Int(DATA)), eid(), from, m.id, m.tag, m.rh, u8(give, enc.k >> 8, enc.k & 255))
        for i in 0..<enc.k + 2 {
            // First attempt sends the plain fragments; retries send fresh combinations so nothing is a duplicate.
            let seed = !retry && i < enc.k ? UInt64(i) : UInt64(enc.k) + UInt64.random(in: 0..<(0x1_0000_0000 - UInt64(MAX_K) - 1))
            send(cat(head, be32(seed), enc.packet(seed)))
        }
    }

    private func onData(_ b: Bytes) {
        guard b.count == 64 + SYM, isMe(b.sub(9, 17)) else { return }
        let from = b.sub(1, 9)
        let id = b.sub(17, 25)
        let key = id.hex
        let tag = b.sub(25, 41)
        let rh = b.sub(41, 57)
        let tokens = Int(b[57])
        let k = b.u16(58)
        if store[key] != nil { return }
        if let receipt = seen[key] { return ack(from, id, receipt) }
        let trusted = known(from)
        let p: Partial
        if let existing = partial[key] {
            p = existing
        } else {
            guard (1...MAX_K).contains(k), makeRoom(&partial, MAX_PARTIAL, STRANGER_PARTIAL, trusted),
                  let dec = try? Decoder(k, symbolSize: SYM) else { return }
            p = Partial(dec, now(), trusted)
            partial[key] = p
        }
        p.dec.add(b.u32(60), b.sub(64, b.count))
        guard p.dec.done else { return }
        partial[key] = nil
        guard let env = try? p.dec.result(), same(sha256(env, rh).sub(0, 8), id) else { return } // corrupted or forged packets
        if !tagsOf(identity.pub).contains(where: { same($0, tag) }) {
            guard makeRoom(&store, MAX_STORE, STRANGER_STORE, trusted) else { return }
            store[key] = Msg(id, tag, rh, env, tokens: min(max(tokens, 1), MAX_TOKENS), at: now(), own: false, trusted: trusted)
            return ack(from, id, proofOf(env, eid()))
        }
        guard let msg = try? open(identity, env), msg.body.count >= 24 else { return }
        // compared against bounds, not subtracted: a sender-chosen timestamp must not overflow the arithmetic
        let sentAt = msg.body.u64(16), t = now()
        guard t > TTL_MS + SKEW_MS, sentAt >= UInt64(t - TTL_MS - SKEW_MS), sentAt <= UInt64(t + SKEW_MS) else { return }
        let receipt = msg.body.sub(0, 16)
        if seen.count >= MAX_SEEN, let first = seen.keys.first { seen[first] = nil }
        seen[key] = receipt
        ack(from, id, receipt)
        let sender = contacts.pairs.first { same($0.1, msg.senderPub) }?.0 ?? "unknown:" + msg.senderPub.hex.prefix(8)
        onMessage(sender, String(decoding: msg.body[24...], as: UTF8.self))
    }

    private func onAck(_ b: Bytes) {
        guard b.count == 41 else { return }
        let from = b.sub(1, 9)
        let proof = b.sub(25, 41)
        let key = b.sub(17, 25).hex
        guard let m = store[key] else { return }
        // Anyone may relay a receipt: only the recipient could have produced it.
        if same(sha256(proof).sub(0, 16), m.rh) { store[key] = nil; return }
        guard isMe(b.sub(9, 17)), let give = pending[key + from.hex], give != 0, same(proofOf(m.env, from), proof) else { return }
        pending[key + from.hex] = nil
        if m.tokens > 1 { m.tokens -= min(give, m.tokens - 1) } else if !m.own { store[key] = nil }
    }

    public func sendMessage(_ name: String, _ text: String) throws {
        guard let pub = contacts[name] else { throw KairoError.unknownContact }
        var rng = SystemRandomNumberGenerator()
        let receipt = (0..<16).map { _ in UInt8.random(in: 0...255, using: &rng) }
        let rh = sha256(receipt).sub(0, 16)
        let env = try seal(identity, pub, cat(receipt, be64(UInt64(now())), Array(text.utf8)))
        guard (env.count + 4 + SYM - 1) / SYM <= MAX_K else { throw KairoError.tooLarge }
        guard makeRoom(&store, MAX_STORE, STRANGER_STORE, true) else { throw KairoError.outboxFull }
        let id = sha256(env, rh).sub(0, 8)
        store[id.hex] = Msg(id, recipientTag(pub, now()), rh, env, tokens: copies, at: now(), own: true, trusted: true)
    }

    /// Feed every frame heard on the radio; malformed frames are dropped.
    public func receive(_ frame: Bytes) {
        guard frame.count >= 10 else { return }
        switch frame[0] {
        case HELLO: onHello(frame)
        case SCORE: onScore(frame)
        case DATA: onData(frame)
        case ACK: onAck(frame)
        default: break
        }
    }

    /// Call every few seconds: expires old state, refills transmit budgets and announces what we carry.
    public func tick() {
        let t = now()
        store.removeAll { t - $0.at > TTL_MS }
        partial.removeAll { t - $0.at > PARTIAL_MS }
        if pending.count > 1000 { pending.removeAll() }
        burst.removeAll()
        scoreBudget = SCORE_BUDGET
        dataBudget = DATA_BUDGET
        let all = store.values
        let n = min(all.count, HELLO_SLOTS)
        let me = eid()
        var out = cat(u8(Int(HELLO)), me, u8(n))
        for i in 0..<n {
            let m = all[(cursor + i) % all.count]
            out += blind(m.id, me) + blind(m.tag, me)
        }
        cursor += n
        send(out)
    }
}
