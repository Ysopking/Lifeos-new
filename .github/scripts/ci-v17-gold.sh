#!/usr/bin/env bash
set -Eeuo pipefail

step() {
  printf '\n==> %s\n' "$1"
}

evidence_dir="v17-gold-evidence"
apk_path="app/build/outputs/apk/debug/app-debug.apk"
mkdir -p "$evidence_dir"

step "V17 gate 01: private debug security invariants"
bash .github/scripts/ci-private-debug-security.sh

step "V17 gate 02: complete core fast regression set"
bash .github/scripts/ci-core-fast.sh

step "V17 gate 03: all unit tests, lint and private debug APK"
bash .github/scripts/ci-android-debug.sh

step "V17 gate 04: emulator recovery preflight"
bash .github/scripts/ci-emulator-preflight.sh

step "V17 gate 05: immutable candidate evidence"
test -f "$apk_path"
sha256sum "$apk_path" | tee "$evidence_dir/app-debug.sha256"
printf 'candidate_sha=%s\n' "${CANDIDATE_SHA:-$(git rev-parse HEAD)}" | tee "$evidence_dir/candidate.txt"
printf 'workflow_run_id=%s\n' "${GOLD_RUN_ID:-local}" | tee -a "$evidence_dir/candidate.txt"
printf 'workflow_job=%s\n' "${GOLD_JOB:-local}" | tee -a "$evidence_dir/candidate.txt"
printf 'release_required=false\n' | tee -a "$evidence_dir/candidate.txt"
printf 'security_static=PASS\ncore_fast=PASS\nunit_tests=PASS\nlint_debug=PASS\nassemble_debug=PASS\nemulator_preflight=PASS\n' > "$evidence_dir/gates.txt"

step "V17 pre-emulator gold matrix complete"
