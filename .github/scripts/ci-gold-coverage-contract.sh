#!/usr/bin/env bash
set -Eeuo pipefail

required_jvm_tests=(
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/buildstudio/BuildStudioHostRuntimeTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/capability/BoundedGeneratedToolBootRecoveryTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/capability/DurableToolWorkshopOwnerRevocationTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/capability/HotSwapRoutingRecoveryTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/deepsearch/DeepSearchMissionRecoveryAuditTest.kt"
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
  'Level7TruthClosureGoldDeviceTest'; do
  grep -Fq "$suite" "$emulator_gate" || { echo "missing-emulator-gold-suite:$suite" >&2; exit 1; }
done

core_fast_workflow=".github/workflows/core-fast.yml"
grep -Fq 'push:' "$core_fast_workflow" || { echo "core-fast-missing-push-trigger" >&2; exit 1; }
if grep -Fq 'branches-ignore: [main]' "$core_fast_workflow"; then
  echo "core-fast-main-push-disabled" >&2
  exit 1
fi
grep -Fq '".github/workflows/**"' "$core_fast_workflow" || {
  echo "core-fast-workflow-change-coverage-missing" >&2
  exit 1
}

recovery_workflow=".github/workflows/android-emulator-recovery.yml"
grep -Fq 'push:' "$recovery_workflow" || { echo "emulator-recovery-missing-push-trigger" >&2; exit 1; }
grep -Fq 'branches: [main]' "$recovery_workflow" || { echo "emulator-recovery-main-push-disabled" >&2; exit 1; }

product_gold_workflow=".github/workflows/product-gold.yml"
grep -Fq 'push:' "$product_gold_workflow" || { echo "product-gold-missing-push-trigger" >&2; exit 1; }
grep -Fq 'CANDIDATE_SHA: ${{ github.sha }}' "$product_gold_workflow" || {
  echo "product-gold-candidate-sha-not-bound-to-checkout-ref" >&2
  exit 1
}
grep -Fq 'SOURCE_HEAD_SHA:' "$product_gold_workflow" || {
  echo "product-gold-source-head-sha-not-recorded" >&2
  exit 1
}
grep -Fq 'offline_image_artifact_e2e=PASS' "$product_gold_workflow" || {
  echo "product-gold-image-e2e-not-sealed" >&2
  exit 1
}
grep -Fq 'field_snapshot_atomic_recovery=PASS' "$product_gold_workflow" || {
  echo "product-gold-field-snapshot-recovery-not-sealed" >&2
  exit 1
}
for seal in \
  'semantic_action_recovery=PASS' \
  'semantic_reference_revision_recovery=PASS' \
  'external_effect_no_duplicate=PASS' \
  'language_gold_device=PASS'; do
  grep -Fq "$seal" "$product_gold_workflow" || {
    echo "product-gold-semantic-device-seal-missing:$seal" >&2
    exit 1
  }
done

product_gold_script=".github/scripts/ci-product-gold.sh"
grep -Fq 'candidate-sha-checkout-mismatch' "$product_gold_script" || {
  echo "product-gold-checkout-sha-not-verified" >&2
  exit 1
}
grep -Fq 'source_head_sha=' "$product_gold_script" || {
  echo "product-gold-source-head-evidence-missing" >&2
  exit 1
}
for seal in \
  'language_semantic_gold=PASS' \
  'semantic_action_router=PASS' \
  'semantic_execution_gate=PASS' \
  'revision_reference_binding=PASS' \
  'bounded_language_retrieval=PASS' \
  'goal_v4_restart_parity=PASS' \
  'linguistic_index_bounded=PASS' \
  'domain_semantic_packs=PASS' \
  'no_external_side_effect_without_executable_semantic_action=PASS'; do
  grep -Fq "$seal" "$product_gold_script" || {
    echo "product-gold-semantic-pre-emulator-seal-missing:$seal" >&2
    exit 1
  }
done

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