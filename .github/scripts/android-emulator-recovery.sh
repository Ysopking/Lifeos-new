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

run_test \
  'app.lifeos.next.PrivateV1DeviceSmokeTest#seedGeneratedToolAndAssertRuntime' \
  "$report_dir/seed.txt"

adb shell am force-stop app.lifeos.next
cold_start="$(adb shell am start -W -n app.lifeos.next/.MainActivity)"
printf '%s\n' "$cold_start" | tee "$report_dir/cold-start.txt"
pid="$(adb shell pidof app.lifeos.next | tr -d '\r')"
test -n "$pid"
activities="$(adb shell dumpsys activity activities)"
printf '%s\n' "$activities" | grep -q 'app.lifeos.next/.MainActivity'

run_test \
  'app.lifeos.next.PrivateV1DeviceSmokeTest#assertRecoveredRuntimeAndToolEvidence' \
  "$report_dir/recovery.txt"
