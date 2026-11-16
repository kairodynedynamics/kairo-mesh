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

    // The Bluetooth permission prompt belongs to SpringBoard and would swallow the first taps.
    private func launch(_ app: XCUIApplication) {
        app.launch()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons.matching(
            NSPredicate(format: "label IN {'Allow', 'OK', 'İzin Ver', 'Tamam'}")).firstMatch
        if allow.waitForExistence(timeout: 5) { allow.tap() }
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
        // a well-formed code: two 32-byte keys and a server address (the server need not be reachable here)
        let key = Data(repeating: 7, count: 32).base64EncodedString()
        type(app, "kurum", "\(key);\(key);https://kurum.example")
        app.buttons["kurumKaydet"].tap()
        XCTAssertTrue(log(app, contains: "KURUM https://kurum.example"))
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
