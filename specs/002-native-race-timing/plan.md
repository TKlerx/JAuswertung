# Implementation Plan — Android race timing client

**Feature** `002-native-race-timing`

**Current branch** `codex/001-phone-race-timing`

**Date** 2026-11-22

**Input** [Spec 002](spec.md)

## Summary

Build a Kotlin Android client for the existing JAuswertung timing plugin. The desk sounds the configured start signal. A paired Android device displays its assigned lanes, captures each finish independently or accepts a manual stopwatch duration, and reliably resends stored results. The desk reviews and applies results.

## Technical context

| Area | Decision |
|---|---|
| Platform and toolchain | Native Kotlin with Jetpack Compose. Android 10 / API 29 minimum for the pilot, compile and target API 37. AGP 9.5.0 with built-in Kotlin, Gradle Wrapper 9.6.0 and JDK 17 for Android. The desktop keeps its Java 25 Maven build. |
| Dependencies | One Android application module. Compose and ViewModel for the screen, Kotlin coroutines, Room, Android HTTPS and Keystore. |
| Transport | Configured desk address and certificate, JSON POST over trusted local HTTPS. The existing plugin owns pairing, assignments, clock estimates and Apply. |
| Live-test prerequisite | A reproducible Java 25 JAuswertung build from the timing feature source, including the plugin, its descriptor and the Core changes it calls. Record the source revision and build or launch commands. Verify the Zeitnahme menu, local HTTPS startup and a successful pairing before testing Android against the live desk. |
| Clock | Use Android monotonic microseconds for clock exchanges and finish timestamps. Refresh after resume or path change and keep the desk's 10 ms budget. Clock qualification starts with the armed attempt. |
| Storage | Room stores outgoing events and delivery outcome. A Keystore-backed local credential is scoped to one desk origin and pairing generation. |
| Verification | JVM tests for timing and payload logic, Android instrumentation for persistence and lifecycle, emulator integration with the actual desk and physical Android testing under Spec 001. |
| Scope | Desk-started heats with multiple lanes per device and mixed capture modes. One app screen after pairing, with independent lane controls and delivery status. Follow-up increments cover iOS and handset audio. |

## Constitution check

| Gate | Before research | After design |
|---|---|---|
| Desktop competition data and architecture | Pass. Reuse the local timing plugin. | Pass. The Android store carries submissions and statuses. The competition is changed only through existing desk Apply. |
| Boundary correctness | Pass. Validate app input and respect desk authority. | Pass. The app validates before committing immutable events. The desk enforces authority, clocks, conflicts and results through the existing API. |
| File compatibility and threading | Pass. Keep historical competition files and existing desktop ownership. | Pass. Android work runs outside Maven and Swing. Existing desktop model and EDT rules apply, and Android storage and I/O run away from the UI thread. |
| Verification | Pass. Define observable pilot outcomes. | Pass. The quickstart covers routing, replay, TLS, clock and review. A complete desktop Maven build currently hits the missing `org.lisasp.swing:filechooser-platform:1.0.0-SNAPSHOT`. Physical timing qualification remains pending. |

## Project structure

```text
specs/002-native-race-timing/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── contracts/desk-api.md
└── quickstart.md

android-race-timing/                     # planned Gradle project
├── gradlew / gradlew.bat
└── app/src/
    ├── main/
    ├── test/
    └── androidTest/

jauswertung-plugins-input/               # existing local HTTPS timing service
```

Keep a single native Android app module. Organize timing, transport, persistence and UI as packages so device-independent code remains unit-testable. The Gradle project reads the wire contract through tests and communicates with the Java desktop over HTTPS.

## Phase 0 — Research

[Research decisions](research.md) cover the toolchain, HTTPS provisioning, clock semantics and retry ownership.

## Phase 1 — Design and contracts

1. [Data model](data-model.md) defines identity, estimate lifetime and delivery states.
2. [Desk API](contracts/desk-api.md) records the consumer subset of the existing wire contract.
3. [Quickstart](quickstart.md) defines one complete desk-to-Android run, loss and recovery cases, and physical qualification.

## Implementation sequence

1. Prepare and verify the live desktop build in T008. In parallel, create the Android Gradle project and test pairing and heat decoding with local fixtures. Exercise Android pairing and assigned lanes against the desk after T008 passes.
2. Add clock sampling and timed or stopwatch capture using the foreground screen. Verify both timestamp lanes can submit independently after a desk start.
3. Persist immutable events and implement serialized retry and visible outcomes. For the lost acknowledgement test, verify desk acceptance, suppress delivery of the acknowledgement to the app and restart the process before allowing replay. Verify identical event ID and payload and one accepted result.
4. Verify the complete emulator flow against the verified desktop build. Then qualify the Android client with the desk as starter, using the applicable Spec 001 timing and fault-recovery criteria. Record this as partial evidence for the full Spec 001 pilot.
