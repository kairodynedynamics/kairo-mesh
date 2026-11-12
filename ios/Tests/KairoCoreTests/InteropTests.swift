import Foundation
import XCTest
@testable import KairoCore

// Checks the Swift port byte-for-byte against vectors from the JavaScript reference (android/vectors.mjs),
// and writes frames the other way for `node android/interop-check.mjs ios/.build/interop-from-swift.json`.
final class InteropTests: XCTestCase {
    private static let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../..").standardized
    private let v = try! JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("android/app/src/test/resources/vectors.json"))) as! [String: Any]
    private func obj(_ k: String) -> [String: Any] { v[k] as! [String: Any] }
    private func hex(_ o: [String: Any], _ k: String) -> Bytes { (o[k] as! String).unhex! }
    private func int(_ o: [String: Any], _ k: String) -> Int64 { (o[k] as! NSNumber).int64Value }
    private var ali: Identity { try! Identity.create(hex(obj("keys"), "aliSecret")) }
    private var bora: Identity { try! Identity.create(hex(obj("keys"), "boraSecret")) }

    func testRng() {
        let r = obj("rng")
        var rng = Rng(UInt64(int(r, "seed")))
        // compared as integers: Linux Foundation's JSON parser rounds the last digits of these decimals
        for expected in r["out"] as! [Double] { XCTAssertEqual(UInt32((expected * 4294967296).rounded()), rng.next()) }
    }

    func testFountain() throws {
        let f = obj("fountain")
        let message = hex(f, "message")
        let enc = try Encoder(message)
        XCTAssertEqual(Int(int(f, "k")), enc.k)
        let dec = try Decoder(enc.k)
        for (i, p) in (f["packets"] as! [[String: Any]]).enumerated() {
            let seed = UInt64(int(p, "seed"))
            XCTAssertEqual(hex(p, "data"), enc.packet(seed))
            if i > 0 { dec.add(seed, hex(p, "data")) } // decode without the first plain fragment
        }
        XCTAssertEqual(message, try dec.result())
    }

    func testIdentitiesAndTags() {
        let keys = obj("keys")
        XCTAssertEqual(hex(keys, "aliPub"), ali.pub)
        XCTAssertEqual(hex(keys, "boraPub"), bora.pub)
        XCTAssertEqual(hex(keys, "boraTag"), recipientTag(bora.pub, int(obj("exchange"), "time")))
        XCTAssertEqual(hex(keys, "boraRk"), recognitionKey(bora.pub))
        let ids = obj("ids")
        XCTAssertEqual(hex(ids, "eid"), ephemeralId(hex(ids, "key"), int(ids, "time")))
    }

    func testOpensAnEnvelopeSealedInJavaScript() throws {
        let s = obj("seal")
        let opened = try open(bora, hex(s, "envelope"))
        XCTAssertEqual(ali.pub, opened.senderPub)
        XCTAssertEqual(hex(s, "body"), opened.body)
    }

    func testReceivesAMessageSentByTheJavaScriptNode() throws {
        let x = obj("exchange")
        var inbox: [String] = [], out: [Bytes] = []
        let node = Node(identity: bora, send: { out.append($0) }, onMessage: { inbox.append("\($0): \($1)") }, now: { self.int(x, "time") })
        try node.addContact("ali", ali.pub)
        for f in x["frames"] as! [String] { node.receive(f.unhex!) }
        XCTAssertEqual(["ali: \(x["text"] as! String)"], inbox)
        XCTAssertTrue(out.contains { $0[0] == 4 }, "delivery receipt sent")
    }

    func testWritesFramesForTheJavaScriptNode() throws {
        let t = int(obj("exchange"), "time")
        var out: [Bytes] = []
        let node = Node(identity: ali, send: { out.append($0) }, now: { t })
        try node.addContact("bora", bora.pub)
        let text = "Swift'ten merhaba ✓"
        try node.sendMessage("bora", text)
        node.tick()
        let hello = out.last!
        let boraEid = ephemeralId(recognitionKey(bora.pub), t)
        node.receive(cat(u8(2), boraEid, hello.sub(1, 9), hello.sub(10, 18), u8(4), Bytes(repeating: 0, count: 8)))
        let data = out.filter { $0[0] == 3 }
        XCTAssertFalse(data.isEmpty)
        let json: [String: Any] = ["time": t, "text": text, "envelope": try seal(ali, bora.pub, Array("merhaba".utf8)).hex, "frames": data.map(\.hex)]
        let dir = Self.root.appendingPathComponent("ios/.build")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try JSONSerialization.data(withJSONObject: json).write(to: dir.appendingPathComponent("interop-from-swift.json"))
    }
}
