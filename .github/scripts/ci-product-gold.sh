#!/usr/bin/env bash
set -Eeuo pipefail

step() {
  printf '\n==> %s\n' "$1"
}

evidence_dir="product-gold-evidence"
apk_path="app/build/outputs/apk/debug/app-debug.apk"
sibling_evidence="$evidence_dir/sibling-runs.json"
mkdir -p "$evidence_dir"

step "Product Gold 01: private security invariants"
bash .github/scripts/ci-private-debug-security.sh

step "Product Gold 02: mandatory coverage contract"
bash .github/scripts/ci-gold-coverage-contract.sh

step "Product Gold 03: exact-head sibling evidence"
test -s "$sibling_evidence"
test -s "$apk_path"
find . -path '*/build/test-results/*/TEST-*.xml' -type f -print -quit | grep -q .
find app/build/reports -maxdepth 1 -type f -name 'lint-results-debug.*' -print -quit | grep -q .
find android-emulator-recovery -type f -print -quit | grep -q .

step "Product Gold 03b: measured non-blocking performance baseline"
python3 .github/scripts/extract-performance-gold.py \
  --emulator-root android-emulator-recovery \
  --out "$evidence_dir/performance-baseline.json"
test -s "$evidence_dir/performance-baseline.json"

step "Product Gold 03c: blocking performance budget"
python3 .github/scripts/check-performance-budget.py \
  --evidence "$evidence_dir/performance-baseline.json" \
  --budget .github/performance-budget.json

step "Product Gold 04: integration contract presence"
test -f core/runtime/src/main/kotlin/app/lifeos/core/runtime/topology/LifeOsRuntimeBindings.kt
test -f core/runtime/src/main/kotlin/app/lifeos/core/runtime/topology/LifeOsRuntimeTopology.kt
test -f app/src/androidTest/java/app/lifeos/next/ProductGoldenChatDeviceTest.kt
test -f app/src/androidTest/java/app/lifeos/next/LanguageRuntimeRecoveryDeviceTest.kt
test -f app/src/androidTest/java/app/lifeos/next/PersonalConversationImportDeviceTest.kt
test -f app/src/androidTest/java/app/lifeos/next/PersonalCorpusLanguageDeviceTest.kt
test -f app/src/androidTest/java/app/lifeos/next/ProductiveWorldPersistenceDeviceTest.kt
test -f app/src/androidTest/java/app/lifeos/next/Level7ProcessDeathGoldDeviceTest.kt
test -f app/src/androidTest/java/app/lifeos/next/Level7WorldEquationRollbackDeviceTest.kt
test -f app/src/androidTest/java/app/lifeos/next/Level7TruthClosureGoldDeviceTest.kt
test -f app/src/androidTest/java/app/lifeos/next/SelfObservationGoldDeviceTest.kt
grep -q 'ProductGoldenChatDeviceTest#seedProductGoldChatRoundTrip' .github/scripts/android-emulator-recovery.sh
grep -q 'ProductGoldenChatDeviceTest#recoverProductGoldChatRoundTrip' .github/scripts/android-emulator-recovery.sh
grep -q 'LanguageRuntimeRecoveryDeviceTest#seedPromotedPersonalLanguageSnapshot' .github/scripts/android-emulator-recovery.sh
grep -q 'LanguageRuntimeRecoveryDeviceTest#recoverPromotedSnapshotThenRollbackDurablyAfterColdStart' .github/scripts/android-emulator-recovery.sh
grep -q 'PersonalConversationImportDeviceTest' .github/scripts/android-emulator-recovery.sh
grep -q 'PersonalCorpusLanguageDeviceTest' .github/scripts/android-emulator-recovery.sh
grep -q 'ProductiveWorldPersistenceDeviceTest' .github/scripts/android-emulator-recovery.sh
grep -q 'Level7ProcessDeathGoldDeviceTest#seedLevel7SemanticCheckpoint' .github/scripts/android-emulator-recovery.sh
grep -q 'Level7ProcessDeathGoldDeviceTest#recoverExactHeadsAndDoNotApplyLearningTwice' .github/scripts/android-emulator-recovery.sh
grep -q 'Level7WorldEquationRollbackDeviceTest#seedDegradedTrialHead' .github/scripts/android-emulator-recovery.sh
grep -q 'Level7WorldEquationRollbackDeviceTest#rollbackAfterProcessDeathRestoresExactV17WorldHead' .github/scripts/android-emulator-recovery.sh
grep -q 'Level7TruthClosureGoldDeviceTest#seedExactAuthorityChainBeforeProcessDeath' .github/scripts/android-emulator-recovery.sh
grep -q 'Level7TruthClosureGoldDeviceTest#recoverExactAuthorityChainRollbackAndSealGold' .github/scripts/android-emulator-recovery.sh
grep -q 'SelfObservationGoldDeviceTest#seedAuthoritativeSelfStateBeforeProcessDeath' .github/scripts/android-emulator-recovery.sh
grep -q 'SelfObservationGoldDeviceTest#recoverAuthorityFingerprintAndObserveLiveState' .github/scripts/android-emulator-recovery.sh
grep -q 'ChatMainActivity' app/src/main/AndroidManifest.xml

