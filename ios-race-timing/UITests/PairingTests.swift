import XCTest

final class PairingTests: XCTestCase {
    func testPairingScreenRejectsAnHTTPAddress() {
        let app = XCUIApplication()
        app.launch()
        let address = app.textFields["HTTPS-Adresse"]
        XCTAssertTrue(address.waitForExistence(timeout: 10))
        let pair = app.buttons["Koppeln"]
        XCTAssertFalse(pair.isEnabled)
        for (label, value) in [
            ("HTTPS-Adresse", "http://127.0.0.1"), ("Gerätename", "Testgeraet"),
            ("Kopplungscode", "abcdef12-3456-7890-abcd-ef1234567890")
        ] {
            let field = app.textFields[label]
            field.tap()
            field.typeText(value)
        }
        XCTAssertTrue(pair.isEnabled)
        pair.tap()
        XCTAssertTrue(app.staticTexts["Gib eine HTTPS-Adresse ohne Pfad ein."].waitForExistence(timeout: 5))
    }
}
