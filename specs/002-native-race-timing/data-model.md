# Phase 1 — Android data model

## Desk pairing

- **Fields** — canonical HTTPS origin, display name, local pairing generation UUID and encrypted bearer credential.
- **Rules** — one active pairing on the device. A generation only changes on a new successful pairing. Every event belongs to its original generation and origin. On HTTP 401, stop delivery for that generation and show session expiry. Keep unresolved events visible. A new pairing authorizes new events for that session.

## Active heat and lanes

- **Fields** — attempt UUID, display heat number, discipline, state, desk readiness, sync requirement, and assigned lane records with lane number, participant label and capture mode.
- **Rules** — the app keeps the last observed heat in memory. The attempt UUID identifies the heat for submissions. Lane numbers are unique within an attempt. Show a disconnected indicator when using the last observed attempt. Enable new entries after observing `LAEUFT` for the current attempt. Under outage, use that snapshot while locally eligible. After app recreation, load the current heat from the desk before enabling new entries. Persisted submissions can be retried independently of the current heat.

## Clock estimate

- **Fields** — pairing generation, attempt UUID, desk epoch, process clock epoch, estimate UUID, desk reference time, offset, uncertainty, drift in ppm and suggested refresh interval. Durations and timestamps use integer microseconds on the wire.
- **Rules** — one outstanding exchange per device. Scope estimates to the pairing, attempt and clock epoch. Schedule refresh and collect new samples on resume or a changed network path. For a new finish, compute the upper error bound at the mapped desk capture time using uncertainty plus the drift allowance from the reference time. Disable new timed captures when no matching estimate qualifies within 10,000 µs. The desk applies the full readiness horizon and evaluates the bound of each actual capture. The suggested refresh interval schedules the next probe. Manual stopwatch entry does not need an estimate.

## Outgoing event

- **Identity** — UUID assigned at capture, pairing generation, canonical desk origin, attempt UUID, and positive lane number. Enforce one local event for each pairing generation, attempt and lane.
- **Timed finish payload** — original monotonic capture time, process epoch and estimate UUID.
- **Stopwatch payload** — positive elapsed duration, parsed with integer arithmetic and checked for overflow.
- **Delivery fields** — status, retry information, and a reason for definitive rejection or a blocked pairing.

Record the event in a single transaction before presenting Pending. Capture the timestamp before storage or network work. Record only the tapped lane. Replay the same immutable fields after network loss, process recreation, epoch change or a later attempt. Keep events and credentials private to the app and exclude them from backup and device transfer.

| State | Transition |
|---|---|
| Pending | A committed event awaits acknowledgement. Transport loss, timeout or retryable server failure leaves it pending. |
| Accepted | HTTP 200 with `angenommen: true` marks the exact event accepted. This confirms receipt for desk review. |
| Rejected | A definitive request error closes delivery for the event and preserves the reason for the official. |
| Delivery blocked | Session expiry or configuration failure pauses sending and preserves the pending event with an explanation. Pairing again never rewrites the event's scope. |

The desk competition remains the source of truth. Only the operator's existing review and Apply step writes competition times.
