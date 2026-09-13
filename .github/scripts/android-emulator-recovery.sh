#!/bin/sh
set -eu

report_dir="android-emulator-recovery"
app_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
runner="app.lifeos.next.test/androidx.test.runner.AndroidJUnitRunner"

mkdir -p "$report_dir"
test -s "$app_apk"
test -s "$test_apk"
adb install -r "$app_apk"
adb install -r "$test_apk"

run_test() {
  test_name="$1"
  report="$2"
  output="$(adb shell am instrument -w -e class "$test_name" "$runner")"
  printf '%s\n' "$output" | tee "$report"
  printf '%s\n' "$output" | grep -q 'OK (1 test)'
}

run_suite() {
  suite_name="$1"
  expected="$2"
  report="$3"
  output="$(adb shell am instrument -w -e class "$suite_name" "$runner")"
  printf '%s\n' "$output" | tee "$report"
  printf '%s\n' "$output" | grep -q "OK ($expected tests)"
}

run_test \
  'app.lifeos.next.PrivateV1DeviceSmokeTest#seedGeneratedToolAndAssertRuntime' \
  "$report_dir/seed-active.txt"

run_test \
  'app.lifeos.next.ThoughtGraphCompactionDeviceTest#seedCompactedGraphHistory' \
  "$report_dir/seed-graph-compaction.txt"

run_test \
  'app.lifeos.next.ConvergenceDecisionDeviceTest#seedConvergenceDecisionCheckpoint' \
  "$report_dir/seed-convergence-decision.txt"

run_test \
  'app.lifeos.next.OutcomeLearningDeviceTest#seedOutcomeLearningAdaptation' \
  "$report_dir/seed-outcome-learning.txt"

run_test \
  'app.lifeos.next.GoalPlanRecoveryDeviceTest#seedGoalPlanProgress' \
  "$report_dir/seed-goal-plan.txt"

run_test \
  'app.lifeos.next.ProductGoldenChatDeviceTest#seedProductGoldChatRoundTrip' \
  "$report_dir/seed-product-gold-chat.txt"

adb shell am force-stop app.lifeos.next
cold_start="$(adb shell am start -W -n app.lifeos.next/.ChatMainActivity)"
printf '%s\n' "$cold_start" | tee "$report_dir/cold-start.txt"
pid="$(adb shell pidof app.lifeos.next | tr -d '\r')"
test -n "$pid"
activities="$(adb shell dumpsys activity activities)"
printf '%s\n' "$activities" | grep -q 'app.lifeos.next/.ChatMainActivity'

run_test \
  'app.lifeos.next.ProductGoldenChatDeviceTest#recoverProductGoldChatRoundTrip' \
  "$report_dir/recovered-product-gold-chat.txt"

run_test \
  'app.lifeos.next.ThoughtGraphCompactionDeviceTest#recoverCompactedGraphHistory' \
  "$report_dir/recovered-graph-compaction.txt"

run_test \
  'app.lifeos.next.ConvergenceDecisionDeviceTest#recoverConvergenceDecisionCheckpoint' \
  "$report_dir/recovered-convergence-decision.txt"

run_test \
  'app.lifeos.next.OutcomeLearningDeviceTest#recoverOutcomeLearningAdaptationAfterColdStart' \
  "$report_dir/recovered-outcome-learning.txt"

run_test \
  'app.lifeos.next.PrivateV1DeviceSmokeTest#assertRecoveredRuntimeAndToolEvidence' \
  "$report_dir/recovered-active.txt"

run_test \
  'app.lifeos.next.GoalPlanRecoveryDeviceTest#recoverGoalPlanAfterColdStart' \
  "$report_dir/recovered-goal-plan.txt"

# B01 persistent universal-field hardening. Exercise the real AndroidKeyStore + AtomicFile repository
# against both explicit rollback and an interrupted write recovered by a fresh repository instance.
run_suite \
  'app.lifeos.next.FieldSnapshotAtomicRecoveryDeviceTest' \
  '2' \
  "$report_dir/field-snapshot-atomic-recovery.txt"

# V17 IMAGE artifact closure. Run after established cold-restart assertions so the new generated
# Photons cannot perturb their seed/recovery baseline, but before the owner-policy test deliberately
# revokes asset-write authority.
run_test \
  'app.lifeos.next.OfflineImageArtifactDeviceTest#promptRendersPngReloadsAndEntersArtifactLifecycle' \
  "$report_dir/offline-image-artifact-e2e.txt"

run_test \
  'app.lifeos.next.OwnerPolicyAssetWriteDeviceTest#assetWriteIsAllowedThenFailsClosedAfterRevokeAcrossFreshStore' \
  "$report_dir/owner-policy-asset-revoke-restart.txt"

run_suite \
  'app.lifeos.next.DeepSearchEncryptedRepositoryCorruptionDeviceTest' \
  '2' \
  "$report_dir/deepsearch-encrypted-recovery-corruption.txt"
