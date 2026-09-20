#!/usr/bin/env bash
set -Eeuo pipefail

required_jvm_tests=(
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/buildstudio/BuildStudioHostRuntimeTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/capability/BoundedGeneratedToolBootRecoveryTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/capability/DurableToolWorkshopOwnerRevocationTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/capability/HotSwapRoutingRecoveryTest.kt"
  "core/runtime-deepsearch/src/test/kotlin/app/lifeos/core/runtime/deepsearch/DeepSearchMissionRecoveryAuditTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/resource/HardwareAdaptiveResourceIntelligenceTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/resource/ResourceIntelligenceCompletionTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/resource/WorldFormulaBudgetBrokerTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/trace/SubsystemDecisionTraceRecorderTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/hardening/V17HardeningEvidenceTest.kt"
  "host/buildstudio/src/test/kotlin/app/lifeos/host/buildstudio/JvmBuildStudioHostTest.kt"
  "app/src/test/java/app/lifeos/next/kernel/GenesisBuildStudioExpansionRuntimeTest.kt"
  "app/src/test/java/app/lifeos/next/kernel/AndroidWebDeepSearchSourceTest.kt"
  "app/src/test/java/app/lifeos/next/OutcomeOverviewTest.kt"
  "core/language/src/test/kotlin/app/lifeos/core/language/LanguageGoldCorpusTest.kt"
  "core/language/src/test/kotlin/app/lifeos/core/language/SemanticActionSafetyTest.kt"
  "core/language/src/test/kotlin/app/lifeos/core/language/EntitySystemV2Test.kt"
  "core/language/src/test/kotlin/app/lifeos/core/language/QuantityTemporalEngineTest.kt"
  "core/language/src/test/kotlin/app/lifeos/core/language/LinguisticFieldIndexV2Test.kt"
  "core/language/src/test/kotlin/app/lifeos/core/language/RevisionAwareReferenceResolverTest.kt"
  "core/language/src/test/kotlin/app/lifeos/core/language/LanguageContextRetrieverTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/ConversationFastPathSafetyTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/goal/GoalResumeEngineTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/agency/ExternalEffectExecutorTest.kt"
  "core/language/src/test/kotlin/app/lifeos/core/language/DomainSemanticPackGoldTest.kt"
  "core/language/src/test/kotlin/app/lifeos/core/language/SemanticInterpretationQualityTest.kt"
  "app/src/test/java/app/lifeos/next/kernel/SemanticActionGraphRouterTest.kt"
  "app/src/test/java/app/lifeos/next/kernel/ExternalSemanticEffectSafetyTest.kt"
  "app/src/test/java/app/lifeos/next/kernel/DurableGoalPlanRecoveryTest.kt"
)

required_device_tests=(
  "app/src/androidTest/java/app/lifeos/next/OwnerPolicyAssetWriteDeviceTest.kt"
  "app/src/androidTest/java/app/lifeos/next/GoalPlanRecoveryDeviceTest.kt"
  "app/src/androidTest/java/app/lifeos/next/OutcomeLearningDeviceTest.kt"
  "app/src/androidTest/java/app/lifeos/next/ConvergenceDecisionDeviceTest.kt"
  "app/src/androidTest/java/app/lifeos/next/DeepSearchEncryptedRepositoryCorruptionDeviceTest.kt"
  "app/src/androidTest/java/app/lifeos/next/OfflineImageArtifactDeviceTest.kt"
  "app/src/androidTest/java/app/lifeos/next/FieldSnapshotAtomicRecoveryDeviceTest.kt"
  "app/src/androidTest/java/app/lifeos/next/ProductGoldenChatDeviceTest.kt"
  "app/src/androidTest/java/app/lifeos/next/SemanticActionRecoveryDeviceTest.kt"
  "app/src/androidTest/java/app/lifeos/next/Level7TruthClosureGoldDeviceTest.kt"
  "app/src/androidTest/java/app/lifeos/next/SelfObservationGoldDeviceTest.kt"
)

for path in "${required_jvm_tests[@]}" "${required_device_tests[@]}"; do
  test -s "$path" || { echo "missing-gold-test:$path" >&2; exit 1; }
done

action_pin_contract=".github/scripts/ci-action-pin-contract.sh"
test -s "$action_pin_contract" || { echo "missing-action-pin-contract" >&2; exit 1; }
grep -Fq 'ci-action-pin-contract.sh' .github/scripts/ci-core-fast.sh || {
  echo "core-fast-action-pin-contract-not-enforced" >&2
  exit 1
}
bash "$action_pin_contract"

android_gate=".github/scripts/ci-android-debug.sh"
for command in 'test' ':app:lintDebug' ':app:assembleDebug'; do
  grep -Fq "$command" "$android_gate" || { echo "missing-android-gate-command:$command" >&2; exit 1; }
done

