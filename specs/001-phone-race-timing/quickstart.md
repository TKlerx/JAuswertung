# Validation Quickstart

Run these checks as the milestones are implemented.

## Prerequisites

Use Java 25 and Maven. Install sibling dependencies via the workspace build script documented in AGENTS.md. In the current workspace, the upstream sibling builds restored jutils and commons.xmt in the local Maven cache. The reactor reaches jauswertung-swing and stops on org.lisasp.swing:filechooser-platform:1.0.0-SNAPSHOT. The public filechooser checkout lacks that module, and GitHub Packages returned HTTP 401. T001 remains open until the exact dependency is available and the reactor builds.

## Simulator

Run the automated scenarios with `mvn -pl jauswertung-plugins-input -am test` after implementation. Open the simulator and use fixed seeds to compare batches.

Compile with `mvn -pl jauswertung -am compile` once the dependency is available. Launch JAuswertung with the entry point and working directory in AGENTS.md. Choose **Information → Zeitnahme-Simulator** without opening a competition. The eight simulator tests and the menu-launch smoke test were verified in isolation on Java 25 using the real PluginManager and its loaded descriptor.

1. Run four virtual lanes with symmetric delays and zero drift. Both starter modes yield zero clock-induced lane error.
2. Add jitter or one-way imbalance. Check timeout rates and ready-time percentiles. Verify every Ready heat stays inside 10 ms under the model.
3. Change the network path and reconnect a virtual phone with clock drift fixed. Check resampling, valid estimate reuse, Resync after expiry, and recovery.
4. Increase the expected duration until the bound fails and verify resampling.
5. Set all lanes to stopwatch mode. Verify the desktop starter needs no sync. With a phone starter, verify that a missing, expired, or greater-than-10-ms grant-deadline bound prevents Ready, and that an eligible estimate allows Ready without syncing lane phones.

## JAuswertung heat

For T010–T014, open **Information → Zeitnahme-Simulator → Virtueller Probelauf** for a standalone heat with three fictional teams. Assign lanes 3 and 4 to one phone and lane 5 to another. Before arming, check the 400 ms default and the 200 ms and 1,500 ms limits. Play a test tone and confirm Start is still disabled until arming. Run once from the desk and once from the virtual phone. With a virtual phone, set a delivery delay, finish a lane while the start report is pending, then retry both messages. Check that each target time is captured once and retains its timestamp. This check only needs one computer.

For the live plugin, choose **Information → Zeitnahme** with a loaded competition and generated heats. The anonymized `jauswertung-io/src/test/resources/competitions/dem24/DEM2024_Mannschaft_Vorlauf_Final_anonym.wk` fixture can be used to generate a classic heat list in memory. Select a populated heat, arm it and capture virtual finishes. Observe the elapsed times in the dialog. Measure the physical speaker onset separately in the device pilot.

For T015–T019, use the virtual trial with lane 3 in phone timing mode, lane 4 assigned to **Stoppuhr · Handy B**, and lane 5 in **Stoppuhr** mode. After the start, capture lane 3 normally. Enter `1:05,20` on the virtual handset for lane 4 and `1:10,01` at the desk for lane 5. Open **Ergebnisse prüfen**, check the sources, and select **Zeiten übernehmen**. The actual in-memory competition must contain 6,520 hundredths for lane 4 and 7,001 hundredths for lane 5. The standalone trial uses a dedicated practice competition.

Launch with the entry point and working directory in AGENTS.md. Open a test competition and assign two phone-timed lanes to one virtual phone. Include one manual lane and one omitted lane with an existing result. Test both starter modes and both classic and open-water heat mapping. Delay start and finish delivery independently and retry a finish. Check that the elapsed time matches in-order delivery. Reject an overlapping assignment, accept a manual mobile entry with expired sync, and reject a submission from another phone. Review and apply the results, verify the omitted lane keeps its time and penalties, then undo and redo the operation. Change an assignment before Apply and verify the stale review is rejected before mutation. After a successful Apply, send a first finish for an omitted lane and verify Attempt closed without a model write. Retry an already accepted event after Apply and after preparing another attempt. Verify the original acknowledgement returns and neither attempt changes.

