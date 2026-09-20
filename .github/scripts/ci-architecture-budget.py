#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"ARCHITECTURE_BUDGET_REJECTED:{message}")


root = Path(".")
budget_path = root / ".github/architecture-budget.json"
try:
    budget = json.loads(budget_path.read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError) as error:
    fail(f"budget-unreadable:{error}")

if budget.get("schema_version") != 1:
    fail("schema-version")

baseline = budget.get("baseline")
if not isinstance(baseline, dict):
    fail("baseline-not-object")
source_head = baseline.get("source_head_sha")
if not isinstance(source_head, str) or len(source_head) != 40 or any(
    ch not in "0123456789abcdef" for ch in source_head.lower()
):
    fail("baseline-source-head-invalid")

line_budgets = budget.get("line_budgets")
if not isinstance(line_budgets, dict) or not line_budgets:
    fail("line-budgets-empty")
for raw_path, maximum in sorted(line_budgets.items()):
    path = root / raw_path
    if not path.is_file():
        fail(f"budget-file-missing:{raw_path}")
    if isinstance(maximum, bool) or not isinstance(maximum, int) or maximum < 1:
        fail(f"line-budget-invalid:{raw_path}")
    lines = len(path.read_text(encoding="utf-8").splitlines())
    if lines > maximum:
        fail(f"line-budget:{raw_path}:max={maximum}:actual={lines}")

runtime_root = root / "core/runtime/src/main/kotlin/app/lifeos/core/runtime"
runtime_files = sorted(runtime_root.rglob("*.kt"))
max_runtime = budget.get("runtime_monolith_max_kotlin_files")
if isinstance(max_runtime, bool) or not isinstance(max_runtime, int) or max_runtime < 1:
    fail("runtime-monolith-budget-invalid")
if len(runtime_files) > max_runtime:
    fail(f"runtime-monolith-files:max={max_runtime}:actual={len(runtime_files)}")
if baseline.get("runtime_kotlin_file_count") != max_runtime:
    fail("runtime-baseline-budget-mismatch")

for prefix in budget.get("forbidden_runtime_package_prefixes", []):
    path = root / prefix
    if path.exists() and any(path.rglob("*.kt")):
        fail(f"forbidden-runtime-package:{prefix}")

allowed_buildstudio = set(budget.get("allowed_runtime_buildstudio_files", []))
actual_buildstudio = {
    path.as_posix()
    for path in (runtime_root / "buildstudio").glob("*.kt")
}
if actual_buildstudio != allowed_buildstudio:
    fail(
        "runtime-buildstudio-files:"
        f"expected={sorted(allowed_buildstudio)}:actual={sorted(actual_buildstudio)}"
    )

project_pattern = re.compile(r'project\("(:[^"]+)"\)')
module_dependencies = budget.get("module_dependencies")
if not isinstance(module_dependencies, dict) or not module_dependencies:
    fail("module-dependencies-empty")
for raw_path, expected in sorted(module_dependencies.items()):
    path = root / raw_path
    if not path.is_file():
        fail(f"module-build-file-missing:{raw_path}")
    if not isinstance(expected, list) or any(not isinstance(item, str) for item in expected):
        fail(f"module-dependencies-invalid:{raw_path}")
    actual = sorted(project_pattern.findall(path.read_text(encoding="utf-8")))
    if actual != sorted(expected):
        fail(
            f"module-dependencies:{raw_path}:"
            f"expected={sorted(expected)}:actual={actual}"
        )

topology = root / "core/runtime/src/main/kotlin/app/lifeos/core/runtime/topology/LifeOsRuntimeTopology.kt"
if not topology.is_file():
    fail("runtime-topology-missing")
topology_text = topology.read_text(encoding="utf-8")
ids = set(re.findall(r'manifest\(\s*"([^"]+)"', topology_text))
expected_count = budget.get("canonical_subsystem_count")
if isinstance(expected_count, bool) or not isinstance(expected_count, int) or expected_count < 1:
    fail("canonical-subsystem-budget-invalid")
if len(ids) != expected_count:
    fail(f"canonical-subsystem-count:expected={expected_count}:actual={len(ids)}")
if baseline.get("canonical_subsystem_count") != expected_count:
    fail("canonical-subsystem-baseline-mismatch")

for raw_path in budget.get("unified_vault_files", []):
    if not (root / raw_path).is_file():
        fail(f"unified-vault-file-missing:{raw_path}")
for raw_path in budget.get("compatibility_vault_adapters", []):
    if not (root / raw_path).is_file():
        fail(f"vault-adapter-missing:{raw_path}")

permission_profile = root / budget["permission_profile_file"]
if not permission_profile.is_file():
    fail("permission-profile-file-missing")
security_contract = (root / ".github/scripts/ci-private-debug-security.sh").read_text(encoding="utf-8")
if "manifest-permission-without-profile" not in security_contract:
    fail("manifest-permission-profile-contract-missing")

wrapper_contract = (root / ".github/scripts/ci-buildstudio-wrapper-contract.sh").read_text(encoding="utf-8")
if "BUILDSTUDIO_WRAPPER_CONTRACT_OK" not in wrapper_contract:
    fail("buildstudio-wrapper-contract-missing")

performance_budget = root / ".github/performance-budget.json"
if not performance_budget.is_file():
    fail("performance-budget-missing")
performance_contract = (root / ".github/scripts/ci-product-gold.sh").read_text(encoding="utf-8")
if "check-performance-budget.py" not in performance_contract:
    fail("performance-budget-not-wired")

print("ARCHITECTURE_BUDGET_OK")
