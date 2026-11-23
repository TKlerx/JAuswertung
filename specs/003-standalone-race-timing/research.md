# Research

## Use the release's ARES file input

JAuswertung 18.1.1 ships an electronic timing source that calls `de.dm.ares.file.FileReader.readHeats`. It expects the ARES semicolon header, a zero-based heat field and one-based physical lane numbers. The stock apply strategy treats absent times for populated lanes as non-starts. Export therefore requires a reviewed result for every configured occupied lane and the operator verifies that the configured lane set matches the official heat before applying.

## Reuse the current HTTPS protocol

The Android client communicates over the existing local HTTPS interface. The timing service, state machine, clock sync and audio code use JDK facilities and Jackson. The direct plugin's competition access and Swing integration are separate from that logic. The standalone Maven build avoids the desktop reactor's `filechooser-platform` dependency.
