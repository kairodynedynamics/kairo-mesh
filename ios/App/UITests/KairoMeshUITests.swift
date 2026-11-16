import XCTest

// Runs the real app on a simulator. Simulators have no Bluetooth, so this covers what can be checked without radio:
// the Keychain identity, pairing, sealing a message, and that the identity survives a relaunch.
final class KairoMeshUITests: XCTestCase {
    private func type(_ app: XCUIApplication, _ field: String, _ text: String) {
        let f = app.textFields[field]
        XCTAssertTrue(f.waitForExistence(timeout: 10), "field \(field)")
        f.tap()
        f.typeText(text)
    }

    // The Bluetooth and location permission prompts belong to SpringBoard and would swallow the first taps. On a fresh
    // install both appear, one after the other; the location one says "Allow While Using App".
    private func launch(_ app: XCUIApplication) {
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.buttons.matching(NSPredicate(format:
            "label IN {'Allow', 'OK', 'İzin Ver', 'Tamam', 'Allow While Using App', 'Uygulamayı Kullanırken İzin Ver'}")).firstMatch
        for _ in 0..<3 where allow.waitForExistence(timeout: 5) { allow.tap() }
        XCTAssertTrue(app.staticTexts["code"].waitForExistence(timeout: 10), "pairing code shown")
    }

    private func log(_ app: XCUIApplication, contains text: String) -> Bool {
        let predicate = NSPredicate(format: "label CONTAINS %@", text)
        return app.staticTexts.matching(identifier: "log").matching(predicate).firstMatch.waitForExistence(timeout: 10)
    }

    // Without an authority code the call is refused and the screen says why; with one, it is sealed and queued.
    func testEmergencyCall() {
        let app = XCUIApplication()
        launch(app)
        app.buttons["tur_enkaz"].tap()
        app.buttons["acilGonder"].tap()
        XCTAssertTrue(app.staticTexts["acilDurum"].waitForExistence(timeout: 5))
        XCTAssertTrue(log(app, contains: "ERROR kurum kodu yok"))
        // a well-formed code no root approved (e.g. a fake "AFAD's new code") is refused and not stored
        let key = Data(repeating: 7, count: 32).base64EncodedString()
        type(app, "kurum", "\(key);\(key);https://saldirgan.example;AFAD;\(Data(repeating: 1, count: 64).base64EncodedString())")
        app.buttons["kurumKaydet"].tap()
        XCTAssertTrue(log(app, contains: "ERROR kurum kodu geçersiz"))
        // a code approved by the test root (test/kurum-kodu-ornek.json; debug builds trust it), server need not exist
        app.textFields["kurum"].clearAndType("rgExj/KgKZhYcPxK/NqXSk3/nmndBd5JIsybQuPvEXg=;9sbrD4AbOZNuhvB+CDzzHS+lxvMaqOmg5eulfRvSH7k=;"
            + "https://kurum.example;Test Kurumu (yalnız test);"
            + "epNYkhZFP0A8TRWKthNdQg0KDvNv8fwfdUA2DI5r/v6Uz+3wXYe2jrn/WFPJ8+9MpXv1/zJNzndXsG1N5tPXBA==")
        app.buttons["kurumKaydet"].tap()
        XCTAssertTrue(log(app, contains: "KURUM https://kurum.example"))
        // the situation buttons sit in a lazy grid at the top: off screen on a phone, they are not in the hierarchy
        for _ in 0..<6 where !app.buttons["tur_enkaz"].isHittable { app.swipeDown() }
        app.buttons["tur_enkaz"].tap()
        app.buttons["acilGonder"].tap()
        XCTAssertTrue(log(app, contains: "ACIL_GONDERILDI"))
    }

    func testPairSendAndKeepIdentity() {
        let app = XCUIApplication()
        launch(app)
        let code = app.staticTexts["code"].label
        XCTAssertEqual(44, code.count, "pairing code is a base64 X25519 key")

        type(app, "name", "kendim")
        type(app, "pairing", "not-a-key")
        app.buttons["addContact"].tap()
        XCTAssertTrue(log(app, contains: "ERROR bad pairing code"))

        app.textFields["pairing"].clearAndType(code)
        app.buttons["addContact"].tap()
        type(app, "to", "kendim")
        type(app, "message", "merhaba")
        app.buttons["send"].tap()
        XCTAssertTrue(log(app, contains: "QUEUED to=kendim"))

        app.terminate()
        launch(app)
        XCTAssertEqual(code, app.staticTexts["code"].label, "identity is kept in the Keychain across launches")
    }
}

private extension XCUIElement {
    func clearAndType(_ text: String) {
        tap()
        if let value = value as? String, !value.isEmpty { typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: value.count)) }
        typeText(text)
    }
}
