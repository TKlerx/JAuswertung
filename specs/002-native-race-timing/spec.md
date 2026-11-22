# Feature Specification — Android race timing client

**Created** 2026-11-22

**Status** Android first increment

## User scenarios

1. **Join a heat.** An official enters the local desk address, a device name and a single-use pairing code. The Android app displays the current heat, assigned lanes and each lane's capture mode. It shows when the desk is waiting for clock sync or has started the race.
2. **Record finishes.** The desk starts the race with its configured horn or countdown. After the app has observed the running attempt, the official can tap Finish separately on two or more assigned lanes. Finishing one lane leaves the other lanes available.
3. **Enter a stopwatch time.** The official enters a positive elapsed time for an assigned stopwatch lane. An unavailable or expired clock estimate does not disable stopwatch entry.
4. **Deliver after disconnection.** The official can capture against the last known running attempt during a temporary outage while the relevant lane remains eligible. After reconnecting or reopening the app, a pending capture is retried for its original attempt and lane. A permanently rejected event remains visible with its reason.

## Requirements

- **FR-001** Provide an Android client for desk-started heats using the existing trusted local HTTPS device API. The desk owns start authority, assignments, readiness and result review.
- **FR-002** Use the platform's monotonic time source for clock samples and finish captures. Refresh estimates on reconnect or resume and after a clock epoch changes. The desk's 10 ms clock-error budget and review rules from Spec 001 apply.
- **FR-003** Durably retain each submission and its original event identity, attempt identity, lane and timing payload before showing it as pending. Retry the original payload for the same desk session until the desk acknowledges or permanently rejects it. Keep pending, accepted and rejected status visible.
- **FR-004** Offer independent finish controls for all assigned timestamp lanes and positive elapsed-time entry for assigned stopwatch lanes. Apply the desk's current assignment and capture-mode rules.
- **FR-005** Preserve the distinction between clock accuracy, audible horn onset and manual reaction time when validating the Android implementation.

## Success criteria

- **SC-001** On one Android client, pair with the existing desk, observe a desk-started heat, capture at least two assigned finish lanes and enter at least one assigned stopwatch lane. The operator reviews and applies the intended times once to the right lanes.
- **SC-002** A qualifying physical Android timing attempt meets Spec 001's 10 ms clock-error budget under documented conditions. Measure horn onset and manual finish reaction separately. Use Spec 001's venue pilot criteria for any readiness-rate claim.
- **SC-003** Disconnect and reconnect during a known running attempt and simulate a lost acknowledgement and app restart. Eligible captures retain the original payload, arrive once, and have a visible status. New captures after a clock epoch change require a fresh estimate. Manual entry remains available for its assigned lane.

## Scope and dependencies

Use the desktop plugin and [device contract](../001-phone-race-timing/contracts/timing-session.md) from Spec 001. Planning and prototyping proceed with the existing API. Venue readiness still depends on physical measurements and desktop verification. A native phone starter and an iOS client are later increments.
