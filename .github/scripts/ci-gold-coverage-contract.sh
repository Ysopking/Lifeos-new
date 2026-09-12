#!/usr/bin/env bash
set -Eeuo pipefail

required_jvm_tests=(
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/capability/BoundedGeneratedToolBootRecoveryTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/capability/DurableToolWorkshopOwnerRevocationTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/capability/HotSwapRoutingRecoveryTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/deepsearch/DeepSearchMissionRecoveryAuditTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/resource/HardwareAdaptiveResourceIntelligenceTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/resource/ResourceIntelligenceCompletionTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/resource/WorldFormulaBudgetBrokerTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/trace/SubsystemDecisionTraceRecorderTest.kt"
  "core/runtime/src/test/kotlin/app/lifeos/core/runtime/hardening/V17HardeningEvidenceTest.kt"
  "app/src/test/java/app/lifeos/next/OutcomeOverviewTest.kt"
)

required_device_tests=(
  "app/src/androidTest/java/app/lifeos/next/OwnerPolicyAssetWriteDeviceTest.kt"
  "app/src/androidTest/java/app/lifeos/next/GoalPlanRecoveryDeviceTest.kt"
  "app/src/androidTest/java/app/lifeos/next/OutcomeLearningDeviceTest.kt"
  "app/src/androidTest/java/app/lifeos/next/ConvergenceDecisionDeviceTest.kt"
  "app/src/androidTest/java/app/lifeos/next/DeepSearchEncryptedRepositoryCorruptionDeviceTest.kt"
)

for path in "${required_jvm_tests[@]}" "${required_device_tests[@]}"; do
  test -s "$path" || { echo "missing-gold-test:$path" >&2; exit 1; }
done

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
  'DeepSearchEncryptedRepositoryCorruptionDeviceTest'; do
  grep -Fq "$suite" "$emulator_gate" || { echo "missing-emulator-gold-suite:$suite" >&2; exit 1; }
done

grep -Fq 'ci-core-fast.sh' .github/scripts/ci-v17-gold.sh
grep -Fq 'ci-android-debug.sh' .github/scripts/ci-v17-gold.sh
grep -Fq 'ci-emulator-preflight.sh' .github/scripts/ci-v17-gold.sh

echo "V17_GOLD_COVERAGE_CONTRACT_OK"
