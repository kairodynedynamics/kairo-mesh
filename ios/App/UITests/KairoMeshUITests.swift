import XCTest

// Runs the real app on a simulator. Simulators have no Bluetooth, so this covers what can be checked without radio:
// the emergency screen, undoing a mistaken tap, the countdown, sealing a call, and that the identity survives a relaunch.
final class KairoMeshUITests: XCTestCase {
    private func type(_ app: XCUIApplication, _ field: String, _ text: String) {
        let f = app.textFields[field]
        XCTAssertTrue(f.waitForExistence(timeout: 10), "field \(field)")
        f.tap()
        f.typeText(text)
    }

    // The Bluetooth and location permission prompts belong to SpringBoard and would swallow the first taps. On a fresh
    // install both appear, one after the other; the location one says "Allow While Using App".
    // kurumYok: start as a build with no authority built in; otherwise a test authority whose address does not exist
    private func launch(_ app: XCUIApplication, kurumYok: Bool = false) {
        // never the live demo authority: CI simulators have internet, and their calls reached its dashboard
        app.launchArguments = kurumYok ? ["-demoKurumYok"] : ["-testKurumu"]
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.buttons.matching(NSPredicate(format:
            "label IN {'Allow', 'OK', 'İzin Ver', 'Tamam', 'Allow While Using App', 'Uygulamayı Kullanırken İzin Ver'}")).firstMatch
        for _ in 0..<3 where allow.waitForExistence(timeout: 5) { allow.tap() }
        XCTAssertTrue(app.buttons["acilGonder"].waitForExistence(timeout: 10), "emergency screen shown")
    }

    private func scrollTo(_ element: XCUIElement, in app: XCUIApplication, direction: String = "up") {
        let sv = app.scrollViews.firstMatch
        for _ in 0..<10 where element.exists && !element.isHittable {
            if direction == "up" { sv.swipeUp() } else { sv.swipeDown() }
        }
    }

    private func log(_ app: XCUIApplication, contains text: String, timeout: TimeInterval = 10) -> Bool {
        let predicate = NSPredicate(format: "label CONTAINS %@", text)
        return app.staticTexts.matching(identifier: "log").matching(predicate).firstMatch.waitForExistence(timeout: timeout)
    }

    // Without an authority the call is refused and the screen says why; with the built-in one it is sealed and queued.
    // (Codes no root approved are refused in KurumKoduTests: there is no screen to enter one any more.)
    func testEmergencyCall() {
        let app = XCUIApplication()
        launch(app, kurumYok: true)
        app.buttons["tur_enkaz"].tap()
        XCTAssertEqual(app.buttons["tur_enkaz"].value as? String, "seçili")
        app.buttons["tur_enkaz"].tap() // a second tap undoes a mistaken one
        XCTAssertEqual(app.buttons["tur_enkaz"].value as? String, "seçili değil")
        app.buttons["tur_enkaz"].tap()
        scrollTo(app.buttons["acilGonder"], in: app)
        app.buttons["acilGonder"].tap()
        XCTAssertTrue(app.staticTexts["acilDurum"].waitForExistence(timeout: 5))
        XCTAssertTrue(log(app, contains: "ERROR kurum kodu yok"))

        app.terminate()
        launch(app)
        scrollTo(app.buttons["tur_enkaz"], in: app, direction: "down")
        app.buttons["tur_enkaz"].tap()
        scrollTo(app.buttons["acilGonder"], in: app)
        // a stray tap must not send: the countdown can be cancelled, and nothing is sealed
        app.buttons["acilGonder"].tap()
        XCTAssertTrue(app.buttons["iptal"].waitForExistence(timeout: 3))
        app.buttons["iptal"].tap()
        XCTAssertFalse(log(app, contains: "ACIL_GONDERILDI", timeout: 7), "cancelled during the countdown")
        app.buttons["acilGonder"].tap()
        app.buttons["hemenGonder"].tap()
        XCTAssertTrue(log(app, contains: "ACIL_GONDERILDI", timeout: 20))
    }

    func testKeepsIdentityAcrossLaunches() {
        let app = XCUIApplication()
        launch(app)
        let ready = app.staticTexts.matching(identifier: "log").firstMatch
        XCTAssertTrue(log(app, contains: "READY code="))
        let code = ready.label.components(separatedBy: "READY code=")[1].prefix(44)
        app.terminate()
        launch(app)
        XCTAssertTrue(log(app, contains: "READY code=\(code)"), "identity is kept in the Keychain across launches")
    }
}

private extension XCUIElement {
    func clearAndType(_ text: String) {
        tap()
        if let value = value as? String, !value.isEmpty { typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: value.count)) }
        typeText(text)
    }
}
