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
test -f app/src/androidTest/java/app/lifeos/next/LanguageRuntimeRecoveryDeviceTest.kt
test -f app/src/androidTest/java/app/lifeos/next/PersonalConversationImportDeviceTest.kt
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

step "Product Gold 07: immutable candidate evidence"
test -s "$apk_path"
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
sha256sum "$apk_path" | tee "$evidence_dir/app-debug.sha256"
printf 'candidate_sha=%s\n' "$candidate_sha" | tee "$evidence_dir/candidate.txt"
printf 'source_head_sha=%s\n' "$source_head_sha" | tee -a "$evidence_dir/candidate.txt"
printf 'checkout_sha=%s\n' "$checkout_sha" | tee -a "$evidence_dir/candidate.txt"
printf 'workflow_run_id=%s\n' "${GOLD_RUN_ID:-local}" | tee -a "$evidence_dir/candidate.txt"
printf 'workflow_job=%s\n' "${GOLD_JOB:-local}" | tee -a "$evidence_dir/candidate.txt"
printf 'event_name=%s\n' "${GITHUB_EVENT_NAME:-local}" | tee -a "$evidence_dir/candidate.txt"
printf 'git_ref=%s\n' "${GITHUB_REF:-local}" | tee -a "$evidence_dir/candidate.txt"
printf 'git_ref_name=%s\n' "${GITHUB_REF_NAME:-local}" | tee -a "$evidence_dir/candidate.txt"
printf 'product_gold_requires_emulator=true\n' | tee -a "$evidence_dir/candidate.txt"
printf '%s\n' \
  'security_static=PASS' \
  'coverage_contract=PASS' \
  'core_fast=PASS' \
  'level7_contract_invariants=PASS' \
  'level7_architecture_guards=PASS' \
  'level7_functional_gold_jvm=PASS' \
  'level7_novel_domain_gold=PASS' \
  'level7_transfer_gold=PASS' \
  'level7_truth_closure_gold_preflight=PASS' \
  'self_observation_gold_preflight=PASS' \
  'unit_tests=PASS' \
  'lint_debug=PASS' \
  'assemble_debug=PASS' \
  'assemble_debug_android_test=PASS' \
  'integration_contract=PASS' \
  'language_semantic_gold=PASS' \
  'semantic_action_router=PASS' \
  'semantic_execution_gate=PASS' \
  'revision_reference_binding=PASS' \
  'bounded_language_retrieval=PASS' \
  'goal_v4_restart_parity=PASS' \
  'linguistic_index_bounded=PASS' \
  'domain_semantic_packs=PASS' \
  'no_external_side_effect_without_executable_semantic_action=PASS' \
  'language_runtime_snapshot=PASS' \
  'personal_language_shadow=PASS' \
  'personal_language_feedback=PASS' \
  'personal_corpus_isolation=PASS' \
  'web_source_page_evidence=PASS' \
  'web_contradiction_detection=PASS' \
  'web_source_diversity=PASS' \
  'web_evidence_cache=PASS' \
  'web_private_corpus_non_export=PASS' \
  'personal_conversation_preview_before_write=PASS' \
  'personal_conversation_import_idempotent=PASS' \
  'personal_conversation_owner_boundary=PASS' \
  'gemini_unknown_schema_fail_closed=PASS' \
  'whatsapp_streaming_import=PASS' > "$evidence_dir/gates.txt"

step "Product Gold pre-emulator matrix complete"
