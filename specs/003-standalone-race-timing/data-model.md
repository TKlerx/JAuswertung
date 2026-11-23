# Data model

- **Heat setup** uses an event number in 1–999, a heat number in 1–999, a discipline label in 1–100 characters, an expected duration in 1–3,600 seconds and at least one occupied lane. Lane numbers are unique and in 1–10. Each occupied lane is a desk stopwatch lane or belongs to exactly one of eight phone assignments, with timestamp or stopwatch mode.
- **Timing attempt** uses the existing attempt and event IDs, microsecond timestamps and clock estimates. The desk keeps one active attempt. Phone permissions are frozen with heat settings at arming.
- **Reviewed result** identifies one configured lane, a positive time that survives hundredth-second rounding, a source and uncertainty. Every occupied lane must have a result. The operator explicitly confirms uncertain results before export.
