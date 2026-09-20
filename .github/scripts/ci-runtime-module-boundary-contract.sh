#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "RUNTIME_MODULE_BOUNDARY_REJECTED:$1" >&2
  exit 1
}

test -f core/runtime-personal/build.gradle.kts ||
  fail "runtime-personal-module-missing"

if find core/runtime/src -type f -path '*/app/lifeos/core/runtime/personal/*' -print -quit | grep -q .; then
  fail "personal-sources-remain-in-runtime-monolith"
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

main_count="$(find core/runtime-personal/src/main/kotlin -type f -name '*.kt' | wc -l | tr -d ' ')"
test_count="$(find core/runtime-personal/src/test/kotlin -type f -name '*.kt' | wc -l | tr -d ' ')"

test "$main_count" -eq 5 ||
  fail "runtime-personal-main-source-count:$main_count"

test "$test_count" -eq 7 ||
  fail "runtime-personal-test-source-count:$test_count"

grep -Fq ':core:runtime-personal:test' .github/scripts/ci-core-fast.sh ||
  fail "runtime-personal-test-gate-missing"

echo "RUNTIME_PERSONAL_MODULE_BOUNDARY_OK"
