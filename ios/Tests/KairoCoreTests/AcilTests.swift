import XCTest
@testable import KairoCore

// Emergency relay on the Swift node, the same scenarios as test/acil.test.js and AcilTest.kt. The "authority" here
// only produces receipts: it opens the envelope (minus the 8-byte work nonce) with its key and returns the receipt.
final class AcilTests: XCTestCase {
    private final class World {
        var t: Int64 = 1_800_000_000_000
        var links = Set<String>(), online = Set<String>()
        var received: [String] = []
        var delivered: [String: [String]] = [:]
        private var queue: [() -> Void] = []
        var nodes: [String: Node] = [:]

        init(_ names: [String], _ kurum: Identity) throws {
            for n in names {
                delivered[n] = []
                nodes[n] = Node(identity: try Identity.create(), send: { [unowned self] f in
                    for m in names where self.links.contains("\(n)>\(m)") { self.queue.append { self.nodes[m]!.receive(f) } }
                }, now: { [unowned self] in self.t }, authority: kurum.pub, online: { [unowned self] in self.online.contains(n) },
                uplink: { [unowned self] env, done in
                    if hasWork(env), let o = try? open(kurum, env.sub(0, env.count - 8)) {
                        self.received.append(String(decoding: o.body[24...], as: UTF8.self)); done(o.body.sub(0, 16))
                    } else { done(nil) }
                }, onEmergencyDelivered: { [unowned self] id in self.delivered[n]!.append(id) })
            }
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
        func meet(_ a: String, _ b: String, _ rounds: Int = 4) { link(a, b); run(rounds); link(a, b, false) }
    }

    private let rapor = "{\"tur\":\"enkaz\",\"aciliyet\":1,\"kisi\":3}"

    func testPhoneWithInternetInRangeUploadsAndTheReceiptReachesTheSender() throws {
        let kurum = try Identity.create()
        let w = try World(["ayse", "gecit"], kurum)
        w.online.insert("gecit")
        let id = try w.nodes["ayse"]!.sendEmergency(rapor)
        w.meet("ayse", "gecit")
        XCTAssertEqual([rapor], w.received)
        XCTAssertEqual([id], w.delivered["ayse"])
        XCTAssertEqual(0, w.nodes["ayse"]!.carrying)
        XCTAssertEqual(0, w.nodes["gecit"]!.carrying)
    }

    func testOfflineCourierUploadsWhenItGetsSignalAndTheNewsComesBack() throws {
        let kurum = try Identity.create()
        let w = try World(["ayse", "kurye", "baska"], kurum)
        try w.nodes["ayse"]!.sendEmergency(rapor)
        w.meet("ayse", "kurye"); w.meet("ayse", "baska")
        XCTAssertEqual(1, w.nodes["kurye"]!.carrying)
        XCTAssertEqual(0, w.received.count)
        w.online.insert("kurye")
        w.run(2)
        XCTAssertEqual(1, w.received.count)
        w.meet("kurye", "baska")
        XCTAssertEqual(0, w.nodes["baska"]!.carrying)
        w.meet("baska", "ayse")
        XCTAssertEqual(1, w.delivered["ayse"]!.count)
    }

    func testACourierThatRestartsStillDeliversWhatItCarried() throws {
        let kurum = try Identity.create()
        let w = try World(["ayse", "kurye"], kurum)
        try w.nodes["ayse"]!.sendEmergency(rapor)
        w.meet("ayse", "kurye")
        let disk = w.nodes["kurye"]!.kayit()
        // iOS ended the app: a new node, restored from disk, then the phone finds a signal
        var yuklenen = 0
        let yeni = Node(identity: try Identity.create(), send: { _ in }, now: { w.t }, authority: kurum.pub, online: { true },
                        uplink: { env, done in yuklenen += 1; done((try? open(kurum, env.sub(0, env.count - 8)))?.body.sub(0, 16)) })
        yeni.yukle(disk + "bozuk satır\n")
        XCTAssertEqual(1, yeni.carrying)
        XCTAssertEqual(0, yeni.kendiAcil)
        yeni.tick()
        XCTAssertEqual(1, yuklenen)
        XCTAssertEqual(0, yeni.carrying)
        let ayse = Node(identity: try Identity.create(), send: { _ in }, now: { w.t }, authority: kurum.pub)
        ayse.yukle(w.nodes["ayse"]!.kayit())
        XCTAssertEqual(1, ayse.kendiAcil)
    }

    func testCouriersRefuseAuthorityMailWithoutWork() throws {
        let kurum = try Identity.create()
        let w = try World(["ayse", "kurye"], kurum)
        try w.nodes["ayse"]!.addContact("sahte", kurum.pub)
        try w.nodes["ayse"]!.sendMessage("sahte", rapor) // sealed to the authority, but without work
        w.meet("ayse", "kurye")
        XCTAssertEqual(0, w.nodes["kurye"]!.carrying)
        try w.nodes["ayse"]!.sendEmergency(rapor)
        w.meet("ayse", "kurye")
        XCTAssertEqual(1, w.nodes["kurye"]!.carrying)
    }

    func testWorkIsChecked() {
        let env = addWork(Bytes((0..<300).map { UInt8($0 & 255) }))
        XCTAssertTrue(hasWork(env))
        var altered = env
        altered[0] ^= 1
        XCTAssertFalse(hasWork(altered))
    }
}
