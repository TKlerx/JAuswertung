# Feature Specification: Standalone race timing

**Created**: 2026-11-23

**Status**: Pilot

## User Scenarios & Testing

### User Story 1 — Time a heat from a separate desk app (P1)

The desk operator enters the official heat and event numbers and sets up the occupied lanes. The operator assigns each lane to one phone for timestamped finishes or stopwatch entry, or keeps it at the desk for stopwatch entry. One phone can handle several lanes.

**Independent test**: Pair an Android client with the standalone desk, arm a heat with two timed lanes and one stopwatch lane, and trigger the desk horn. Capture two finishes and submit a manual time. Verify correct lane assignment and that re-delivery of an already accepted event does not create a second result.

### User Story 2 — Import reviewed times using stock JAuswertung (P1)

The desk reviews the recorded times and exports a results file. The operator selects the corresponding heat in JAuswertung’s existing electronic timing screen, compares the imported lanes, and applies the results there.

**Independent test**: Open a synthetic heat in unmodified JAuswertung 18.1.1. Import a file with distinct lane times, including a manually entered time. Verify the selected heat, event and occupied lanes, and the recorded times after applying. An unoccupied lane must stay empty.

### Edge cases

- Refuse out-of-range heat, event and lane numbers, duplicate lanes and invalid times. A failed export leaves the heat available for retry.
- A result from a different attempt or an unassigned client cannot populate a lane. The desk blocks export until every configured occupied lane has a valid reviewed time. ARES imports can interpret absent results as non-starts.
- Changes to the next heat or its lane assignments require explicitly ending the active heat. Clock readiness, restart retries, and timestamps retain the existing device contract.

## Requirements

- **FR-001** The timing desk MUST run as a separate app beside an unmodified JAuswertung 18.1.1 and allow explicit heat, event and lane identification.
- **FR-002** The desk MUST support pairing, exclusive lane assignment, several lanes per phone, timed finishes, and manual stopwatch times on the phone or desk.
- **FR-003** The desk MUST retain the 10 ms clock readiness limit, support the single horn and four-tone countdown, and allow the existing horn settings and test tone. Race time begins at the single horn onset or the final long tone onset.
- **FR-004** The desk MUST show a review before export, identify uncertain times, and require explicit selection of any uncertain result included.
- **FR-005** The desk MUST export ARES results for the identified heat and its actual lane numbers. Export requires a reviewed time for every occupied lane. The operator compares all occupied lanes before applying in the existing electronic timing screen.
- **FR-006** The Android client MUST work with the same local HTTPS contract and continue retrying the same event after a network interruption.
- **FR-007** The desk MUST retain previously reported results if export is cancelled or fails, and close a heat to new submissions only after a successful export or an explicit operator action.

### Key Entities

- **Heat setup**: Official heat and event numbers, displayed discipline name, active lanes and their assignments.
- **Timing attempt**: One start, exclusive timed or stopwatch lanes and captured results.
- **Results file**: A snapshot of explicitly approved lane times for one heat.

## Success Criteria

- **SC-001** Stock JAuswertung 18.1.1 reads the synthetic export and applies the expected times to their intended lanes with hundredth-second precision.
- **SC-002** A phone-grade client can pair and submit timestamp and stopwatch results to the standalone desk using the existing HTTPS contract.
- **SC-003** A failed or cancelled export leaves all results available for another export.

## Assumptions

- The operator copies the official heat and numeric event identifiers to the timing desk and verifies the pairing in the electronic timing screen. The desk does not need a competition file.
- This pilot covers Windows desk start and existing Android lane clients. Physical WLAN and audible-start qualification follow the existing Spec 002 checks.
