# Data Model — Phone-based race timing

| Entity | Fields and invariants |
|---|---|
| Timing session | Opaque session and attempt IDs, competition and heat snapshot, discipline, selected starter, expected finish horizon, and state. Display heat numbers can repeat. |
| Lane assignment | Lane number, swimmer or team identity, phone-capture or stopwatch mode, and optional assigned phone. Each lane has at most one phone. A phone can own several lanes. Freeze assignments for the armed attempt. |
| Device | Session-scoped identity and credential, role and assignments, and clock epoch. |
| Clock estimate | Device and desk epochs, estimate ID, monotonic anchor, offset, adjusted round trip, uncertainty, and drift bound. |
| Start event | Attempt, event ID, starter, capture time and clock estimate ID for a phone starter. The desk uses its own clock directly. One accepted start per attempt. |
| Phone start grant | One-use ID, attempt, starter, clock epoch, accepted estimate, and desk deadline. Grant issuance reserves the attempt. The report separates the capture timestamp from the audio outcome. Outcome values are timely onset supported by evidence, definitely no playback, or uncertain. |
| Lane submission | Attempt, lane, device, event ID, and either a finish timestamp with estimate ID or a positive stopwatch duration. The first accepted phone value wins. Exact retries return the same outcome. |
| Reviewed result | Lane, optional elapsed time, source and review state. The operator may enter a manual value. Captured times inherit uncertainty from an operator-confirmed start. Record the sum of the actual start and finish clock bounds at their capture times. Exclude missing lanes on apply. |
| Pending submission | Original event, attempt, clock epoch and estimate ID where applicable, plus delivery state. Pending records survive reload in the same browser tab and desk session. |

An attempt moves through **Preparing**, **Ready**, **Armed**, **Running**, **Review**, and **Applied**. Preparing and Ready can alternate as clock estimates age. A timing timeout stays visible until the clock check recovers. A granted attempt remains reserved until a definite outcome arrives or the deadline expires. Expiry without a definite outcome enters **Start uncertain**. After expiry, operator confirmation can accept an existing valid capture and preserve its submissions. Discard closes the run without applying it. Reject first-time submissions for a cancelled, superseded, or Applied attempt. Successful Apply closes admission. Preserve original acknowledgements for authorized exact retries until the desk session ends.

Use integer microseconds within a named device or desk clock epoch. A captured timestamp is bound to the estimate used for that event. Once mapped, a later estimate cannot change the accepted result. If the latest plausible desk-clock time for a finish exceeds the covered horizon, or the sum of the original event bounds exceeds 10 ms, keep it flagged until the operator explicitly approves it for Apply. Equality is within either limit. A manual value is an elapsed duration and uses no clock estimate. Apply the existing competition precision at the boundary.
