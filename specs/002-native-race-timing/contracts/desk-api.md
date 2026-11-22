# Phase 1 — Android consumer contract

This is the Android subset of the existing [Spec 001 device contract](../../001-phone-race-timing/contracts/timing-session.md). Use the configured local HTTPS origin, verified certificate chain and hostname, UTF-8 JSON POST bodies, and an `Authorization: Bearer` header after pairing. Use one origin per credential. Disable plaintext and redirects. Native requests can omit `Origin`.

| Path | Request | Response and Android use |
|---|---|---|
| `/api/paaren` | `code`, `name` | `zugang` is the session bearer credential. Pairing codes are single-use. After an ambiguous timeout, surface the pairing problem. If the role is already bound, use a free role after operator reassignment or restart the desk listener when safe. |
| `/api/lauf` | Empty object | Standby if `versuch` is absent. Otherwise read attempt UUID `versuch`, heat UUID `lauf`, `disziplin`, `zustand`, `bereit`, `abgleich`, and assigned `bahnen`. Lane fields are `nummer`, `teilnehmer` and `telefonzeit`. |
| `/api/uhr` | `versuch`, `epoche` | `probe`, `schreibtischEpoche`, `schreibtischEmpfangen`, `schreibtischGesendet`. Record the handset's send and receive timestamps on the same monotonic clock. |
| `/api/probe` | `probe`, `epoche`, `telefonGesendet`, `telefonEmpfangen` | Estimate fields `id`, `versatz`, `unsicherheit`, `bezugszeit`, `driftPpm`, `auffrischungMs`. An active attempt and eligible timed lane are required. Probes are single-use and expire after five seconds. |
| `/api/ziel` | `versuch`, `ereignis`, `bahn`, `zeit`, `epoche`, `schaetzung` | `angenommen: true` acknowledges that finish for desk review. Send only for a timestamp lane. |
| `/api/stoppuhr` | `versuch`, `ereignis`, `bahn`, `dauer` | Same acknowledgement for an assigned stopwatch lane. The duration is a positive integer in microseconds. |

The desk's `bereit` flag concerns readiness before start. After the desk horn, `LAEUFT` opens the lane controls. The app never requests a phone-start grant or sends a start outcome. Timed lane controls also require a matching estimate. The UI displays errors in German, including invalid stopwatch input, absent sync and rejected delivery.

Retry the same payload and credential scope on transport loss, timeout or transient 5xx. A 401 pauses the pairing and leaves unresolved data visible. Authorization, stale attempt, conflicting event and invalid data responses are definitive for the submitted event and must be surfaced. An exact retry admitted before Apply can still receive the original acknowledgement after Apply while the same desk session is active. A first-time arrival for an Applied attempt is rejected.
