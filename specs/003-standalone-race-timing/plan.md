# Implementation Plan: Standalone race timing

**Branch**: `codex/001-phone-race-timing` | **Date**: 2026-11-23 | [Spec](spec.md)

## Summary

Build a separate desk application. Reuse the pilot timing and local HTTPS code, and pass approved results through the stock ARES file importer.

## Technical Context

- Java 21, Swing, Java Sound and the JDK HTTPS server. Use the existing Jackson version for JSON.
- Independently packaged Maven project in `race-timing-desk/`. Provide a runnable JAR and a German resource bundle.
- One active heat per desk. The heat setup explicitly maps lanes to one of eight reusable phone assignments or desk stopwatch entry.
- File handoff targets the released 18.1.1 ARES reader. User reviews and applies results in JAuswertung.
- Verify with Maven, a synthetic HTTPS heat, and the official release's reader and apply path.

## Constitution Check

The implementation lives outside the JAuswertung Maven reactor and communicates via the release's existing import path. It validates lane identity and durations before file creation. The desk reads and writes its own attempt, never a competition file or the in-memory model. Swing actions stay on the EDT. TLS startup, audio preparation and ARES file I/O use background work.

## Source Layout

```text
race-timing-desk/
  pom.xml
  src/main/java/de/df/jauswertung/zeitnahme/
  src/main/resources/
  src/test/java/de/df/jauswertung/zeitnahme/
```

## Design

1. Reuse the timing state machine, event model, clock sync, audio, stopwatch parser, HTTPS service and browser client from the unpublished pilot. Keep the standalone build self-contained. The Android client retains the same endpoints and payloads.
2. Replace competition access and live Apply with explicit heat setup, a read-only result review and ARES export. Freeze settings while an attempt is active. Show the real lane numbers and sources. Keep results available for repeat export until a new heat is selected.
3. Validate against the released reader, including sparse lane numbers, the zero-based ARES heat field, hundredths precision and refusal of incomplete attempts. After a successful export, close the attempt to new events while retaining exact retry acknowledgements.
