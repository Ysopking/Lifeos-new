#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "RUNTIME_MONOLITH_BUDGET_REJECTED:$1" >&2
  exit 1
}

root="core/runtime/src/main/kotlin/app/lifeos/core/runtime"
test -d "$root" || fail "runtime-root-missing"

if find "$root/personal" -type f -name '*.kt' -print -quit 2>/dev/null | grep -q .; then
  fail "personal-package-returned"
fi
if find "$root/deepsearch" -type f -name '*.kt' -print -quit 2>/dev/null | grep -q .; then
  fail "deepsearch-package-returned"
fi

buildstudio_files="$(find "$root/buildstudio" -type f -name '*.kt' 2>/dev/null | LC_ALL=C sort)"
test "$buildstudio_files" = "$root/buildstudio/BuildStudioHostProcessRegistry.kt" ||
  fail "buildstudio-bridge-budget-changed"

total="$(find "$root" -type f -name '*.kt' | wc -l | tr -d ' ')"
test "$total" -eq 505 || fail "runtime-kotlin-file-count:$total"

declare -A expected=(
  ["(root)"]=47
  [agency]=8
  [artifact]=14
  [boot]=18
  [buildstudio]=1
  [capability]=54
  [chat]=2
  [checkpoints]=3
  [codeaudit]=2
  [cognition]=34
  [context]=8
  [convergence]=11
  [escalation]=8
  [evolution]=24
  [execution]=2
  [extension]=14
  [field]=11
  [genesis]=2
  [goal]=19
  [hardening]=1
  [health]=18
  [informationasset]=21
  [learning]=15
  [level7]=24
  [life]=27
  [livedata]=2
  [memory]=4
  [module]=3
  [policy]=3
  [query]=1
  [recovery]=2
  [resource]=12
  [self]=8
  [tasks]=9
  [thought]=11
  [topology]=3
  [trace]=6
  [workers]=8
  [world]=45
)

declare -A actual=()
while IFS= read -r path; do
  rel="${path#"$root"/}"
  if [[ "$rel" == */* ]]; then
    key="${rel%%/*}"
  else
    key="(root)"
  fi
  actual["$key"]=$(( ${actual["$key"]:-0} + 1 ))
done < <(find "$root" -type f -name '*.kt' | LC_ALL=C sort)

for key in "${!actual[@]}"; do
  [[ -v "expected[$key]" ]] || fail "new-runtime-package:$key"
done

for key in "${!expected[@]}"; do
  count="${actual["$key"]:-0}"
  test "$count" -eq "${expected["$key"]}" ||
    fail "package-budget:$key:expected=${expected["$key"]}:actual=$count"
done

echo "RUNTIME_MONOLITH_BUDGET_OK"
