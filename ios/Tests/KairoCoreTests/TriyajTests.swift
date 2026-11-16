import Foundation
import XCTest
@testable import KairoCore

// The Swift forward pass must reproduce PyTorch's outputs on the golden messages (triyaj/telefon/altin.json,
// written by `python triyaj/model.py agirlik`), and decode them to the same record.
final class TriyajTests: XCTestCase {
    private static let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../triyaj/telefon").standardized
    private let model = try! Triyaj(meta: Data(contentsOf: dir.appendingPathComponent("triyaj.json")),
                                    weights: Data(contentsOf: dir.appendingPathComponent("triyaj.bin")))
    private let altin = try! JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent("altin.json"))) as! [[String: Any]]

    private func close(_ want: Any?, _ got: [Float], _ what: String) {
        let w = (want as! [NSNumber]).map { $0.floatValue }
        XCTAssertEqual(w.count, got.count, what)
        for i in got.indices { XCTAssertEqual(w[i], got[i], accuracy: 2e-3, "\(what)[\(i)]") }
    }

    func testMatchesPyTorchOnGoldenMessages() {
        for (k, a) in altin.enumerated() {
            let r = model.ham(a["metin"] as! String)
            let e = a["etiket"] as! [Any]
            XCTAssertEqual(e.count, r.etiket.count)
            for i in r.etiket.indices { close(e[i], r.etiket[i], "etiket \(k)/\(i)") }
            close(a["tur"], r.tur, "tur \(k)"); close(a["aciliyet"], r.aciliyet, "aciliyet \(k)")
            close(a["ozel"], r.ozel, "ozel \(k)"); close(a["il"], r.il, "il \(k)"); close(a["ilce"], r.ilce, "ilce \(k)")
        }
    }

    func testDecodesToTheSameRecord() {
        for a in altin {
            let want = a["kayit"] as! [String: Any]
            let got = model.coz(a["metin"] as! String)
            func s(_ key: String) -> String? { want[key].flatMap { $0 is NSNull ? nil : "\($0)" } }
            XCTAssertEqual(s("tur"), got.tur)
            XCTAssertEqual(s("aciliyet"), String(got.aciliyet))
            XCTAssertEqual(s("kisi"), got.kisi.map(String.init))
            XCTAssertEqual(s("il"), got.il); XCTAssertEqual(s("ilce"), got.ilce)
            XCTAssertEqual(s("mahalle"), got.mahalle); XCTAssertEqual(s("sokak"), got.sokak)
            XCTAssertEqual(s("bina"), got.bina); XCTAssertEqual(s("ad"), got.ad); XCTAssertEqual(s("tel"), got.tel)
            XCTAssertEqual(want["ozel"] as? [String], got.ozel)
        }
    }

    func testEmojiDoesNotShiftSpans() {
        let r = model.coz("🙏🙏 hatay antakya ürgenpaşa mh enkaz altındayız 3 kişi")
        XCTAssertEqual("enkaz", r.tur)
        XCTAssertTrue(r.mahalle == nil || r.mahalle!.contains("ürgenpaşa"), r.mahalle ?? "")
    }
}
