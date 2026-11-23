# Validation quickstart

1. Run `mvn -f race-timing-desk/pom.xml verify` with Java 21. Run the resulting `race-timing-desk/target/race-timing-desk.jar` with `java -jar`.
2. Enter the event and heat numbers, select the occupied lanes and assign phones. For a synthetic heat, use lanes 2 and 6 as timed and lane 4 as stopwatch. Configure local HTTPS with a PKCS#12 server key and certificate trusted by the phone for the entered address. Pair the phone using its assignment’s one-time code.
3. Arm and wait for readiness. Start at the desk, capture lanes 2 and 6, enter a stopwatch time on lane 4 and review the result list. Export to a new ARES result file.
4. In unmodified JAuswertung 18.1.1, select **ARES21-Ergebnisdatei** in **Optionen → Elektronische Zeitnahme** and point it at the exported file. Open **Elektronische Zeitnahme**, select the matching heat, refresh, and compare the event, heat and occupied lanes before choosing **Eintragen**. The stock application can report the heat as incomplete when some lanes are empty. Confirm only if every occupied lane has the expected time.
5. Repeat the export flow with a missing lane and with a file write failure. Confirm the desk leaves results available for retry.

## Verification evidence

- The standalone Maven build and packaged Swing window passed. The desktop reactor still requires `filechooser-platform`. The standalone project builds independently.
- The synthetic HTTPS test paired a client for lanes 2, 4 and 6, accepted a desk start, captured timestamped finishes on lanes 2 and 6, and accepted a stopwatch time on lane 4. It rejected the unassigned client and acknowledged exact retries after export.
- The unmodified 18.1.1 Swing timing screen imported desk event 137, heat 2 into synthetic heat `137-02`. It applied `1:00,12`, `1:05,20` and `1:10,99` on lanes 2, 4 and 6. The adjacent heat stayed untouched. This check used official release JARs and a disposable competition in memory.
- The Android APK, audible onset, and timing on venue Wi-Fi still need a standalone desk pilot.

To generate the synthetic file again, supply an absolute, unused output path:

```powershell
mvn -f race-timing-desk/pom.xml -Dtest=ZeitnahmeDienstTest#exportiertTischstartMitZielUndStoppuhrzeiten "-Dares.fixture.output=C:/temps/event-137-lauf-2.txt" test
```
