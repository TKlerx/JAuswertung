# Tasks: Standalone race timing

## Setup
- [X] T001 Confirm ARES lane, event, heat and time semantics against release 18.1.1.
- [X] T002 Create the independent package at `race-timing-desk/pom.xml` and port the existing timing and HTTPS engine.

## Timing and export
- [X] T003 [US2] Add round-trip, rounding, sparse-lane and failure tests in `race-timing-desk/src/test/`. Implement complete-heat ARES export.
- [X] T004 [US1] Add the desk launcher and heat setup, with event and heat 1–999, discipline 1–100 characters, duration 1–3,600 seconds and distinct occupied lanes 1–10 with one assignment each.
- [X] T005 [US1] Wire trusted local HTTPS, per-phone pairing, horn settings, test tone and the desk stopwatch into the standalone window.
- [X] T006 [US2] Add results review and ARES export, with explicit uncertainty confirmation and retryable write errors.

## Verification
- [X] T007 Verify the packaged app, synthetic mixed HTTPS heat, lane isolation and event retries.
- [X] T008 Verify the synthetic file using stock JAuswertung 18.1.1 and record the operator workflow.
