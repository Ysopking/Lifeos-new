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

assert_cold_launcher() {
  cold_start="$1"
  printf '%s\n' "$cold_start" | grep -q 'Status: ok'
  printf '%s\n' "$cold_start" | grep -q 'LaunchState: COLD'

  pid=""
  attempt=0
  while [ -z "$pid" ] && [ "$attempt" -lt 20 ]; do
    pid="$(adb shell pidof app.lifeos.next 2>/dev/null | tr -d '\r' || true)"
    [ -n "$pid" ] || sleep 0.25
    attempt=$((attempt + 1))
  done
  printf 'pid=%s\n' "$pid" | tee "$report_dir/cold-start-process.txt"
  test -n "$pid"

  activities="$(adb shell dumpsys activity activities)"
  printf '%s\n' "$activities" > "$report_dir/cold-start-activities.txt"
  top_activity="$(printf '%s\n' "$activities" | grep -m1 -E 'topResumedActivity=|ResumedActivity:' || true)"

  if printf '%s\n' "$top_activity" | grep -Eq 'app\.lifeos\.next/(\.|app\.lifeos\.next\.)ChatMainActivity'; then
    printf 'launcher_state=lifeos-activity-visible\n' | tee "$report_dir/cold-start-overlay.txt"
    return 0
  fi

  # On the first legitimate launch ChatMainActivity may immediately delegate foreground focus to
  # Android's runtime-permission controller while the LIFEOS process stays alive. That is a valid
  # launcher outcome, not a process crash. Accept only this explicit system overlay, linked back to
  # ChatMainActivity as its result target; every other foreground replacement still fails closed.
  if printf '%s\n' "$cold_start" | grep -q 'com.android.permissioncontroller/.permission.ui.GrantPermissionsActivity' && \
     printf '%s\n' "$top_activity" | grep -q 'com.android.permissioncontroller/.permission.ui.GrantPermissionsActivity' && \
     printf '%s\n' "$activities" | grep -Eq 'resultTo=.*app\.lifeos\.next/(\.|app\.lifeos\.next\.)ChatMainActivity'; then
    printf 'launcher_state=lifeos-alive-with-system-permission-overlay\n' | tee "$report_dir/cold-start-overlay.txt"
    return 0
  fi

  printf 'launcher_state=unexpected-foreground\n' | tee "$report_dir/cold-start-overlay.txt"
  return 1
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

# F6 Goals workspace. Project the exact productive ledger fixture before process death and prove the
# UI read model does not mutate durable goal progress.
run_test \
  'app.lifeos.next.GoalsWorkspaceDeviceTest#productiveLedgerProjectsWithoutGoalMutation' \
  "$report_dir/goals-workspace-read-only.txt"

run_test \
  'app.lifeos.next.ProductGoldenChatDeviceTest#seedProductGoldChatRoundTrip' \
  "$report_dir/seed-product-gold-chat.txt"

adb shell am force-stop app.lifeos.next
cold_start="$(adb shell am start -W -n app.lifeos.next/.ChatMainActivity)"
printf '%s\n' "$cold_start" | tee "$report_dir/cold-start.txt"
assert_cold_launcher "$cold_start"

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

run_test \
  'app.lifeos.next.GoalsWorkspaceDeviceTest#recoveredGoalWorkspaceMatchesDurableLedgerAfterColdStart' \
  "$report_dir/recovered-goals-workspace.txt"

# F5 Memory workspace. Read the productive DurableLifeMemoryRuntime snapshot and prove that the UI
# projection neither rebuilds the memory runtime nor writes Photon state.
run_test \
  'app.lifeos.next.MemoryWorkspaceDeviceTest#productiveSnapshotProjectsWithoutMemoryMutation' \
  "$report_dir/memory-workspace-read-only.txt"

# B01 persistent universal-field hardening. Exercise the real AndroidKeyStore + AtomicFile repository
# against both explicit rollback and an interrupted write recovered by a fresh repository instance.
run_suite \
  'app.lifeos.next.FieldSnapshotAtomicRecoveryDeviceTest' \
  '2' \
  "$report_dir/field-snapshot-atomic-recovery.txt"

# F5A owner-reviewed IMAGE artifact closure. Run after established cold-restart assertions so the
# new generated Photons cannot perturb their seed/recovery baseline, but before the owner-policy
# test deliberately revokes asset-write authority.
run_test \
  'app.lifeos.next.OfflineImageArtifactDeviceTest#promptRendersPngButPublishesOnlyAfterExactOwnerApproval' \
  "$report_dir/offline-image-artifact-e2e.txt"

run_test \
  'app.lifeos.next.OwnerPolicyAssetWriteDeviceTest#assetWriteIsAllowedThenFailsClosedAfterRevokeAcrossFreshStore' \
  "$report_dir/owner-policy-asset-revoke-restart.txt"

run_suite \
  'app.lifeos.next.DeepSearchEncryptedRepositoryCorruptionDeviceTest' \
  '2' \
  "$report_dir/deepsearch-encrypted-recovery-corruption.txt"
