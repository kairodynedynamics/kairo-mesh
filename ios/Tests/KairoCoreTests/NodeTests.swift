import XCTest
@testable import KairoCore

final class NodeTests: XCTestCase {
    // Three phones on a bench; `links` says who can hear whom. Frames are delivered after the current call returns.
    private final class World {
        var t: Int64 = 1_800_000_000_000
        var links = Set<String>()
        var inbox: [String: [String]] = [:]
        private var queue: [() -> Void] = []
        var nodes: [String: Node] = [:]

        init(_ names: [String]) throws {
            let ids = try Dictionary(uniqueKeysWithValues: names.map { ($0, try Identity.create()) })
            for n in names {
                inbox[n] = []
                nodes[n] = Node(identity: ids[n]!, send: { [unowned self] f in
                    for m in names where self.links.contains("\(n)>\(m)") { self.queue.append { self.nodes[m]!.receive(f) } }
                }, onMessage: { [unowned self] from, text in self.inbox[n]!.append("\(from): \(text)") }, now: { [unowned self] in self.t })
            }
            for a in names { for b in names where a != b { try nodes[a]!.addContact(b, ids[b]!.pub) } }
        }
        func link(_ a: String, _ b: String, _ on: Bool = true) {
            for k in ["\(a)>\(b)", "\(b)>\(a)"] { if on { links.insert(k) } else { links.remove(k) } }
        }
        func run(_ rounds: Int) {
            for _ in 0..<rounds {
                t += 2000
                nodes.values.forEach { $0.tick() }
                while !queue.isEmpty { queue.removeFirst()() }
            }
        }
    }

    private func meet(_ w: World, _ a: String, _ b: String) { w.link(a, b); w.run(3); w.link(a, b, false) }
    private func loaded() throws -> World {
        let w = try World(["ali", "k1", "k2", "bora"])
        try w.nodes["ali"]!.sendMessage("bora", "okul bahçesi")
        meet(w, "ali", "k1"); meet(w, "ali", "k2")
        XCTAssertEqual(1, w.nodes["k1"]!.carrying); XCTAssertEqual(1, w.nodes["k2"]!.carrying)
        return w
    }

    func testTwoCouriersReachingTheRecipientTogetherDeliverOnce() throws {
        let w = try loaded()
        w.link("k1", "bora"); w.link("k2", "bora"); w.run(3)
        XCTAssertEqual(["ali: okul bahçesi"], w.inbox["bora"])
        XCTAssertEqual(0, w.nodes["k1"]!.carrying); XCTAssertEqual(0, w.nodes["k2"]!.carrying)
    }

    func testNewsOfDeliveryReachesTheSenderAndOtherCouriers() throws {
        let w = try loaded()
        meet(w, "k1", "bora"); meet(w, "k1", "k2")
        XCTAssertEqual(0, w.nodes["k1"]!.carrying); XCTAssertEqual(0, w.nodes["k2"]!.carrying)
        meet(w, "ali", "k1")
        XCTAssertEqual(0, w.nodes["ali"]!.carrying)
    }

    func testLowBatteryPhoneCarriesOnlyItsOwnMail() throws {
        let w = try World(["ali", "k1", "bora"])
        w.nodes["k1"]!.relay = false
        try w.nodes["ali"]!.sendMessage("bora", "başkasının")
        meet(w, "ali", "k1")
        XCTAssertEqual(0, w.nodes["k1"]!.carrying)
        try w.nodes["ali"]!.sendMessage("k1", "sana")
        try w.nodes["k1"]!.sendMessage("bora", "kendi mesajım")
        meet(w, "ali", "k1"); meet(w, "k1", "bora")
        XCTAssertEqual(["ali: sana"], w.inbox["k1"])
        XCTAssertEqual(["k1: kendi mesajım"], w.inbox["bora"])
    }

