#!/usr/bin/env python3
from __future__ import annotations

import copy
import importlib.util
from pathlib import Path

SCRIPT = Path(__file__).with_name("check-performance-budget.py")
spec = importlib.util.spec_from_file_location("performance_budget", SCRIPT)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)

EVIDENCE = {
    "schema_version": 1,
    "blocking": False,
    "source": "android-emulator-recovery",
    "cold_start": {"total_ms": 420, "wait_ms": 455},
    "instrumentation": {
        "sample_count": 2,
        "min_ms": 250,
        "median_ms": 875,
        "max_ms": 1500,
        "samples": [
            {"file": "seed-a.txt", "elapsed_ms": 250},
            {"file": "seed-b.txt", "elapsed_ms": 1500},
        ],
    },
}

MEASUREMENTS = [
    {
        "run_id": 1001, "attempt": 1, "artifact_id": 3001, "artifact_sha256": "a" * 64,
        "cold_total_ms": 400, "cold_wait_ms": 430, "sample_count": 2, "median_ms": 800, "max_ms": 1400,
    },
    {
        "run_id": 1001, "attempt": 2, "artifact_id": 3002, "artifact_sha256": "b" * 64,
        "cold_total_ms": 420, "cold_wait_ms": 455, "sample_count": 2, "median_ms": 875, "max_ms": 1500,
    },
    {
        "run_id": 1001, "attempt": 3, "artifact_id": 3003, "artifact_sha256": "c" * 64,
        "cold_total_ms": 410, "cold_wait_ms": 440, "sample_count": 2, "median_ms": 850, "max_ms": 1450,
    },
]

BUDGET = {
    "schema_version": 1,
    "blocking": True,
    "source": "android-emulator-recovery",
    "baseline": {
        "head_sha": "1" * 40,
        "derivation": "upper=max_observed+(max_observed-min_observed)",
        "measurements": MEASUREMENTS,
    },
    "cold_start": {
        "total_ms_max": 440,
        "wait_ms_max": 480,
    },
    "instrumentation": {
        "sample_count_min": 2,
        "median_ms_max": 950,
        "max_ms_max": 1600,
        "required_sample_files": ["seed-a.txt", "seed-b.txt"],
    },
}


def rejects(mutator, expected: str) -> None:
    evidence = copy.deepcopy(EVIDENCE)
    budget = copy.deepcopy(BUDGET)
    mutator(evidence, budget)
    try:
        module.check(evidence, budget)
    except module.PerformanceBudgetError as error:
        assert expected in str(error), (expected, str(error))
    else:
        raise AssertionError(f"expected rejection containing {expected!r}")


module.check(copy.deepcopy(EVIDENCE), copy.deepcopy(BUDGET))
rejects(lambda e, b: e["cold_start"].__setitem__("total_ms", 441), "cold-total")
rejects(lambda e, b: e["cold_start"].__setitem__("wait_ms", 481), "cold-wait")
rejects(lambda e, b: e["instrumentation"].__setitem__("median_ms", 951), "instrumentation-median")
rejects(lambda e, b: e["instrumentation"].__setitem__("max_ms", 1601), "instrumentation-max")
rejects(lambda e, b: e["instrumentation"].__setitem__("sample_count", 1), "instrumentation-sample-count")
rejects(lambda e, b: e["instrumentation"].__setitem__("samples", e["instrumentation"]["samples"][:1]), "instrumentation-sample-count-mismatch")
rejects(lambda e, b: e["instrumentation"]["samples"][1].__setitem__("file", "other.txt"), "required-samples-missing")
rejects(lambda e, b: b.__setitem__("blocking", False), "budget-not-blocking")
rejects(lambda e, b: b.__setitem__("schema_version", 2), "budget-schema")
rejects(lambda e, b: b["baseline"].__setitem__("measurements", b["baseline"]["measurements"][:2]), "baseline-measurements-min-3")
rejects(lambda e, b: b["baseline"]["measurements"][2].__setitem__("run_id", 1002), "baseline-run-id-mismatch")
rejects(lambda e, b: b["cold_start"].__setitem__("total_ms_max", 999), "cold-total-budget-not-derived")
rejects(lambda e, b: b["instrumentation"].__setitem__("median_ms_max", 999), "median-budget-not-derived")
rejects(lambda e, b: e.__setitem__("schema_version", 2), "evidence-schema")

print("PERFORMANCE_BUDGET_TEST_OK")
