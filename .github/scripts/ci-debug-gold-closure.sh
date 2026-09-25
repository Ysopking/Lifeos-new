#!/usr/bin/env bash
set -Eeuo pipefail

required_files=(
  "core/runtime-research/src/main/kotlin/app/lifeos/core/runtime/research/VerifiedOutcomeLearningSignal.kt"
  "core/runtime-research/src/main/kotlin/app/lifeos/core/runtime/research/OwnerPersonalContextSnapshot.kt"
  "core/runtime-research/src/main/kotlin/app/lifeos/core/runtime/research/PersonalContextDeltaPlanner.kt"
  "core/runtime-research/src/main/kotlin/app/lifeos/core/runtime/research/PersonalContextLearningEventFactory.kt"
  "core/runtime-research/src/main/kotlin/app/lifeos/core/runtime/research/OwnerActionReadiness.kt"
  "app/src/main/java/app/lifeos/next/kernel/ProductivePerceptionComposition.kt"
  "core/runtime-research/src/test/kotlin/app/lifeos/core/runtime/research/VerifiedOutcomeLearningSignalTest.kt"
  "core/runtime-research/src/test/kotlin/app/lifeos/core/runtime/research/OwnerPersonalContextSnapshotTest.kt"
  "core/runtime-research/src/test/kotlin/app/lifeos/core/runtime/research/PersonalContextDeltaPlannerTest.kt"
  "core/runtime-research/src/test/kotlin/app/lifeos/core/runtime/research/PersonalContextLearningEventFactoryTest.kt"
  "core/runtime-research/src/test/kotlin/app/lifeos/core/runtime/research/OwnerActionReadinessTest.kt"
)

for file in "${required_files[@]}"; do
  test -s "$file" || {
    echo "DEBUG_GOLD_CLOSURE_MISSING:$file" >&2
    exit 1
  }
done

installer="app/src/main/java/app/lifeos/next/ProcessRuntimeInstaller.kt"
installer_lines="$(wc -l < "$installer" | tr -d ' ')"
installer_budget="$(python3 - <<'PY'
import json
from pathlib import Path
payload = json.loads(Path(".github/architecture-budget.json").read_text(encoding="utf-8"))
print(payload["line_budgets"]["app/src/main/java/app/lifeos/next/ProcessRuntimeInstaller.kt"])
PY
)"

if (( installer_lines > installer_budget )); then
  echo "DEBUG_GOLD_INSTALLER_BUDGET_REJECTED:lines=$installer_lines:budget=$installer_budget" >&2
  exit 1
fi
if (( installer_budget > 480 )); then
  echo "DEBUG_GOLD_INSTALLER_BUDGET_NOT_TIGHTENED:budget=$installer_budget" >&2
  exit 1
fi

grep -Fq 'ProductivePerceptionComposition.prepare' "$installer"
grep -Fq 'ProductivePerceptionComposition.bind' "$installer"
grep -Fq 'LearningProvenance.VERIFIED_OUTCOME'   core/runtime-research/src/main/kotlin/app/lifeos/core/runtime/research/PersonalContextLearningEventFactory.kt
grep -Fq 'OwnerActionReadinessState.INFORMATION_REQUIRED'   core/runtime-research/src/main/kotlin/app/lifeos/core/runtime/research/OwnerActionReadiness.kt
grep -Fq 'val executionAuthority: Boolean'   core/runtime-research/src/main/kotlin/app/lifeos/core/runtime/research/OwnerPersonalContextSnapshot.kt

mkdir -p product-gold-evidence
cat > product-gold-evidence/debug-gold-roadmap.txt <<EOF
schema=1
target=DEBUG_GOLD
release_gold_required=false
b494_verified_outcome_learning=true
b495_owner_personal_context=true
b496_continuous_context_delta=true
b497_continuous_learning_event_bridge=true
b498_information_first_readiness=true
b499_runtime_installer_modularized=true
process_runtime_installer_lines=$installer_lines
process_runtime_installer_budget=$installer_budget
EOF

echo "DEBUG_GOLD_ROADMAP_CLOSURE_OK"
