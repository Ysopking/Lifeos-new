#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "RUNTIME_MODULE_BOUNDARY_REJECTED:$1" >&2
  exit 1
}

test -f core/runtime-contracts/build.gradle.kts ||
  fail "runtime-contracts-module-missing"

test -f core/runtime-personal/build.gradle.kts ||
  fail "runtime-personal-module-missing"

grep -Fq '":core:runtime-contracts"' settings.gradle.kts ||
  fail "runtime-contracts-not-registered"

grep -Fq 'api(project(":core:runtime-contracts"))' core/runtime/build.gradle.kts ||
  fail "runtime-monolith-contract-edge-missing"

if grep -Fq 'project(":core:runtime")' core/runtime-contracts/build.gradle.kts; then
  fail "runtime-contracts-depends-on-runtime-monolith"
fi

if find core/runtime/src -type f -path '*/app/lifeos/core/runtime/personal/*' -print -quit | grep -q .; then
  fail "personal-sources-remain-in-runtime-monolith"
fi

if test -f core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/CapabilityModels.kt; then
  fail "capability-models-remain-in-runtime-monolith"
fi

grep -Fq '":core:runtime-personal"' settings.gradle.kts ||
  fail "runtime-personal-not-registered"

grep -Fq 'implementation(project(":core:runtime-personal"))' app/build.gradle.kts ||
  fail "app-runtime-personal-dependency-missing"

grep -Fq 'implementation(project(":core:runtime-personal"))' core/data/build.gradle.kts ||
  fail "data-runtime-personal-dependency-missing"

if grep -Fq 'project(":core:runtime")' core/runtime-personal/build.gradle.kts; then
  fail "runtime-personal-depends-on-runtime-monolith"
fi

contracts_main_count="$(find core/runtime-contracts/src/main/kotlin -type f -name '*.kt' | wc -l | tr -d ' ')"
contracts_test_count="$(find core/runtime-contracts/src/test/kotlin -type f -name '*.kt' | wc -l | tr -d ' ')"
personal_main_count="$(find core/runtime-personal/src/main/kotlin -type f -name '*.kt' | wc -l | tr -d ' ')"
personal_test_count="$(find core/runtime-personal/src/test/kotlin -type f -name '*.kt' | wc -l | tr -d ' ')"

test "$contracts_main_count" -eq 1 ||
  fail "runtime-contracts-main-source-count:$contracts_main_count"

test "$contracts_test_count" -eq 1 ||
  fail "runtime-contracts-test-source-count:$contracts_test_count"

test "$personal_main_count" -eq 6 ||
  fail "runtime-personal-main-source-count:$personal_main_count"

test "$personal_test_count" -eq 8 ||
  fail "runtime-personal-test-source-count:$personal_test_count"

grep -Fq ':core:runtime-contracts:test' .github/scripts/ci-core-fast.sh ||
  fail "runtime-contracts-test-gate-missing"

grep -Fq ':core:runtime-personal:test' .github/scripts/ci-core-fast.sh ||
  fail "runtime-personal-test-gate-missing"

echo "RUNTIME_MODULE_BOUNDARY_OK"
