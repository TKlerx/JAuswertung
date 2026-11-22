# Phase 0 — Research

## Native Android build

**Decision** — Use one Kotlin Android application module under `android-race-timing/`, with Jetpack Compose for the pairing and lane screens. Use built-in Kotlin in AGP 9.5.0, Gradle Wrapper 9.6.0 and JDK 17, with compile and target SDK 37. Use API 29 as a provisional pilot minimum and confirm the device inventory before distribution.

**Rationale** — The requested first increment has one platform. A separate Gradle build fits the Android SDK while keeping Java 25 Maven desktop builds independent. Target SDK 37 requires runtime local network permission for direct LAN connections. Provide actionable status on denial or revocation. [AGP compatibility](https://developer.android.com/build/releases/agp-9-5-0-release-notes#compatibility) · [Built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) · [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)

**Alternatives considered** — Kotlin Multiplatform can share the protocol and event logic when an iOS client is scheduled. The current first increment needs only the Android build.

## HTTPS and pairing

**Decision** — Enter the desk HTTPS origin and one-time code. Use standard Android HTTPS with hostname verification, no redirect following, and the platform trust store. Allow a pilot build to add a preconfigured private CA scoped to the pilot desk hosts. Store the bearer credential encrypted with an Android Keystore-backed key. Keep credentials and outbox files out of device transfer and backup.

**Rationale** — The existing listener and wire protocol cover the required client operations. A provisioned pilot CA supports a local test certificate with a matching SAN, with no changes to the desk trust policy. Use `ACCESS_LOCAL_NETWORK` on Android 17 and detect reachability by contacting the configured desk. A venue WLAN can reach the local desk without public internet. On dual-network devices, use the selected venue Wi-Fi network for the LAN connection where default routing would select cellular. [Android network security configuration](https://developer.android.com/privacy-and-security/security-config) · [Android Keystore](https://developer.android.com/privacy-and-security/keystore) · [Android backup rules](https://developer.android.com/identity/data/autobackup)

**Alternatives considered** — Separate cloud relaying or device discovery would introduce a deployment dependency. Manual address entry and the existing local service cover the pilot.

## Clock semantics

**Decision** — Read `SystemClock.elapsedRealtimeNanos()` at the capture callback and around the clock exchange, converting to integer microseconds. Use a fresh clock epoch per app process. Measure the exchange around sending and receiving the response headers. Refresh on resume and network-path changes and follow the desk refresh guidance, with a 200 ms short sampling interval during startup or when the desk is awaiting readiness.

**Rationale** — The monotonic source includes deep sleep and avoids device wall-clock changes. The plugin owns the 10 ms readiness bound and validates actual captures. Its current API requires an active attempt and currently carries the same conservative timestamp-resolution allowance for every client. The current five-second readiness observations are a baseline for comparison. [Android monotonic clocks](https://developer.android.com/reference/android/os/SystemClock) · [Spec 001 pilot](../001-phone-race-timing/research.md)

**Alternatives considered** — Desk receive timestamps absorb Wi-Fi delay into each result. Session-wide pre-arm sampling needs a desk contract change and belongs to a separate experiment.

## Durable delivery

**Decision** — Commit immutable payloads to a private Room outbox before acknowledging capture in the UI. Use a single foreground sender with bounded retry, triggered on capture, reconnect, app resume and manual retry. Keep the original envelope after reboot or process recreation. Scope replay to the original pairing and desk origin.

**Rationale** — A durable record survives process death and a lost acknowledgement. The existing server returns the original acknowledgement on exact replay. A foreground sender fits an official actively running the timing screen and resumes delivery when they reopen the app. [Android offline-first architecture](https://developer.android.com/topic/architecture/data-layer/offline-first)

**Alternatives considered** — In-memory queues lose captures on process death. WorkManager is a later option if unattended background delivery becomes a requirement.
