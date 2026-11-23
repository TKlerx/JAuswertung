# Research — Phone-based race timing

## Heat integration

**Decision** — Add a plugin in the bundled input module. Use the positive-time entry behavior of PHeatInputPlugin for classic and open-water heats, with a batch update event for the reviewed heat.

**Rationale** — PHeatInputPlugin uses the lane's discipline index in a classic heat and the heat's discipline ID in open water. The electronic bulk setters translate missing inputs to zero and can mark swimmers as Nicht angetreten. Omit missing lanes and preserve existing penalties when applying ordinary positive times. ElectronicHeats converts milliseconds to integer hundredths by truncation. Convert once at the result boundary and verify both heat paths. MElektronischeZeitPlugin already publishes a batch points-change event. MUndoPlugin treats ChangeTime as a single-participant event, so use a purpose-specific batch event with the existing generic undo behavior.

**Alternatives considered** — A complete electronic heat import would require special handling for missing lanes. Direct writes from device callbacks would couple network delivery to live competition mutations.

## Clock sync

**Decision** — Use the four NTP timestamps against the desk’s monotonic clock. Add half the processing-adjusted round trip, timestamp uncertainty, and elapsed drift allowance for each device. The sum of starter and lane bounds must fit within 10 ms per phone-timed lane. For all-stopwatch heats, only a phone starter needs a bound, and it must fit within 10 ms through the grant deadline. The desktop starter contributes zero clock-mapping uncertainty. [RFC 5905](https://www.rfc-editor.org/rfc/rfc5905.html) · [NIST delay-asymmetry study](https://tf.nist.gov/general/pdf/2776.pdf)

**Rationale** — Delays can change independently of clock drift. Reconnect or sustained deterioration triggers a fresh burst. An earlier estimate remains eligible while its bound covers the finish and the device clock epoch matches. A restart that changes the epoch invalidates the estimate. Retain the mapping used for each captured event so delivery and retries produce stable results.

**Alternatives considered** — System-clock sync depends on OS policy. Timestamping at desk receipt introduces delivery delay into the result.

## Mobile pilot

**Decision** — Start with a foreground browser client and short-lived local pairing over browser-validated HTTPS. Use a desk certificate trusted on both platforms and keep the timing listener local and opt-in. Keep the device contract suitable for native clients. Select the shipping client after measuring timestamp accuracy, suspend/reconnect behavior, and horn onset on representative devices.

**Rationale** — Browsers expose monotonic timestamps. They can throttle background work and vary timestamp resolution. The pilot measures these effects on the actual workflow. The 10 ms budget addresses clock error. Use common reference markers captured with calibrated latency or measured error bounds at both devices. Compare those markers with the mapped device timestamps to measure clock agreement independently of the desk estimates. Record actual horn onset on the reference timebase and measure manual reaction separately. [W3C High Resolution Time](https://www.w3.org/TR/hr-time-3/)

**Alternatives considered** — Native apps provide platform audio and lifecycle APIs. Build them if the browser pilot exposes a limitation that matters for the intended event use.

## T023 — PC measurement rehearsal

The display, browser clients, timing desk and reference ran on one Windows PC. The shared reference was Windows QueryPerformanceCounter (QPC), read by Python on either side of each measurement call. The desktop Java clock was aligned with QPC using 12 bracketed reads before and after each signal. Firefox clock markers used the same independent QPC reference. Each marker used the tightest of 12 brackets and its own active app offset. The browser result is a loopback check with independent observation windows on a single machine. Separate hardware clock drift and WLAN asymmetry need physical devices. [Windows audio packet timestamps](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudiocaptureclient-getbuffer) · [Python monotonic clocks](https://docs.python.org/3/library/time.html#time.perf_counter)

Desktop Java Sound signals were read from the default Windows output with WASAPI loopback at 48 kHz. The detector uses sustained 1 ms peaks and reports a window across two amplitude thresholds. A capture with a timestamp error or a discontinuity near onset is inconclusive. The Java-to-QPC alignment windows were 39–65 µs. These values describe the digital output stream at the measured endpoint. The speaker, air path and microphone were outside this measurement. [Windows WASAPI loopback](https://learn.microsoft.com/en-us/windows/win32/coreaudio/loopback-recording)

| Configured horn length | Digital signal onset after recorded desktop start |
|---|---|
| 200 ms | 92.2–95.2 ms |
| 400 ms | 92.5–95.5 ms, 100.2–103.2 ms, 89.0–92.0 ms |
| 1,500 ms | 90.3–93.3 ms |
| 400 ms, separate process | 104.7–107.7 ms |

Two end-to-end HTTPS rehearsals with three Firefox tabs measured browser interval errors of +0.963 ms and −1.073 ms, with reference uncertainties of ±3.323 ms and ±2.775 ms. The corresponding upper bounds were 4.286 ms and 3.847 ms. They apply to the measured start and finish clock markers. Both events completed with an interrupted finish submission, same-tab reload and correct result write-back. The audio-path delay and human finishing reaction are measured separately.

## T023 — Physical-device pilot protocol

Proposed venue limits for operator approval before the first scored heat are **5 s to Ready** from arming and **25 ms absolute error from the recorded start to the microphone's horn onset**, including the reference uncertainty. Keep the specified **10 ms** bound for clock-induced elapsed-time error. Use 0.4 s for the performance runs and exercise 0.2 s and 1.5 s in the separate setup checks. Fix the expected maximum heat duration and audio route for the performance set. Record both with the device models, OS and browser versions, AP and location.

Designate 30 performance heats per platform before testing. Every group below has three desktop-start heats and three phone-start heats. Put the platform under test on a timed lane for its desktop-start heats, and exercise its local horn in its phone-start heats. Include timed lanes on separate phones for the end-to-end runs. Keep all 30 heats in the Ready denominator. At least 29 must meet the declared wait. Every timed lane in every Ready heat must meet the clock bound. Each performance start must produce exactly one audible horn within the declared limit.

| Heat IDs per platform | Planned scenario |
|---|---|
| 01–06 | Stationary with a stable WLAN path |
| 07–12 | Officials move between the designated poolside positions |
| 13–18 | Reconnect before start while preserving the intended heat |
| 19–24 | Background or lock the device before start, then resume and re-establish readiness |
| 25–30 | Capture a finish, interrupt its acknowledgement and reload the same tab before delivery is confirmed |

Use an external reference recorder with a known sample rate and the horn microphone on that same timebase. At each end of the interval, record a shared stimulus that the desk or phone can timestamp in its own monotonic clock and that the reference recorder also sees. Measure the marker-capture latency and its uncertainty for each device path in calibration runs. Keep the microphone geometry fixed, record the horn threshold and include its uncertainty. For each lane compare the difference of mapped app timestamps with the external reference interval. Score using absolute error plus reference uncertainty. If the marker path cannot establish a bound, mark the run inconclusive. Record human reaction in separate trials against the reference stimulus.

The physical checks are the handset speaker and audio route, microphone onset versus the recorded timestamp, real-device clock drift and WLAN movement or asymmetry, HTTPS trust, touch capture, and browser recovery after backgrounding, lock, reconnect and reload. Run them on Android Chrome and iPhone Safari as distinct configurations. One phone of each OS can cover role-by-role compatibility with the PC filling other roles. The performance matrix needs separate capture devices active together to represent the venue workflow.

Run the deliberately injected failures separately, including stale Start, grant response after expiry, lost start report, definite playback failure, late audio evidence, uncertain-start confirmation and discard. Confirm that the test tone creates no start, a valid pending capture keeps its original payload through reconnect or reload, and an invalid or closed-attempt submission is visibly rejected. Reconcile every capture and acknowledgement with the desk. Leave the browser/native decision open until failures have been separated by browser, device, audio path and network.
