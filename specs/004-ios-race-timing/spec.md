# iPhone pilot for JAuswertung timing

The iPhone client supports the lane official at a JAuswertung desk. Use iOS 17 or newer, the desk as starter, and the existing integrated HTTPS endpoints from the [Android consumer contract](../002-native-race-timing/contracts/desk-api.md).

- Pair once with one origin and show only the lanes assigned by the desk. Keep the bearer credential in the device Keychain. Validate the desk certificate and hostname through system HTTPS trust and refuse redirects.
- Capture timed finishes only after the desk reports a running heat and a usable clock estimate. Retain the 10 ms uncertainty limit. A manual stopwatch lane can report a positive elapsed time during a connection loss once the app has observed a running heat.
- Store events locally before sending. Retry the original event under the original pairing. Show pending, accepted, rejected and blocked outcomes. A new pairing never reuses an old pairing's pending events.
- Use SwiftUI and Swift Package Manager for the client. Run the contract tests and the simulator smoke test with the same Codemagic Xcode environment as Pitch & Pulse.

## Pilot verification

The Codemagic simulator build tests the pairing form and rejects plaintext addresses. A live rehearsal is separate. Pair with a certificate trusted by the iPhone for the desk address, run a desk countdown, capture two timed lanes and one manual lane, interrupt the connection, then recover and apply in JAuswertung. Confirm the original event identities and the correct stored times. Measure audible onset and clock accuracy at the pool with independent reference equipment before relying on timing there.
