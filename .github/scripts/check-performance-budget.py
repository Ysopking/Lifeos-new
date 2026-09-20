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
    run_ids = baseline.get("run_ids")
    if not isinstance(run_ids, list) or not run_ids:
        fail("baseline-run-ids")
    for run_id in run_ids:
        require_int(run_id, "baseline-run-id", 1)
    require_int(baseline.get("sample_count"), "baseline-sample-count", 1)

    cold = evidence.get("cold_start")
    cold_budget = budget.get("cold_start")
    if not isinstance(cold, dict) or not isinstance(cold_budget, dict):
        fail("cold-start-shape")
    total_ms = require_int(cold.get("total_ms"), "cold-total")
    wait_ms = require_int(cold.get("wait_ms"), "cold-wait")
    total_max = require_int(cold_budget.get("total_ms_max"), "cold-total-max", 1)
    wait_max = require_int(cold_budget.get("wait_ms_max"), "cold-wait-max", 1)
    if total_ms > total_max:
        fail(f"cold-total:max={total_max}:actual={total_ms}")
    if wait_ms > wait_max:
        fail(f"cold-wait:max={wait_max}:actual={wait_ms}")

    timing = evidence.get("instrumentation")
    timing_budget = budget.get("instrumentation")
    if not isinstance(timing, dict) or not isinstance(timing_budget, dict):
        fail("instrumentation-shape")
    sample_count = require_int(timing.get("sample_count"), "instrumentation-sample-count")
    median_ms = require_int(timing.get("median_ms"), "instrumentation-median")
    max_ms = require_int(timing.get("max_ms"), "instrumentation-max")
    sample_count_min = require_int(timing_budget.get("sample_count_min"), "instrumentation-sample-count-min", 1)
    median_max = require_int(timing_budget.get("median_ms_max"), "instrumentation-median-max", 1)
    max_max = require_int(timing_budget.get("max_ms_max"), "instrumentation-max-max", 1)
    if sample_count < sample_count_min:
        fail(f"instrumentation-sample-count:min={sample_count_min}:actual={sample_count}")
    if median_ms > median_max:
        fail(f"instrumentation-median:max={median_max}:actual={median_ms}")
    if max_ms > max_max:
        fail(f"instrumentation-max:max={max_max}:actual={max_ms}")

    samples = timing.get("samples")
    if not isinstance(samples, list):
        fail("instrumentation-samples-not-list")
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
    normalized_required: set[str] = set()
    for filename in required_files:
        normalized_required.add(require_string(filename, "required-sample-file"))
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
