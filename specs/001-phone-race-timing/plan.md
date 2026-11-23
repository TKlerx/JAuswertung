# Implementation Plan — Phone-based race timing

**Branch** codex/001-phone-race-timing | **Date** 2026-11-22 | **Spec** [Phone-based race timing](spec.md)

## Summary

Build a single-computer simulator using the same timing rules the desktop plugin will use. Then connect virtual phones to a real JAuswertung heat. The final pilot runs on Android and iOS and supports both start methods, phone-captured finishes, and stopwatch entry.

## Technical Context

| Aspect | Choice |
|---|---|
| Language and dependencies | Java 25, Swing, existing jutils plugin framework, and JUnit. A small browser client is the first Android and iOS pilot. |
| Storage | Live timing sessions in memory. Confirmed times use the existing competition inputs and save workflow. |
| Platform | Windows desk and Android or iOS officials on a local network. The first milestone runs on one computer. |
| Timing goal | All phone-timed lanes within a ±10 ms clock-error bound through the expected finish. Report simulated readiness, timeouts, and observed timing error. |
| Scope | One active heat and starter. Each lane has at most one phone. One phone can cover multiple lanes. |

## Constitution Check

Keep timing rules testable without Swing. The competition owns heat assignments and applied results. Network callbacks work with session data and immutable heat snapshots. Swing updates and reviewed result writes run on the EDT. Introduce the shared CorePlugin competition access guard with the timing panel, before it reads live heat data. Require conflicting operations to reserve it before starting a worker and hold it through final model access, including EDT completion. Acquire without waiting on the EDT for snapshot creation, refresh, arming and start validation, and reviewed Apply. A busy snapshot stays visible as stale and cannot be armed. Apply holds the guard through revalidation and the complete reviewed write. Release it in all completion and failure paths. Existing serialization supports the applied times. The validation guide records the Maven dependency blocker.

## Project Structure

- **jauswertung-plugins-input** houses the timing session, simulator, desktop panel, local listener, and tests. Keep the clock and session logic independent of Swing and the network transport.
- **jauswertung-plugins-core** owns the shared guard. Reuse an existing exclusion mechanism if it covers worker and EDT paths. Audit competing imports, edits and background readers of the live model before enabling reviewed Apply.
- **jauswertung-files/src/main/resources/plugins/** registers the panel and its required plugin dependencies.
- This feature directory holds [research](research.md), the [data model](data-model.md), the [device contract](contracts/timing-session.md), and the [validation quickstart](quickstart.md).

## Delivery Sequence

1. **Simulator.** Register an independent simulator menu entry before wiring the heat timing plugin. It opens without a competition and runs on one computer. Add virtual-clock drift, changing Wi-Fi paths, reconnects, configurable sampling and timeout, and both starter modes. Use an explicit expected maximum duration to compute the finish horizon. Schedule refresh according to FR-003 and FR-009. Cover all-stopwatch readiness for both starter modes, with clock checks only for a phone starter through the proposed grant deadline. Run seeded batches through the readiness logic and report timeouts, ready-time percentiles among ready heats, and observed lane error.
2. **JAuswertung integration.** Drive a real heat with virtual devices. Exercise delayed and duplicate start and finish delivery with US2, then mobile and desk stopwatch entry and reviewed Apply with US3. Resolve classic and open-water lane mappings using the existing heat input behavior. Revalidate the active competition, discipline, and lane assignments on the EDT before writing. Flag finishes outside the covered horizon or whose actual start and finish mappings exceed the combined 10 ms budget. Require explicit review approval for those values, confirm overwrites, and publish one batch points-change event. Successful Apply closes the attempt to new results. Keep acknowledgements for exact authorized retries until the desk session ends. Verify apply, undo, and redo.
3. **Physical-device pilot.** Connect paired Android and iOS browser clients using HTTPS with a desk certificate trusted by both clients. Keep the listener local and opt-in, with no plaintext fallback. Timestamp-capture phones refresh sync in the background. Stopwatch-only phones need no sync. Phone Start requires a fresh grant with a one-second maximum lifetime for the pilot. Reserve the attempt and check readiness through grant expiry plus the expected maximum heat duration for phone-timed lanes. With stopwatch-only lanes, check the phone starter through grant expiry alone. Report capture time and audio outcome separately. After an uncertain start, let the operator confirm the valid existing capture or discard the attempt once the grant has expired. Retain confirmed submissions in a same-tab retry queue across reload and reconnect. Calibrate the audio allowance against audible onset. Use an independent common-reference clock measurement with a documented uncertainty bound. Predeclare the Ready wait and horn error limits and the performance heats for each platform. Score intentionally injected failures separately, and reconcile every submission in SC-007 before deciding whether to plan native apps.
