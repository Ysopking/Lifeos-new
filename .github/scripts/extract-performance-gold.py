#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import statistics
import math
import tempfile
from pathlib import Path

COLD_TOTAL = re.compile(r"^TotalTime:\s*(\d+)\s*$", re.MULTILINE)
COLD_WAIT = re.compile(r"^WaitTime:\s*(\d+)\s*$", re.MULTILINE)
INSTRUMENT_TIME = re.compile(r"^Time:\s*([0-9]+(?:\.[0-9]+)?)\s*$", re.MULTILINE)


class PerformanceEvidenceError(RuntimeError):
    pass


def read_required(path: Path) -> str:
    if not path.is_file():
        raise PerformanceEvidenceError(f"missing:{path}")
    return path.read_text(encoding="utf-8", errors="replace")


def parse_single(pattern: re.Pattern[str], text: str, label: str) -> int:
    matches = pattern.findall(text)
    if len(matches) != 1:
        raise PerformanceEvidenceError(f"{label}-count:{len(matches)}")
    value = int(matches[0])
    if value < 0:
        raise PerformanceEvidenceError(f"{label}-negative")
    return value


def instrumentation_samples(root: Path) -> list[dict]:
    samples = []
    for path in sorted(root.glob("*.txt")):
        if path.name in {
            "cold-start.txt",
            "cold-start-process.txt",
            "cold-start-activities.txt",
            "cold-start-overlay.txt",
        }:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        matches = INSTRUMENT_TIME.findall(text)
        if not matches:
            continue
        if len(matches) != 1:
            raise PerformanceEvidenceError(f"instrument-time-count:{path.name}:{len(matches)}")
        milliseconds = int(round(float(matches[0]) * 1000.0))
        if milliseconds < 0:
            raise PerformanceEvidenceError(f"instrument-time-negative:{path.name}")
        samples.append({"file": path.name, "elapsed_ms": milliseconds})
    if not samples:
        raise PerformanceEvidenceError("instrumentation-timing-missing")
    return samples


def build(root: Path) -> dict:
    cold = read_required(root / "cold-start.txt")
    total_ms = parse_single(COLD_TOTAL, cold, "cold-total-time")
    wait_ms = parse_single(COLD_WAIT, cold, "cold-wait-time")
    samples = instrumentation_samples(root)
    values = [sample["elapsed_ms"] for sample in samples]
    return {
        "schema_version": 1,
        "blocking": False,
        "source": "android-emulator-recovery",
        "cold_start": {
            "total_ms": total_ms,
            "wait_ms": wait_ms,
        },
        "instrumentation": {
            "sample_count": len(values),
            "min_ms": min(values),
            "median_ms": int(round(statistics.median(values))),
            "p95_ms": sorted(values)[max(0, math.ceil(len(values) * 0.95) - 1)],
            "max_ms": max(values),
            "samples": samples,
        },
    }


def atomic_write(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(value, sort_keys=True, indent=2) + "\n"
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        handle.write(payload)
        temp = Path(handle.name)
    temp.replace(path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--emulator-root", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = build(args.emulator_root)
        atomic_write(args.out, result)
        print(
            "PERFORMANCE_GOLD_BASELINE:"
            f"cold_total_ms={result['cold_start']['total_ms']}:"
            f"samples={result['instrumentation']['sample_count']}"
        )
        return 0
    except PerformanceEvidenceError as error:
        print(f"PERFORMANCE_GOLD_REJECTED:{error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