step "Product Gold 05: immutable candidate evidence"
checkout_sha="$(git rev-parse HEAD)"
candidate_sha="${CANDIDATE_SHA:-$checkout_sha}"
source_head_sha="${SOURCE_HEAD_SHA:-$candidate_sha}"
if [[ ! "$candidate_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "candidate-sha-invalid:$candidate_sha" >&2
  exit 1
fi
if [[ ! "$source_head_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "source-head-sha-invalid:$source_head_sha" >&2
  exit 1
fi
if [[ "$candidate_sha" != "$checkout_sha" ]]; then
  echo "candidate-sha-checkout-mismatch:candidate=$candidate_sha checkout=$checkout_sha" >&2
  exit 1
fi
python3 - "$sibling_evidence" "$source_head_sha" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
expected = sys.argv[2]
payload = json.loads(path.read_text(encoding="utf-8"))
if payload.get("head_sha") != expected:
    raise SystemExit("sibling-evidence-head-mismatch")
required = {"Core Fast Gate", "Android Debug CI", "Android Emulator Recovery"}
workflows = payload.get("workflows") or {}
if set(workflows) != required:
    raise SystemExit("sibling-evidence-required-workflows-mismatch")
for name in sorted(required):
    item = workflows[name]
    if item.get("head_sha") != expected or item.get("conclusion") != "success":
        raise SystemExit(f"sibling-evidence-not-green:{name}")
print("EXACT_HEAD_SIBLING_EVIDENCE_OK")
PY
sha256sum "$apk_path" | tee "$evidence_dir/app-debug.sha256"
sha256sum "$sibling_evidence" | tee "$evidence_dir/sibling-runs.sha256"
printf 'candidate_sha=%s\n' "$candidate_sha" | tee "$evidence_dir/candidate.txt"
printf 'source_head_sha=%s\n' "$source_head_sha" | tee -a "$evidence_dir/candidate.txt"
printf 'checkout_sha=%s\n' "$checkout_sha" | tee -a "$evidence_dir/candidate.txt"
printf 'workflow_run_id=%s\n' "${GOLD_RUN_ID:-local}" | tee -a "$evidence_dir/candidate.txt"
printf 'workflow_job=%s\n' "${GOLD_JOB:-local}" | tee -a "$evidence_dir/candidate.txt"
printf 'event_name=%s\n' "${GITHUB_EVENT_NAME:-local}" | tee -a "$evidence_dir/candidate.txt"
printf 'git_ref=%s\n' "${GITHUB_REF:-local}" | tee -a "$evidence_dir/candidate.txt"
printf 'git_ref_name=%s\n' "${GITHUB_REF_NAME:-local}" | tee -a "$evidence_dir/candidate.txt"
printf 'product_gold_requires_emulator=true\n' | tee -a "$evidence_dir/candidate.txt"
printf 'product_gold_reuses_exact_head_gates=true\n' | tee -a "$evidence_dir/candidate.txt"
python3 .github/scripts/seal-gold-evidence.py pre \
  --candidate-sha "$candidate_sha" \
  --source-head-sha "$source_head_sha" \
  --apk "$apk_path" \
  --reports-root . \
  --out "$evidence_dir/pre-emulator.json"
test -s "$evidence_dir/pre-emulator.json"

step "Product Gold evidence preflight complete"
