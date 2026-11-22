# Tasks — Android race timing client

**Input** [Spec](spec.md) · [Plan](plan.md) · [Research](research.md) · [Data model](data-model.md) · [Desk API](contracts/desk-api.md) · [Quickstart](quickstart.md)

Task paths are relative to the repository root. `[P]` marks work that can run alongside the other marked tasks in its group once their prerequisites are in place. Story IDs follow the order of the user scenarios in the spec.

## Phase 1 — Setup

- [X] T001 Create the single Android application module and commit its Gradle Wrapper in `android-race-timing/settings.gradle.kts`, `android-race-timing/app/build.gradle.kts` and `android-race-timing/gradle/wrapper/gradle-wrapper.properties` using Kotlin, Compose, Room, coroutines, JDK 17, AGP 9.5.0, Gradle 9.6.0, minSdk 29 and compileSdk/targetSdk 37.
- [X] T002 Configure Android internet and network-state access, API 37 local-network permission, HTTPS-only policy, and hostname-scoped test CA trust for the pilot in `android-race-timing/app/src/main/AndroidManifest.xml`, `android-race-timing/app/src/main/res/xml/network_security_config.xml` and `android-race-timing/app/src/debug/res/xml/network_security_config.xml`.
- [X] T003 Define backup and device-transfer exclusions for the private credential and Room outbox in `android-race-timing/app/src/main/res/xml/backup_rules.xml` and `android-race-timing/app/src/main/res/xml/data_extraction_rules.xml`. Connect both to `android-race-timing/app/src/main/AndroidManifest.xml` after T002.

## Phase 2 — Foundations

- [X] T004 [P] Implement trusted UTF-8 JSON POST transport, HTTPS origin validation, explicit timeouts and network selection in `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/net/DeskHttp.kt`. Use the configured desk origin for bearer authentication, keep credentials out of logs and URLs, disable redirects, and allow the venue Wi-Fi route with no public uplink.
- [X] T005 [P] Implement Keystore-backed pairing storage in `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/storage/PairingStore.kt` with “canonical HTTPS origin, display name, local pairing generation UUID and encrypted bearer credential.” Keep “one active pairing on the device” and enforce “A generation only changes on a new successful pairing.”
- [X] T006 [P] Add the Room outbox entity, DAO and database in `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/storage/TimingOutbox.kt`. Enforce “UUID assigned at capture, pairing generation, canonical desk origin, attempt UUID, and positive lane number” and “Enforce one local event for each pairing generation, attempt and lane.” Store the immutable typed payload with delivery status, retry information and reason. Enforce “Record the event in a single transaction before presenting Pending.”
- [X] T007 Implement one foreground sender for both event types in `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/delivery/OutboxSender.kt` using T004–T006. Route from the stored type, enforce “Every event belongs to its original generation and origin,” and distinguish “HTTP 200 with `angenommen: true`” from retryable transport or 5xx errors and definitive rejections. On 401 pause delivery for that generation, surface expiry and preserve unresolved events. Serialize sends and persist every delivery outcome.
- [X] T008 Prepare the live-test desktop build and record evidence in `specs/002-native-race-timing/quickstart.md`. Resolve the desktop build dependency tracked in Spec 001 T001 or obtain a reproducible matching build. Record the exact source revision, including any unpublished changes needed to reproduce it, and the build and launch commands. Launch JAuswertung with `jauswertung-files/src/main/resources/plugins/de.dm.auswertung.zeitnahme.plugin` and the matching Core changes. Verify the Zeitnahme menu, trusted local HTTPS startup and successful pairing. Keep T008 open while `filechooser-platform` or another blocker prevents this proof. Require T008 for all live desk checks, starting with US1.

## Phase 3 — US1 Join a heat (P1)

**Goal** — Pair an Android device and show its assigned heat and lanes.

**Independent check** — After T008, pair with a valid code against the HTTPS desk. Verify standby before arming, assigned lanes and modes after arming, and desk readiness or running status. A consumed code and invalid TLS produce actionable errors.

- [X] T009 [US1] Write contract tests for `/api/paaren` and `/api/lauf` in `android-race-timing/app/src/test/java/de/df/jauswertung/zeitnahme/android/net/PairingHeatContractTest.kt`, covering exact request fields, bearer scoping, standby without `versuch`, mode decoding, consumed code and an ambiguous pairing response.
- [X] T010 [US1] Implement pairing and heat polling in `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/net/DeskSession.kt` against T009. Bind successful pairing to T005, and surface ambiguous or consumed codes without blind replay. Enforce “The attempt UUID identifies the heat for submissions” and “Lane numbers are unique within an attempt.” Keep the last observed heat in memory and clear it on a change of pairing.
- [X] T011 [US1] Wire the activity, pairing and heat screens and ViewModel in `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/MainActivity.kt`, `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/ui/TimingViewModel.kt` and `android-race-timing/app/src/main/res/values/strings.xml`. Show only assigned lanes, their modes and the desk's current state. Show LAN permission denial, pairing expiry and TLS failures in German.

