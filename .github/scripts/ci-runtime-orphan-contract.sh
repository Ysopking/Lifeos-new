#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re

topology = Path("core/runtime/src/main/kotlin/app/lifeos/core/runtime/topology/LifeOsRuntimeTopology.kt")
text = topology.read_text(encoding="utf-8")
ids = set(re.findall(r'manifest\(\s*"([^"]+)"', text))
if len(ids) != 43:
    raise SystemExit(f"RUNTIME_ORPHAN_REJECTED:canonical-id-count:{len(ids)}")

unknown = []
roots = [
    Path("core/runtime/src/main/kotlin"),
    Path("app/src/main/java"),
]
patterns = [
    re.compile(r'SubsystemId\("([^"]+)"\)'),
    re.compile(r'const\s+val\s+SUBSYSTEM_ID\s*=\s*"([^"]+)"'),
    re.compile(r'subsystemId\s*=\s*"([^"]+)"'),
]
for root in roots:
    for path in root.rglob("*.kt"):
        source = path.read_text(encoding="utf-8")
        for pattern in patterns:
            for value in pattern.findall(source):
                if value not in ids:
                    unknown.append(f"{path}:{value}")
if unknown:
    raise SystemExit("RUNTIME_ORPHAN_REJECTED:unknown-subsystem-literal:" + ",".join(sorted(set(unknown))))

required = {
    "deep-search": "SubsystemStartupOwner.DEEP_SEARCH",
    "self-healing": "SubsystemStartupOwner.SELF_HEALING",
    "goal-planning": "SubsystemStartupOwner.DURABLE_GOALS",
    "hot-swap-runtime": "SubsystemStartupOwner.OPTIONAL_RUNTIME",
    "build-studio": "SubsystemStartupOwner.EXTERNAL_HOST",
}
for subsystem, owner in required.items():
    marker = f'"{subsystem}"'
    start = text.find(marker)
    if start < 0:
        raise SystemExit(f"RUNTIME_ORPHAN_REJECTED:manifest-missing:{subsystem}")
    window = text[start:start + 600]
    if owner not in window:
        raise SystemExit(f"RUNTIME_ORPHAN_REJECTED:owner-drift:{subsystem}:{owner}")

print("RUNTIME_ORPHAN_CONTRACT_OK")
PY
