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

BUDGET = {
    "schema_version": 1,
    "blocking": True,
    "source": "android-emulator-recovery",
    "baseline": {
        "head_sha": "1" * 40,
        "run_ids": [1001, 1002, 1003],
        "sample_count": 3,
    },
    "cold_start": {"total_ms_max": 500, "wait_ms_max": 550},
    "instrumentation": {
        "sample_count_min": 2,
        "median_ms_max": 900,
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
rejects(lambda e, b: e["cold_start"].__setitem__("total_ms", 501), "cold-total")
rejects(lambda e, b: e["cold_start"].__setitem__("wait_ms", 551), "cold-wait")
rejects(lambda e, b: e["instrumentation"].__setitem__("median_ms", 901), "instrumentation-median")
rejects(lambda e, b: e["instrumentation"].__setitem__("max_ms", 1601), "instrumentation-max")
rejects(lambda e, b: e["instrumentation"].__setitem__("sample_count", 1), "instrumentation-sample-count")
rejects(lambda e, b: e["instrumentation"].__setitem__("samples", e["instrumentation"]["samples"][:1]), "required-samples-missing")
rejects(lambda e, b: b.__setitem__("blocking", False), "budget-not-blocking")
rejects(lambda e, b: b.__setitem__("schema_version", 2), "budget-schema")
rejects(lambda e, b: e.__setitem__("schema_version", 2), "evidence-schema")

print("PERFORMANCE_BUDGET_TEST_OK")
