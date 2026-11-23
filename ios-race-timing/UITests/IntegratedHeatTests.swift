import Foundation
import XCTest

@MainActor
final class IntegratedHeatTests: XCTestCase {
    func testOfflineFinishAndStopwatchAreAppliedToTheAssignedLanes() async throws {
        continueAfterFailure = false
        let invite = try await control("invite")
        let app = XCUIApplication()
        app.launch()
        let pair = app.buttons["Koppeln"]
        XCTAssertTrue(pair.waitForExistence(timeout: 12))
        let values = [
            ("HTTPS-Adresse", try XCTUnwrap(invite["address"])),
            ("Gerätename", "iPhone-Pilot"),
            ("Kopplungscode", try XCTUnwrap(invite["code"]))
        ]
        for (label, value) in values {
            let field = app.textFields[label]
            field.tap()
            field.typeText(value)
        }
        try tapVisible(pair, in: app)
        XCTAssertTrue(app.staticTexts["Startbereit"].waitForExistence(timeout: 30))
        let online = app.buttons["Ziel erfassen · Bahn 3"]
        let offline = app.buttons["Ziel erfassen · Bahn 5"]
        XCTAssertFalse(online.isEnabled)
        _ = try await control("start")
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: online)
        await fulfillment(of: [enabled], timeout: 15)
        try tapVisible(online, in: app)

        let accepted = app.staticTexts["Am Wettkampftisch eingegangen"]
        XCTAssertTrue(accepted.firstMatch.waitForExistence(timeout: 15))
        let stopwatch = app.textFields["Stoppuhrzeit für Bahn 4"]
        try tapVisible(stopwatch, in: app)
        stopwatch.typeText("1:05,20")
        app.swipeUp()
        try reveal(offline, in: app)
        XCTAssertTrue(offline.isEnabled)
        _ = try await control("offline")
        offline.tap()
        let pending = app.staticTexts["Gespeichert · wartet auf Bestätigung"]
        XCTAssertTrue(pending.firstMatch.waitForExistence(timeout: 12))
        try tapVisible(app.buttons["Zeit melden · Bahn 4"], in: app)
        let disconnected = app.staticTexts["Zuletzt empfangener Lauf"]
        app.swipeDown()
        XCTAssertTrue(disconnected.waitForExistence(timeout: 15))
        _ = try await control("online")

        let response = try await control("applyWhenComplete")
        let rows = try XCTUnwrap(response["rows"])
        let stored = rows.split(separator: "\n").map { $0.split(separator: ",") }
        XCTAssertEqual(stored.count, 4)
        XCTAssertEqual(stored.first(where: { $0.first == "4" })?.last, "6520")
        XCTAssertEqual(stored.first(where: { $0.first == "6" })?.last, "7890")
    }

    private func reveal(_ element: XCUIElement, in app: XCUIApplication) throws {
        for _ in 0..<4 where !element.isHittable {
            app.swipeUp()
        }
        XCTAssertTrue(element.isHittable)
    }

    private func tapVisible(_ element: XCUIElement, in app: XCUIApplication) throws {
        if !element.isHittable { app.swipeDown() }
        try reveal(element, in: app)
        element.tap()
    }

    private func control(_ operation: String) async throws -> [String: String] {
        let url = URL(string: "https://127.0.0.1:18443/" + operation)!
        let (data, response) = try await URLSession.shared.data(from: url)
        let body = try JSONDecoder().decode([String: String].self, from: data)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200, body["error"] ?? operation)
        return body
    }
}
