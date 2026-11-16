import Foundation
import XCTest
@testable import KairoCore

// The Swift tokenizer and encoder must reproduce PyTorch on the golden messages (triyaj/telefon/kodlayici-altin.json,
// written by `python triyaj/kodlayici.py telefon` with the same float16 weights).
final class KodlayiciTests: XCTestCase {
    private static let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../triyaj/telefon").standardized
    private static let kod = try! Kodlayici(meta: Data(contentsOf: dir.appendingPathComponent("kodlayici.json")),
                                            weights: Data(contentsOf: dir.appendingPathComponent("kodlayici.bin")))
    private let altin = try! JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent("kodlayici-altin.json"))) as! [[String: Any]]

    func testTokenizesLikeTheTrainingTokenizer() {
        for a in altin {
            XCTAssertEqual(Self.kod.belirtecler(a["metin"] as! String), (a["ids"] as! [NSNumber]).map(\.intValue), a["metin"] as! String)
        }
    }

    func testMatchesPyTorchOnGoldenMessages() {
        for (k, a) in altin.enumerated() {
            let got = Self.kod.ham(a["metin"] as! String)
            for (h, ad) in ["tur", "aciliyet", "ozel", "il", "ilce"].enumerated() {
                let want = (a[ad] as! [NSNumber]).map(\.floatValue)
                XCTAssertEqual(want.count, got[h].count, ad)
                for i in got[h].indices { XCTAssertEqual(want[i], got[h][i], accuracy: 5e-3, "\(ad) \(k)[\(i)]") }
            }
        }
    }

    func testHalfFloatConversion() {
        XCTAssertEqual(Kodlayici.yarim(0x3C00), 1); XCTAssertEqual(Kodlayici.yarim(0xC000), -2)
        XCTAssertEqual(Kodlayici.yarim(0x7BFF), 65504); XCTAssertEqual(Kodlayici.yarim(0x0001), 5.9604645e-8)
    }

    func testTriageWithTheEncoderReadsWordsTheCharacterModelNeverSaw() throws {
        let t = try Triyaj(meta: Data(contentsOf: Self.dir.appendingPathComponent("triyaj.json")),
                           weights: Data(contentsOf: Self.dir.appendingPathComponent("triyaj.bin")), kodlayici: Self.kod)
        let r = t.coz("harabenin altındayız ses veriyoruz")
        XCTAssertEqual(r.tur, "enkaz")
        XCTAssertLessThanOrEqual(r.aciliyet, 2)
        // the address still comes from the character model
        let a = t.coz("hatay antakya ürgenpaşa mh enkaz altındayız 3 kişi bebek var")
        XCTAssertEqual(a.il, "Hatay"); XCTAssertEqual(a.kisi, 3); XCTAssertTrue(a.ozel.contains("bebek"))
    }
}