## Phase 4 — US2 Record finishes on several lanes (P2)

**Goal** — Use the desk's horn or countdown and capture independent finishes on the assigned timestamp lanes.

**Independent check** — Give one phone two timestamp lanes. During the short countdown tones the controls stay disabled. After the desk reports the race running, finishing one lane leaves the other available. The desk receives the two original timestamps for their lanes.

- [X] T012 [P] [US2] Write `/api/uhr` and `/api/probe` contract and timing tests in `android-race-timing/app/src/test/java/de/df/jauswertung/zeitnahme/android/net/ClockContractTest.kt`. Include a five-second probe expiry, epoch and attempt mismatch, and a computed bound exactly at 10,000 µs versus one microsecond over it.
- [X] T013 [P] [US2] Write the `/api/ziel` consumer contract and lane capture tests in `android-race-timing/app/src/test/java/de/df/jauswertung/zeitnahme/android/net/FinishContractTest.kt`. Cover original attempt, event, lane, capture time, epoch and estimate in the payload, acknowledgement, incorrect mode and duplicate taps for one lane while another lane stays eligible.
- [X] T014 [US2] Implement the process clock and sampler in `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/clock/ClockSync.kt` after T012. Generate a fresh clock epoch UUID once per app process. Use `SystemClock.elapsedRealtimeNanos()`, “Durations and timestamps use integer microseconds on the wire,” and “one outstanding exchange per device.” Enforce “Scope estimates to the pairing, attempt and clock epoch,” keep the desk epoch, use the 200 ms acquisition interval and desk refresh hint, and trigger refresh on resume or a changed path. Compute mapped capture time and its drift bound using checked arithmetic.
- [X] T015 [US2] Implement finish capture and `/api/ziel` payload creation in `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/capture/FinishCapture.kt` using T006, T007 and T013–T014. Enforce “Enable new entries after observing `LAEUFT` for the current attempt” and “Disable new timed captures when no matching estimate qualifies within 10,000 µs.” Store “original monotonic capture time, process epoch and estimate UUID.” Enforce “Capture the timestamp before storage or network work” and “Record only the tapped lane.”
- [X] T016 [US2] Connect the sampler and per-lane Finish controls in `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/ui/TimingViewModel.kt` and `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/ui/LaneScreen.kt`. Show missing or expired sync, gate controls on observed desk state, show each lane's independent outcome, and leave start authority with Swing.

## Phase 5 — US3 Enter manual stopwatch times (P3)

**Goal** — Enter an elapsed stopwatch duration for each assigned manual lane.

**Independent check** — With a running attempt and an expired or absent clock estimate, send `1:05,20` for an assigned stopwatch lane. The desk reviews 65.20 s for that lane. Zero, negative or malformed values yield a field error and no event.

- [ ] T017 [US3] Write `/api/stoppuhr` consumer contract and input tests in `android-race-timing/app/src/test/java/de/df/jauswertung/zeitnahme/android/net/StopwatchContractTest.kt` for the expected integer-microsecond payload, malformed and overflowing inputs, wrong lane or mode, and capture with absent or expired sync.
- [ ] T018 [US3] Implement manual duration parsing and event creation in `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/capture/StopwatchCapture.kt` after T017. Enforce “positive elapsed duration, parsed with integer arithmetic and checked for overflow” and “Manual stopwatch entry does not need an estimate.” Verify the active attempt and assigned mode and submit through the existing outbox and sender.
- [ ] T019 [US3] Add the manual-entry control with German inline validation and per-lane acknowledgement in `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/ui/LaneScreen.kt` and `android-race-timing/app/src/main/res/values/strings.xml`.

## Phase 6 — US4 Deliver after disconnection (P4)

**Goal** — Capture eligible times during a temporary outage and send each original event when delivery is possible.

**Independent check** — Interrupt an observed running attempt, capture a valid finish and kill the app after persistence. Restore the connection and reopen it. The desk accepts the original event once. An expired session or first-time submission after Apply stays visibly blocked or rejected.

