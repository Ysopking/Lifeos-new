#!/usr/bin/env bash
set -Eeuo pipefail

workflow_dir=".github/workflows"

if [[ ! -d "$workflow_dir" ]]; then
  echo "missing-workflow-directory:$workflow_dir" >&2
  exit 1
fi

found_external=0
failure=0

while IFS= read -r workflow; do
  while IFS= read -r line; do
    usage="$(sed -E 's/^[[:space:]]*-[[:space:]]*uses:[[:space:]]*([^[:space:]#]+).*/\1/' <<< "$line")"

    # Repository-local actions are bound to the checked-out LIFEOS candidate SHA.
    if [[ "$usage" == ./* ]]; then
      continue
    fi

    found_external=1
    if [[ "$usage" != *@* ]]; then
      echo "external-action-ref-missing:$workflow:$usage" >&2
      failure=1
      continue
    fi

    ref="${usage##*@}"
    if [[ ! "$ref" =~ ^[0-9a-f]{40}$ ]]; then
      echo "mutable-action-ref:$workflow:$usage" >&2
      failure=1
    fi
  done < <(grep -E '^[[:space:]]*-[[:space:]]*uses:[[:space:]]*[^[:space:]#]+' "$workflow" || true)
done < <(find "$workflow_dir" -maxdepth 1 -type f \( -name '*.yml' -o -name '*.yaml' \) -print | LC_ALL=C sort)

if [[ "$found_external" -ne 1 ]]; then
  echo "no-external-actions-found" >&2
  exit 1
fi

if [[ "$failure" -ne 0 ]]; then
  exit 1
fi

echo "CI_ACTION_PIN_CONTRACT_OK"
