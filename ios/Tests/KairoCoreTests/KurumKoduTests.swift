import Foundation
import XCTest
@testable import KairoCore

// Codes approved by the test root in JS (test/kurum-kodu-ornek.json): Swift must accept and reject the same ones.
final class KurumKoduTests: XCTestCase {
    private static let yol = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .appendingPathComponent("../../../test/kurum-kodu-ornek.json").standardized
    private let o = try! JSONSerialization.jsonObject(with: Data(contentsOf: yol)) as! [String: Any]
    private var kok: Bytes { Bytes(Data(base64Encoded: o["kok"] as! String)!) }

    func testAcceptsCodeApprovedByRoot() {
        let k = KurumKodu(o["gecerli"] as! String, kokler: [kok])
        XCTAssertEqual("https://kurum.example", k?.url)
        XCTAssertEqual("Test Kurumu (yalnız test)", k?.ad)
    }

    func testRejectsForgedOrUnapprovedCodes() {
        for (ad, kod) in o["gecersiz"] as! [String: String] { XCTAssertNil(KurumKodu(kod, kokler: [kok]), ad) }
        XCTAssertNil(KurumKodu(o["gecerli"] as! String, kokler: [Bytes(repeating: 9, count: 32)]), "unknown root")
    }
}