- [ ] T020 [US4] Add Android persistence and retry tests in `android-race-timing/app/src/androidTest/java/de/df/jauswertung/zeitnahme/android/OutboxRecoveryTest.kt` and orchestrate the two restart phases from the host in `android-race-timing/tools/outbox-restart.ps1`. Cover transaction failure before Pending, process recreation, unchanged payload and pairing, exact retry after Apply, first-time closed rejection, 401 blocking and visibility of unresolved events. Use a test-only transport seam that observes the desk acceptance and withholds it from the sender. Before ending the first phase, save the acceptance evidence and original event ID and payload on the host, excluding credentials. End the first instrumentation invocation, stop the app process from the host and start the second invocation in the same desk session. Hold replay until the second phase has verified the restored Pending record. Assert that replay sends the same ID and payload, including the old epoch and estimate, and that the desk has exactly one accepted result for that lane. On another assigned timestamp lane, verify a new process epoch and keep capture disabled until a qualifying estimate for it arrives, even after the heat is shown as running. Run the live variant after T008.
- [ ] T021 [US4] Wire bounded foreground replay on capture, reconnect, resume and explicit Retry after T020 in `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/delivery/OutboxSender.kt` and `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/delivery/DeliveryLifecycle.kt`. Enforce “Replay the same immutable fields after network loss, process recreation, epoch change or a later attempt” and “Pairing again never rewrites the event's scope.” Restore pending records independently of heat polling and retain the first accepted desk result after a lost acknowledgement.
- [ ] T022 [US4] Expose connection state, pending records and accepted, rejected or blocked outcomes in `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/ui/TimingViewModel.kt` and `android-race-timing/app/src/main/java/de/df/jauswertung/zeitnahme/android/ui/LaneScreen.kt`. Enforce “Show a disconnected indicator when using the last observed attempt,” “Under outage, use that snapshot while locally eligible,” and “After app recreation, load the current heat from the desk before enabling new entries.”

## Phase 7 — Integration and cross-cutting verification

- [ ] T023 Run the Android unit, instrumentation and install checks in `specs/002-native-race-timing/quickstart.md`, including plaintext, certificate chain, hostname, local-network permission and backup exclusions. Record actual results and environment blockers in that file.
- [ ] T024 After T008 and the completed Android stories, run the complete emulator-to-desk scenario in `specs/002-native-race-timing/quickstart.md` using the verified desktop build. Verify the desk countdown, two timed lanes, one manual lane, offline retry with original payload and one Apply to the intended competition lanes. Run the T020 lost-acknowledgement check against this desk. Run desktop `mvn verify` at branch verification and record any persistent Maven dependency blocker separately.
- [ ] T025 After T024, run the Android desk-start subset of physical qualification in `specs/002-native-race-timing/quickstart.md` and record it as partial Spec 001 evidence in `specs/001-phone-race-timing/research.md`. Use the independent reference, predeclared readiness and horn limits, at least 30 Android desk-start trials and a separate fault-recovery suite. Count readiness timeouts, check the 10 ms clock-error bound including reference uncertainty, record audible desk horn onset and manual reaction separately, and exercise venue Wi-Fi without internet while mobile data remains enabled.

## Dependencies and execution order

| Work | Depends on |
|---|---|
| Setup T001 to T003 | T001 starts the Android module. T003 extends the manifest configured in T002. |
| Foundation T004 to T007 | Setup. T004, T005 and T006 can run in parallel against the design documents. T007 joins them. |
| Desktop T008 | Independent of Android setup and local tests. Blocks US1 live pairing and every later live desk check. |
| US1 T009 to T011 | Foundation for implementation and local tests, plus T008 for the live pairing check. Pair and read the heat before exercising any assigned lane. |
| US2 T012 to T016 | US1. Write the clock and finish contract tests in parallel. Implement the sampler before finish capture and screen integration. |
| US3 T017 to T019 | US1 for a paired running view and the existing sender. Parsing and tests can be developed alongside US2 clock work. Merge shared screen changes after T016. |
| US4 T020 to T022 | Both capture paths for end-to-end coverage. Replay uses the same durable event model and sender. |
| Verification T023 to T025 | All stories. T023 can run its local Android checks independently of T008. T024 requires T008 and precedes the live physical qualification in T025. |

### Parallel examples

- **US1** — After agreeing the DTO fields in the desk contract, prepare T009 fixtures while sketching the separate Compose pairing view from T011. Integrate with the session after T010.
- **US2** — Write T012 and T013 together in separate test files.
- **US3** — Write T017 while T014 implements the clock sampler. Keep shared lane-screen changes ordered.
- **US4** — Prepare persistence fixtures in T020 while US3 integrates the manual-entry screen. Exercise replay of both capture types after T019 and T021.

## Implementation strategy

Use US1 as the first runnable checkpoint. Add US2 and US3 for a complete desk-started heat with mixed lane modes. **The MVP requested for this release includes US1 through US4**, including durable offline replay and the complete emulator-to-desk check. Physical qualification determines readiness for venue use under Spec 001.
