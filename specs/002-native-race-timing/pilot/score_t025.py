import argparse
import csv
import sys
from pathlib import Path


def check_error(row, channel, limit, failures, pending):
    error = row[f"{channel}_error_us"]
    uncertainty = row[f"{channel}_uncertainty_us"]
    if not error or not uncertainty:
        pending.append(channel)
        return
    distance, uncertainty = abs(int(error)), int(uncertainty)
    if uncertainty < 0:
        raise ValueError(f"{channel}: uncertainty cannot be negative")
    if distance - uncertainty > limit:
        failures.append(channel)
    elif distance + uncertainty > limit:
        pending.append(f"{channel} inconclusive")


def score(path, wait_ms, horn_us):
    with path.open(encoding="utf-8-sig", newline="") as source:
        rows = list(csv.DictReader(source))
    groups = ("stationary", "moving", "reconnect", "resume", "pending_reopen")
    expected = {f"{i + 1:02}": groups[i // 6] for i in range(30)}
    if any(None in row or None in row.values() for row in rows):
        raise ValueError("Each CSV row must have the same columns as the header.")
    if len(rows) != 30 or {row["trial_id"]: row["scenario"] for row in rows} != expected:
        raise ValueError("Use all 30 registered trial IDs with their original scenarios.")
    attempts = [row["attempt_id"].strip() for row in rows if row["attempt_id"].strip()]
    if len(set(attempts)) != len(attempts):
        raise ValueError("Each trial must use its own attempt ID.")

    ready = unknown_readiness = failed = pending_count = 0
    for row in rows:
        failures, pending = [], []
        time = row["ready_ms"].strip()
        if not row["attempt_id"].strip():
            pending.append("attempt ID")
        if not time:
            pending.append("Ready time")
            unknown_readiness += 1
        elif time != "timeout":
            elapsed = int(time)
            if elapsed < 0:
                raise ValueError("Ready time cannot be negative.")
            ready += elapsed <= wait_ms
            if not row["horn_count"]:
                pending.append("horn count")
            elif int(row["horn_count"]) != 1:
                failures.append("horn count")
            check_error(row, "horn", horn_us, failures, pending)
            for lane in ("lane3", "lane5"):
                check_error(row, lane, 10_000, failures, pending)
            reconciliation = row["reconciled"].strip().lower()
            if reconciliation == "no":
                failures.append("reconciliation")
            elif reconciliation != "yes":
                pending.append("reconciliation")
        elif any(row[column] for column in (
            "horn_count", "horn_error_us", "horn_uncertainty_us", "lane3_error_us",
            "lane3_uncertainty_us", "lane5_error_us", "lane5_uncertainty_us", "reconciled",
        )):
            raise ValueError(f"Trial {row['trial_id']}: a timed-out attempt must remain unstarted.")
        if failures or pending:
            print(f"{row['trial_id']}: failures={', '.join(failures) or '-'}; "
                  f"pending={', '.join(pending) or '-'}")
        failed += bool(failures)
        pending_count += bool(pending)

    if failed or ready + unknown_readiness < 29:
        status = "FAIL"
    else:
        status = "OPEN" if pending_count else "PASS"
    print(f"Performance: {status} | Ready within {wait_ms} ms: {ready}/30 | "
          f"Failed trials: {failed} | Open trials: {pending_count}")
    return {"PASS": 0, "FAIL": 1, "OPEN": 2}[status]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Score the 30 registered T025 performance trials.")
    parser.add_argument("csv", type=Path)
    parser.add_argument("--wait-ms", type=int, default=5000)
    parser.add_argument("--horn-us", type=int, default=25000)
    args = parser.parse_args()
    if args.wait_ms <= 0 or args.horn_us <= 0:
        parser.error("Ready and horn limits must be positive.")
    try:
        sys.exit(score(args.csv, args.wait_ms, args.horn_us))
    except (KeyError, ValueError) as error:
        parser.error(str(error))
