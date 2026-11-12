import XCTest

// Runs the real app on a simulator. Simulators have no Bluetooth, so this covers what can be checked without radio:
// the Keychain identity, pairing, sealing a message, and that the identity survives a relaunch.
final class KairoMeshUITests: XCTestCase {
    private func type(_ app: XCUIApplication, _ field: String, _ text: String) {
        let f = app.textFields[field]
        f.tap()
        f.typeText(text)
    }

    func testPairSendAndKeepIdentity() {
        let app = XCUIApplication()
        app.launch()
        let code = app.staticTexts["code"].label
        XCTAssertEqual(44, code.count, "pairing code is a base64 X25519 key")

        type(app, "name", "kendim")
        type(app, "pairing", "not-a-key")
        app.buttons["Add contact"].tap()
        XCTAssertTrue(app.staticTexts["log"].label.contains("ERROR bad pairing code"))

        app.textFields["pairing"].clearAndType(code)
        app.buttons["Add contact"].tap()
        type(app, "to", "kendim")
        type(app, "message", "merhaba")
        app.buttons["Send"].tap()
        XCTAssertTrue(app.staticTexts["log"].label.contains("QUEUED to=kendim"))

        app.terminate()
        app.launch()
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
