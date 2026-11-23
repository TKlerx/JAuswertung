# T023 measurement tools

Run the desktop horn check on Windows with Python and Java 25. It plays the real JAuswertung desktop signal through the default audio output. It captures render-loopback packets and prints timing windows. The tool never opens a microphone or writes audio recordings. Run it with other audio sources quiet and leave the default audio device unchanged.

```powershell
python -B specs/001-phone-race-timing/pilot/pc_measurement.py --jdk-bin 'C:/path/to/jdk-25/bin' --duration 200 400 400 400 1500
python -B specs/001-phone-race-timing/pilot/test_measurement.py
```

The PC browser check takes two synchronised timing pages from the HTTPS rehearsal. In the Playwright driver, call `browser_marker(start_page)` shortly before the start and `browser_marker(lane_page)` shortly before the virtual finish. Each call returns `(reference_before, mapped_desk_timestamp, reference_after)`, in microseconds. Write the difference of the two mapped timestamps as `mapped_elapsed_us` and the two QPC observation pairs as `start_reference` and `finish_reference` in a JSON file. The measurement uses two software markers rather than the human click times. A different environment can provide reference intervals from its calibrated marker system in the same format.

```powershell
python -B specs/001-phone-race-timing/pilot/pc_measurement.py --clock-json path/to/markers.json
```

The strict upper bound is `abs(error_us) + reference_uncertainty_us`. If the reference cannot distinguish crossing the 10 ms limit, the tool reports `inconclusive`.
