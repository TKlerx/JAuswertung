import XCTest
@testable import TimingCore

final class TimingCoreTests: XCTestCase {
    func testOriginRequiresHTTPSAndRejectsRedirectableInputs() {
        XCTAssertEqual(DeskOrigin("HTTPS://Desk.local:443/")?.url.absoluteString, "https://desk.local")
        for invalid in ["http://desk.local", "https://user@desk.local", "https://desk.local/path",
                        "https://desk.local/?token=x", "https://desk.local/#x", "https://desk.local:"] {
            XCTAssertNil(DeskOrigin(invalid))
        }
    }

    func testManualTimeAndPersistentRetryPayload() throws {
        XCTAssertEqual(stopwatchMicros("1:05,20"), 65_200_000)
        for invalid in ["0", "-1,03", "1:60", "1:5", "4,", "9999999999999999999999999"] {
            XCTAssertNil(stopwatchMicros(invalid))
        }
        let capture = Capture(pairing: UUID(), attempt: UUID(), lane: 4, kind: .stopwatch, micros: 65_200_000)
        let recovered = try JSONDecoder().decode(Capture.self, from: JSONEncoder().encode(capture))
        XCTAssertEqual(try capture.body(), try recovered.body())
        let fields = try XCTUnwrap(JSONSerialization.jsonObject(with: recovered.body()) as? [String: Any])
        XCTAssertEqual(fields["bahn"] as? Int, 4)
        XCTAssertEqual(fields["dauer"] as? Int, 65_200_000)
        XCTAssertEqual(fields["ereignis"] as? String, capture.id.uuidString)
    }

    func testClockBudgetIsInclusiveAndRejectsOverflow() throws {
        let data = Data("""
            {"id":"\(UUID())","versatz":100,"unsicherheit":9999,"bezugszeit":100,"driftPpm":100,"auffrischungMs":250}
            """.utf8)
        let estimate = try JSONDecoder().decode(ClockEstimate.self, from: data)
        XCTAssertTrue(estimate.usable(at: 10_000))
        XCTAssertFalse(estimate.usable(at: 10_001))
        XCTAssertFalse(estimate.usable(at: .max))
    }

    func testTimingContractParsesStandbyAndPhysicalLaneNumber() throws {
        XCTAssertNil(try Heat.decode(Data(#"{"starter":false}"#.utf8)))
        let attempt = UUID()
        let data = Data("""
            {"versuch":"\(attempt)","lauf":"\(UUID())","disziplin":"50 m","zustand":"LAEUFT",
             "bereit":false,"abgleich":true,"bahnen":[{"nummer":5,"teilnehmer":"Team A","telefonzeit":true}]}
            """.utf8)
        let heat = try XCTUnwrap(Heat.decode(data))
        XCTAssertEqual(heat.attempt, attempt)
        XCTAssertEqual(heat.phase, .running)
        XCTAssertEqual(heat.lanes.first?.id, 5)
        XCTAssertTrue(heat.lanes.first?.timed ?? false)
    }
}