Hold the shared competition guard in a simulated background operation. Verify the panel marks its last snapshot stale and prevents arming. Verify Apply reports busy, preserves the review and writes nothing. Change an assignment, then release the guard and refresh. Check that the panel shows the new assignment and refuses the stale review. Capture virtual finishes with upper bounds exactly at and just past the horizon. Confirm only the latter requires explicit approval if clock uncertainty is otherwise within budget. Then replace a lane estimate mid-heat with one that puts the actual start-plus-finish bound above 10 ms. Capture inside the horizon and verify this result still needs explicit approval.

## Physical-device pilot

For the first T023 rehearsal, use the [PC measurement tools](pilot/README.md) and the predeclared scenarios in [T023 research notes](research.md#t023--physical-device-pilot-protocol).

### Local HTTPS and one-computer rehearsal

In **Zeitnahme**, choose **Lokales HTTPS starten**. Enter the interface IP, port and a PKCS#12 file with the server key and certificate chain. The certificate must cover the interface IP and each browser must trust its chain. Use `127.0.0.1` for a rehearsal on one PC and the desk's LAN IP for separate phones. The listener is off by default and closes with the timing window.

Choose **Handy koppeln** and issue one code for the start handset and one for each lane handset. Open the displayed HTTPS address in a separate tab per virtual phone, enter a device name and redeem its code. A code expires after two minutes and works once. Configure the desk's lane roles and horn duration, play the test tone before arming, then arm the heat. Each browser shows its own lanes. Only the capture roles synchronize clocks.

For the mixed rehearsal, use the start handset, a timed finish on lane 3, a browser stopwatch entry of `1:05,20` on lane 4 and a desk stopwatch entry of `1:10,01` on lane 5. Interrupt lane 3's first submission and reload its tab. Check that it retries the original payload and reaches **Angenommen**. When the acoustic onset is uncalibrated, confirm the heard start on the desk after the grant expires and explicitly approve the flagged finish in the review. Apply the results and read the three values back from the practice competition.

Configure the local HTTPS listener and a matching certificate trusted by both phone browsers. Verify pairing and result submission over HTTPS. Check the listener is unavailable before opt-in and that HTTP, invalid certificates, and expired session credentials cannot be used to pair or submit.

Before testing, record the allowed wait for Ready and horn timestamp error. Calibrate a shared external timebase and use reference markers observed by the desk and each phone near start and finish. Record marker capture latency and uncertainty for both endpoints. Compare mapped endpoint timestamps to the independently measured reference interval. Use the absolute elapsed-time error plus measurement uncertainty as the upper bound. A measurement that cannot establish the 10 ms bound counts as inconclusive and cannot pass the pilot. Record audible horn onset with a microphone on the same reference timebase and evaluate manual reaction separately. Predesignate at least 30 performance heats per platform in SC-007, including movement, reconnect, resume, and same-tab reload where success is expected. Count all designated heats, including timeouts, in the Ready-rate denominator. Run stale Start, late grant, lost start report, definite playback failure, and late audio after timely capture as a separate fault-recovery suite. Check the expected handling for each fault independently of performance percentages. For Start uncertain, confirm a witnessed horn with a valid capture and check that queued finishes stay attached and require approval. Discard a second attempt and verify late reports cannot reopen it. Try confirming without a valid start and verify Confirm is unavailable.

Record submission IDs before delivery. Disconnect after capture and before acknowledgement, including one case where the desk received the event but its response was lost. Reload the same tab, reconnect and reconcile every ID with the desk. Check pending status, original payload, deduplication, and visible rejection of a superseded attempt. Measure finish reaction separately. Record the browser decision and its evidence. Run `mvn verify` before completing implementation.
