# Validation quickstart

The integrated JAuswertung plugin is the preferred desktop path for further development. It hosts the same local HTTPS API inside JAuswertung. The standalone desk and stock ARES importer provide an independent pilot and compatibility path.

1. Run `mvn -f race-timing-desk/pom.xml verify` with Java 21. Run the resulting `race-timing-desk/target/race-timing-desk.jar` with `java -jar`.
2. Enter the event and heat numbers, select the occupied lanes and assign phones. For a synthetic heat, use lanes 2 and 6 as timed and lane 4 as stopwatch. Configure local HTTPS with a PKCS#12 server key and certificate trusted by the phone for the entered address. Pair the phone using its assignment’s one-time code.
3. Arm and wait for readiness. Start at the desk, capture lanes 2 and 6, enter a stopwatch time on lane 4 and review the result list. Export to a new ARES result file.
4. In unmodified JAuswertung 18.1.1, select **ARES21-Ergebnisdatei** in **Optionen → Elektronische Zeitnahme** and point it at the exported file. Open **Elektronische Zeitnahme**, select the matching heat, refresh, and compare the event, heat and occupied lanes before choosing **Eintragen**. The stock application can report the heat as incomplete when some lanes are empty. Confirm only if every occupied lane has the expected time.
5. Repeat the export flow with a missing lane and with a file write failure. Confirm the desk leaves results available for retry.

## Verification evidence

- The standalone Maven build and packaged Swing window passed. The desktop reactor still requires `filechooser-platform`. The standalone project builds independently.
- The synthetic HTTPS test paired a client for lanes 2, 4 and 6, accepted a desk start, captured timestamped finishes on lanes 2 and 6, and accepted a stopwatch time on lane 4. It rejected the unassigned client and acknowledged exact retries after export.
- The unmodified 18.1.1 Swing timing screen imported desk event 137, heat 2 into synthetic heat `137-02`. It applied `1:00,12`, `1:05,20` and `1:10,99` on lanes 2, 4 and 6. The adjacent heat stayed untouched. This check used official release JARs and a disposable competition in memory.
- The Android 10 emulator completed a live run with the debug APK and the standalone Swing desk. Android instrumentation invoked the app's timing actions with its regular HTTPS client and Room outbox. The desk played the four-tone countdown. Before the long tone, the app blocked finish capture. After lane 2 was accepted, the test dropped traffic to the desk. The app kept the timed finish for lane 6 and the manual `1:05,20` for lane 4 pending. The desk still held only lane 2.
- On reconnect the app automatically delivered the original records, preserving event IDs, timestamps and clock estimates. The desk reviewed and exported one result per lane. The stock JAuswertung 18.1.1 import applied `0:00,54` to lane 2, `1:05,20` to lane 4 and `0:01,18` to lane 6 in heat `137-02`. The short synthetic times triggered the stock plausibility confirmation. The neighboring heat was unchanged. Run evidence is under `target/standalone-timing-validation/android-e2e/instrumented-zh23md9l/`.
- Physical speaker onset and venue Wi-Fi are separate checks. Spec 002 T024 still covers the complete run against the integrated plugin and direct result transfer.

To generate the synthetic file again, supply an absolute, unused output path:

```powershell
mvn -f race-timing-desk/pom.xml -Dtest=ZeitnahmeDienstTest#exportiertTischstartMitZielUndStoppuhrzeiten "-Dares.fixture.output=C:/temps/event-137-lauf-2.txt" test
```
