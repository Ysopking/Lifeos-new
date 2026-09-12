#!/usr/bin/env bash
set -Eeuo pipefail

step() {
  printf '\n==> %s\n' "$1"
}

evidence_dir="product-gold-evidence"
apk_path="app/build/outputs/apk/debug/app-debug.apk"
mkdir -p "$evidence_dir"

step "Product Gold 01: private security invariants"
bash .github/scripts/ci-private-debug-security.sh

step "Product Gold 02: mandatory coverage contract"
bash .github/scripts/ci-gold-coverage-contract.sh

step "Product Gold 03: complete core regression set"
bash .github/scripts/ci-core-fast.sh

step "Product Gold 04: unit tests, lint and debug APK"
bash .github/scripts/ci-android-debug.sh

step "Product Gold 05: AndroidTest and emulator preflight"
bash .github/scripts/ci-emulator-preflight.sh

step "Product Gold 06: integration contract presence"
test -f core/runtime/src/main/kotlin/app/lifeos/core/runtime/topology/LifeOsRuntimeBindings.kt
test -f core/runtime/src/main/kotlin/app/lifeos/core/runtime/topology/LifeOsRuntimeTopology.kt
test -f app/src/androidTest/java/app/lifeos/next/ProductGoldenChatDeviceTest.kt
grep -q 'ProductGoldenChatDeviceTest#seedProductGoldChatRoundTrip' .github/scripts/android-emulator-recovery.sh
grep -q 'ProductGoldenChatDeviceTest#recoverProductGoldChatRoundTrip' .github/scripts/android-emulator-recovery.sh
grep -q 'ChatMainActivity' app/src/main/AndroidManifest.xml

step "Product Gold 07: immutable candidate evidence"
test -s "$apk_path"
sha256sum "$apk_path" | tee "$evidence_dir/app-debug.sha256"
printf 'candidate_sha=%s\n' "${CANDIDATE_SHA:-$(git rev-parse HEAD)}" | tee "$evidence_dir/candidate.txt"
printf 'workflow_run_id=%s\n' "${GOLD_RUN_ID:-local}" | tee -a "$evidence_dir/candidate.txt"
printf 'workflow_job=%s\n' "${GOLD_JOB:-local}" | tee -a "$evidence_dir/candidate.txt"
printf 'product_gold_requires_emulator=true\n' | tee -a "$evidence_dir/candidate.txt"
printf '%s\n' \
  'security_static=PASS' \
  'coverage_contract=PASS' \
  'core_fast=PASS' \
  'unit_tests=PASS' \
  'lint_debug=PASS' \
  'assemble_debug=PASS' \
  'assemble_debug_android_test=PASS' \
  'integration_contract=PASS' > "$evidence_dir/gates.txt"

step "Product Gold pre-emulator matrix complete"
