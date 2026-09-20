#!/usr/bin/env bash
set -Eeuo pipefail

ruleset=".github/rulesets/main-protection.json"

test -s "$ruleset" || {
  echo "main-authority-ruleset-missing:$ruleset" >&2
  exit 1
}

python3 - "$ruleset" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
try:
    data = json.loads(path.read_text(encoding="utf-8"))
except Exception as error:
    raise SystemExit(f"main-authority-ruleset-invalid-json:{error}")

expected_checks = [
    "Core Fast Gate",
    "Android Debug CI",
    "Android Emulator Recovery",
    "LIFEOS Product Gold",
]

if data.get("name") != "lifeos-main-gold":
    raise SystemExit("main-authority-ruleset-name-mismatch")
if data.get("target") != "branch":
    raise SystemExit("main-authority-target-must-be-branch")
if data.get("enforcement") != "active":
    raise SystemExit("main-authority-ruleset-must-be-active")

conditions = data.get("conditions") or {}
ref_name = conditions.get("ref_name") or {}
includes = ref_name.get("include") or []
excludes = ref_name.get("exclude") or []
if includes != ["refs/heads/main"]:
    raise SystemExit("main-authority-target-ref-must-be-exact-main")
if excludes:
    raise SystemExit("main-authority-main-must-not-be-excluded")

rules = data.get("rules")
if not isinstance(rules, list):
    raise SystemExit("main-authority-rules-missing")

types = [rule.get("type") for rule in rules if isinstance(rule, dict)]
for required_type in ("deletion", "non_fast_forward", "pull_request", "required_status_checks"):
    if types.count(required_type) != 1:
        raise SystemExit(f"main-authority-rule-count-invalid:{required_type}")

pull_request = next(rule for rule in rules if rule.get("type") == "pull_request")
pull_params = pull_request.get("parameters") or {}
if pull_params.get("required_review_thread_resolution") is not True:
    raise SystemExit("main-authority-review-thread-resolution-required")
if pull_params.get("dismiss_stale_reviews_on_push") is not True:
    raise SystemExit("main-authority-stale-review-dismissal-required")

status_rule = next(rule for rule in rules if rule.get("type") == "required_status_checks")
status_params = status_rule.get("parameters") or {}
if status_params.get("strict_required_status_checks_policy") is not True:
    raise SystemExit("main-authority-strict-status-policy-required")
if status_params.get("do_not_enforce_on_create") is not False:
    raise SystemExit("main-authority-status-checks-must-enforce-on-create")

checks = status_params.get("required_status_checks")
if not isinstance(checks, list):
    raise SystemExit("main-authority-required-status-checks-missing")
contexts = [check.get("context") for check in checks if isinstance(check, dict)]
if contexts != expected_checks:
    raise SystemExit(
        "main-authority-required-status-checks-mismatch:" +
        ",".join(str(context) for context in contexts)
    )
if len(contexts) != len(set(contexts)):
    raise SystemExit("main-authority-required-status-checks-duplicated")

if data.get("bypass_actors") != []:
    raise SystemExit("main-authority-bypass-actors-must-be-empty")

print("MAIN_AUTHORITY_RULESET_JSON_OK")
PY

declare -A workflows=(
  [".github/workflows/core-fast.yml"]="Core Fast Gate"
  [".github/workflows/android.yml"]="Android Debug CI"
  [".github/workflows/android-emulator-recovery.yml"]="Android Emulator Recovery"
  [".github/workflows/product-gold.yml"]="LIFEOS Product Gold"
)

for workflow in "${!workflows[@]}"; do
  expected="${workflows[$workflow]}"
  test -s "$workflow" || {
    echo "main-authority-workflow-missing:$workflow" >&2
    exit 1
  }
  count="$(grep -Ec "^[[:space:]]+name:[[:space:]]+${expected// /[[:space:]]}[[:space:]]*$" "$workflow" || true)"
  if [[ "$count" -ne 1 ]]; then
    echo "main-authority-stable-job-name-mismatch:$workflow:$expected:$count" >&2
    exit 1
  fi
done

python3 - "$ruleset" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
include = data["conditions"]["ref_name"]["include"]
if "refs/heads/main" not in include:
    raise SystemExit("main-authority-main-ref-not-protected")
print("MAIN_AUTHORITY_MAIN_REF_OK")
PY

echo "MAIN_AUTHORITY_CONTRACT_OK"
