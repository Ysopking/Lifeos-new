#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("extract-performance-gold.py")
spec = importlib.util.spec_from_file_location("performance_gold", SCRIPT)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    (root / "cold-start.txt").write_text(
        "Status: ok\nLaunchState: COLD\nTotalTime: 420\nWaitTime: 455\n",
        encoding="utf-8",
    )
    (root / "seed-a.txt").write_text("Time: 0.250\nOK (1 test)\n", encoding="utf-8")
    (root / "seed-b.txt").write_text("Time: 1.500\nOK (1 test)\n", encoding="utf-8")
    result = module.build(root)
    assert result["schema_version"] == 1
    assert result["blocking"] is False
    assert result["cold_start"] == {"total_ms": 420, "wait_ms": 455}
    assert result["instrumentation"]["sample_count"] == 2
    assert result["instrumentation"]["min_ms"] == 250
    assert result["instrumentation"]["max_ms"] == 1500
    assert result["instrumentation"]["median_ms"] == 875

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    (root / "cold-start.txt").write_text("TotalTime: 1\nWaitTime: 2\n", encoding="utf-8")
    try:
        module.build(root)
    except module.PerformanceEvidenceError as error:
        assert str(error) == "instrumentation-timing-missing"
    else:
        raise AssertionError("missing instrumentation timings must fail closed")

print("PERFORMANCE_GOLD_PARSER_TEST_OK")
