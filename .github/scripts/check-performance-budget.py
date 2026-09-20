#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


class PerformanceBudgetError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise PerformanceBudgetError(message)


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except OSError as error:
        fail(f"missing:{path}:{error}")
    except json.JSONDecodeError as error:
        fail(f"invalid-json:{path}:{error}")
    if not isinstance(value, dict):
        fail(f"root-not-object:{path}")
    return value


def require_int(value: Any, label: str, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        fail(f"{label}-not-int")
    if value < minimum:
        fail(f"{label}-below-min:{minimum}")
    return value


def require_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        fail(f"{label}-not-string")
    return value


def derived_upper(values: list[int]) -> int:
    if len(values) < 3:
        fail("baseline-measurements-min-3")
    return max(values) + (max(values) - min(values))


def check(evidence: dict[str, Any], budget: dict[str, Any]) -> None:
    if evidence.get("schema_version") != 1:
        fail("evidence-schema")
    if budget.get("schema_version") != 1:
        fail("budget-schema")
    if budget.get("blocking") is not True:
        fail("budget-not-blocking")

    source = require_string(budget.get("source"), "budget-source")
    if evidence.get("source") != source:
        fail(f"source-mismatch:expected={source}:actual={evidence.get('source')}")

    baseline = budget.get("baseline")
    if not isinstance(baseline, dict):
        fail("baseline-not-object")
    head_sha = require_string(baseline.get("head_sha"), "baseline-head-sha")
    if len(head_sha) != 40 or any(ch not in "0123456789abcdef" for ch in head_sha.lower()):
        fail("baseline-head-sha-invalid")
    if baseline.get("derivation") != "upper=max_observed+(max_observed-min_observed)":
        fail("baseline-derivation")

    measurements = baseline.get("measurements")
    if not isinstance(measurements, list) or len(measurements) < 3:
        fail("baseline-measurements-min-3")

    seen_attempts: set[int] = set()
    seen_artifacts: set[int] = set()
    cold_totals: list[int] = []
    cold_waits: list[int] = []
    medians: list[int] = []
    maxima: list[int] = []
    sample_counts: list[int] = []

    for measurement in measurements:
        if not isinstance(measurement, dict):
            fail("baseline-measurement-not-object")
        require_int(measurement.get("run_id"), "baseline-run-id", 1)
        attempt = require_int(measurement.get("attempt"), "baseline-attempt", 1)
        artifact_id = require_int(measurement.get("artifact_id"), "baseline-artifact-id", 1)
        digest = require_string(measurement.get("artifact_sha256"), "baseline-artifact-sha256")
        if len(digest) != 64 or any(ch not in "0123456789abcdef" for ch in digest.lower()):
            fail("baseline-artifact-sha256-invalid")
        if attempt in seen_attempts:
            fail(f"baseline-attempt-duplicate:{attempt}")
        if artifact_id in seen_artifacts:
            fail(f"baseline-artifact-duplicate:{artifact_id}")
        seen_attempts.add(attempt)
        seen_artifacts.add(artifact_id)
        cold_totals.append(require_int(measurement.get("cold_total_ms"), "baseline-cold-total"))
        cold_waits.append(require_int(measurement.get("cold_wait_ms"), "baseline-cold-wait"))
        medians.append(require_int(measurement.get("median_ms"), "baseline-median"))
        maxima.append(require_int(measurement.get("max_ms"), "baseline-max"))
        sample_counts.append(require_int(measurement.get("sample_count"), "baseline-sample-count", 1))

    cold_budget = budget.get("cold_start")
    timing_budget = budget.get("instrumentation")
    if not isinstance(cold_budget, dict) or not isinstance(timing_budget, dict):
        fail("budget-shape")

    expected_total_max = derived_upper(cold_totals)
    expected_wait_max = derived_upper(cold_waits)
    expected_median_max = derived_upper(medians)
    expected_max_max = derived_upper(maxima)
    expected_sample_min = min(sample_counts)

    total_max = require_int(cold_budget.get("total_ms_max"), "cold-total-max", 1)
    wait_max = require_int(cold_budget.get("wait_ms_max"), "cold-wait-max", 1)
    median_max = require_int(timing_budget.get("median_ms_max"), "instrumentation-median-max", 1)
    max_max = require_int(timing_budget.get("max_ms_max"), "instrumentation-max-max", 1)
    sample_count_min = require_int(timing_budget.get("sample_count_min"), "instrumentation-sample-count-min", 1)

    if total_max != expected_total_max:
        fail(f"cold-total-budget-not-derived:expected={expected_total_max}:actual={total_max}")
    if wait_max != expected_wait_max:
        fail(f"cold-wait-budget-not-derived:expected={expected_wait_max}:actual={wait_max}")
    if median_max != expected_median_max:
        fail(f"median-budget-not-derived:expected={expected_median_max}:actual={median_max}")
    if max_max != expected_max_max:
        fail(f"max-budget-not-derived:expected={expected_max_max}:actual={max_max}")
    if sample_count_min != expected_sample_min:
        fail(f"sample-count-budget-not-derived:expected={expected_sample_min}:actual={sample_count_min}")

    cold = evidence.get("cold_start")
    timing = evidence.get("instrumentation")
    if not isinstance(cold, dict) or not isinstance(timing, dict):
        fail("evidence-shape")

    total_ms = require_int(cold.get("total_ms"), "cold-total")
    wait_ms = require_int(cold.get("wait_ms"), "cold-wait")
    sample_count = require_int(timing.get("sample_count"), "instrumentation-sample-count")
    median_ms = require_int(timing.get("median_ms"), "instrumentation-median")
    max_ms = require_int(timing.get("max_ms"), "instrumentation-max")

    if total_ms > total_max:
        fail(f"cold-total:max={total_max}:actual={total_ms}")
    if wait_ms > wait_max:
        fail(f"cold-wait:max={wait_max}:actual={wait_ms}")
    if sample_count < sample_count_min:
        fail(f"instrumentation-sample-count:min={sample_count_min}:actual={sample_count}")
    if median_ms > median_max:
        fail(f"instrumentation-median:max={median_max}:actual={median_ms}")
    if max_ms > max_max:
        fail(f"instrumentation-max:max={max_max}:actual={max_ms}")

    samples = timing.get("samples")
    if not isinstance(samples, list):
        fail("instrumentation-samples-not-list")
    if len(samples) != sample_count:
        fail(f"instrumentation-sample-count-mismatch:declared={sample_count}:actual={len(samples)}")

    actual_files: set[str] = set()
    for sample in samples:
        if not isinstance(sample, dict):
            fail("instrumentation-sample-not-object")
        filename = require_string(sample.get("file"), "instrumentation-sample-file")
        require_int(sample.get("elapsed_ms"), f"instrumentation-sample-elapsed:{filename}")
        if filename in actual_files:
            fail(f"instrumentation-duplicate-file:{filename}")
        actual_files.add(filename)

    required_files = timing_budget.get("required_sample_files")
    if not isinstance(required_files, list) or not required_files:
        fail("required-sample-files")
    normalized_required = {
        require_string(filename, "required-sample-file")
        for filename in required_files
    }
    if len(normalized_required) != len(required_files):
        fail("required-sample-files-duplicate")
    missing = sorted(normalized_required - actual_files)
    if missing:
        fail(f"required-samples-missing:{','.join(missing)}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--budget", required=True, type=Path)
    args = parser.parse_args()
    try:
        check(load_json(args.evidence), load_json(args.budget))
        print("PERFORMANCE_BUDGET_OK")
        return 0
    except PerformanceBudgetError as error:
        print(f"PERFORMANCE_BUDGET_REJECTED:{error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