    func testCourierCarriesBetweenPeopleWhoNeverMeet() throws {
        let w = try World(["ali", "kurye", "bora"])
        try w.nodes["ali"]!.sendMessage("bora", "okul bahçesinde buluşalım")
        w.link("ali", "kurye"); w.run(3)
        XCTAssertEqual(1, w.nodes["kurye"]!.carrying)
        w.link("ali", "kurye", false); w.link("kurye", "bora"); w.run(3)
        XCTAssertEqual(["ali: okul bahçesinde buluşalım"], w.inbox["bora"])
        XCTAssertEqual(0, w.nodes["kurye"]!.carrying)
        w.run(3)
        XCTAssertEqual(1, w.inbox["bora"]!.count)
    }

    // Swift traps on an out-of-range index where Kotlin throws, so every length must be checked before reading.
    func testGarbageDoesNotCrashOrDropMail() throws {
        let w = try World(["ali", "kurye", "bora"])
        try w.nodes["ali"]!.sendMessage("bora", "gizli")
        w.link("ali", "kurye"); w.run(3); w.link("ali", "kurye", false)
        var rng = SystemRandomNumberGenerator()
        let kurye = w.nodes["kurye"]!
        for _ in 0..<2000 { kurye.receive((0..<Int.random(in: 1...500, using: &rng)).map { _ in UInt8.random(in: 0...255, using: &rng) }) }
        for type in 0...5 {
            for len in 0...520 { kurye.receive([UInt8(type)] + (0..<len).map { _ in UInt8.random(in: 0...255, using: &rng) }) }
        }
        XCTAssertEqual(1, kurye.carrying)
        w.link("kurye", "bora"); w.run(3)
        XCTAssertEqual(["ali: gizli"], w.inbox["bora"])
    }

    // A contact seals a timestamp that wrapped arithmetic would read as "now": it must be refused like any stale one.
    func testSealedTimestampCannotOverflowTheFreshnessCheck() throws {
        let ali = try Identity.create(), bora = try Identity.create()
        let t: Int64 = 1_800_000_000_000
        var inbox: [String] = []
        let node = Node(identity: bora, send: { _ in }, onMessage: { inbox.append($1) }, now: { t })
        try node.addContact("ali", ali.pub)
        func deliver(_ sentAt: UInt64, _ text: String) throws {
            let receipt = Bytes(0..<16)
            let rh = sha256(receipt).sub(0, 16)
            let env = try seal(ali, bora.pub, cat(receipt, be64(sentAt), Array(text.utf8)))
            let enc = try Encoder(env, symbolSize: 400)
            let head = cat(u8(3), Bytes(repeating: 0, count: 8), ephemeralId(recognitionKey(bora.pub), t), sha256(env, rh).sub(0, 8),
                           recipientTag(bora.pub, t), rh, u8(1, enc.k >> 8, enc.k & 255))
            for i in 0..<UInt64(enc.k) { node.receive(cat(head, be32(i), enc.packet(i))) }
        }
        try deliver(UInt64(t) &+ (1 << 63), "wrapped")
        try deliver(UInt64.max, "far future")
        try deliver(UInt64(t), "fresh")
        XCTAssertEqual(["fresh"], inbox)
    }

    func testReplayAfterRestartIsNotDeliveredAgain() throws {
        let ali = try Identity.create(), bora = try Identity.create()
        var heard: [Bytes] = [] // everything ali transmits, as an eavesdropper would record it
        var inbox: [String] = []
        let t: Int64 = 1_800_000_000_000
        var first: Node!
        let sender = Node(identity: ali, send: { heard.append($0); first.receive($0) }, now: { t })
        try sender.addContact("bora", bora.pub)
        func recipient(_ delivered: [(String, String)]) throws -> Node {
            let n = Node(identity: bora, send: { sender.receive($0) }, onMessage: { inbox.append($1) }, now: { t }, delivered: delivered)
            try n.addContact("ali", ali.pub)
            return n
        }
        first = try recipient([])
        try sender.sendMessage("bora", "bir kez")
        sender.tick(); first.tick(); sender.tick()
        XCTAssertEqual(["bir kez"], inbox)

        let restarted = try recipient(first.delivered)
        heard.forEach(restarted.receive)
        XCTAssertEqual(["bir kez"], inbox)
        // without the persisted set the same replay works: this is the attack the persistence closes
        let forgetful = try recipient([])
        heard.forEach(forgetful.receive)
        XCTAssertEqual(["bir kez", "bir kez"], inbox)
    }
}