emulator_gate=".github/scripts/android-emulator-recovery.sh"
for suite in \
  'OwnerPolicyAssetWriteDeviceTest' \
  'GoalPlanRecoveryDeviceTest' \
  'OutcomeLearningDeviceTest' \
  'ConvergenceDecisionDeviceTest' \
  'DeepSearchEncryptedRepositoryCorruptionDeviceTest' \
  'OfflineImageArtifactDeviceTest' \
  'FieldSnapshotAtomicRecoveryDeviceTest' \
  'ProductGoldenChatDeviceTest' \
  'SemanticActionRecoveryDeviceTest' \
  'Level7TruthClosureGoldDeviceTest' \
  'SelfObservationGoldDeviceTest'; do
  grep -Fq "$suite" "$emulator_gate" || { echo "missing-emulator-gold-suite:$suite" >&2; exit 1; }
done

require_exact_head_workflow() {
  local workflow="$1"
  local stable_job_name="$2"

  grep -Fq 'push:' "$workflow" || {
    echo "exact-head-push-trigger-missing:$workflow" >&2
    exit 1
  }

  grep -Fq 'pull_request:' "$workflow" || {
    echo "exact-head-pr-trigger-missing:$workflow" >&2
    exit 1
  }

  grep -Fq 'merge_group:' "$workflow" || {
    echo "exact-head-merge-group-trigger-missing:$workflow" >&2
    exit 1
  }

  grep -Fq 'github.event.pull_request.number || github.sha' "$workflow" || {
    echo "exact-head-concurrency-key-missing:$workflow" >&2
    exit 1
  }

  grep -Fq "github.event_name == 'pull_request'" "$workflow" || {
    echo "exact-head-cancel-policy-missing:$workflow" >&2
    exit 1
  }

  grep -Fq "    name: $stable_job_name" "$workflow" || {
    echo "stable-required-check-name-missing:$workflow" >&2
    exit 1
  }
}

require_exact_head_workflow ".github/workflows/core-fast.yml" "Core Fast Gate"
require_exact_head_workflow ".github/workflows/android.yml" "Android Debug CI"
require_exact_head_workflow ".github/workflows/android-emulator-recovery.yml" "Android Emulator Recovery"
require_exact_head_workflow ".github/workflows/product-gold.yml" "LIFEOS Product Gold"

ruleset_contract=".github/scripts/ci-main-authority-contract.sh"
test -s "$ruleset_contract" || { echo "missing-main-authority-contract" >&2; exit 1; }
bash "$ruleset_contract"

gold_sealer=".github/scripts/seal-gold-evidence.py"
gold_sealer_selftest=".github/scripts/test-seal-gold-evidence.py"
test -s "$gold_sealer" || { echo "gold-evidence-sealer-missing" >&2; exit 1; }
test -s "$gold_sealer_selftest" || { echo "gold-evidence-sealer-selftest-missing" >&2; exit 1; }
python3 "$gold_sealer_selftest"

product_gold_workflow=".github/workflows/product-gold.yml"
grep -Fq 'CANDIDATE_SHA: ${{ github.sha }}' "$product_gold_workflow" || {
  echo "product-gold-candidate-sha-not-bound-to-checkout-ref" >&2
  exit 1
}
grep -Fq 'SOURCE_HEAD_SHA:' "$product_gold_workflow" || {
  echo "product-gold-source-head-sha-not-recorded" >&2
  exit 1
}
grep -Fq 'seal-gold-evidence.py final' "$product_gold_workflow" || {
  echo "product-gold-final-attestation-missing" >&2
  exit 1
}
grep -Fq -- '--emulator-root android-emulator-recovery' "$product_gold_workflow" || {
  echo "product-gold-emulator-evidence-not-bound" >&2
  exit 1
}
grep -Fq 'product-gold-evidence/product-gold.json' "$product_gold_workflow" || {
  echo "product-gold-final-json-not-produced" >&2
  exit 1
}
if grep -Fq 'product_gold=PASS' "$product_gold_workflow"; then
  echo "product-gold-bare-pass-string-forbidden" >&2
  exit 1
fi

product_gold_script=".github/scripts/ci-product-gold.sh"
grep -Fq 'candidate-sha-checkout-mismatch' "$product_gold_script" || {
  echo "product-gold-checkout-sha-not-verified" >&2
  exit 1
}
grep -Fq 'source_head_sha=' "$product_gold_script" || {
  echo "product-gold-source-head-evidence-missing" >&2
  exit 1
}
grep -Fq 'seal-gold-evidence.py pre' "$product_gold_script" || {
  echo "product-gold-pre-attestation-missing" >&2
  exit 1
}
grep -Fq 'pre-emulator.json' "$product_gold_script" || {
  echo "product-gold-pre-json-not-produced" >&2
  exit 1
}

semantic_contract=".github/scripts/ci-language-semantic-contract.sh"
test -s "$semantic_contract" || {
  echo "language-semantic-architecture-contract-missing" >&2
  exit 1
}
grep -Fq 'ci-language-semantic-contract.sh' .github/scripts/ci-core-fast.sh || {
  echo "language-semantic-architecture-contract-not-enforced" >&2
  exit 1
}

grep -Fq 'ci-core-fast.sh' .github/scripts/ci-v17-gold.sh
grep -Fq 'ci-android-debug.sh' .github/scripts/ci-v17-gold.sh
grep -Fq 'ci-emulator-preflight.sh' .github/scripts/ci-v17-gold.sh

echo "V17_GOLD_COVERAGE_CONTRACT_OK"