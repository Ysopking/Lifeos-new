#!/usr/bin/env bash
set -Eeuo pipefail

expected="${EXPECTED_HEAD_SHA:-}"
if [[ ! "$expected" =~ ^[0-9a-f]{40}$ ]]; then
  echo "exact-head-expected-sha-invalid:$expected" >&2
  exit 1
fi

actual="$(git rev-parse HEAD)"
if [[ "$actual" != "$expected" ]]; then
  echo "exact-head-mismatch:expected=$expected actual=$actual" >&2
  exit 1
fi

if [[ -n "$(git status --porcelain --untracked-files=all)" ]]; then
  echo "exact-head-worktree-not-clean-before-gates" >&2
  git status --short >&2
  exit 1
fi

base="${EXPECTED_BASE_SHA:-}"
if [[ -n "$base" ]]; then
  if [[ ! "$base" =~ ^[0-9a-f]{40}$ ]]; then
    echo "exact-head-base-sha-invalid:$base" >&2
    exit 1
  fi
  git cat-file -e "$base^{commit}" 2>/dev/null || {
    echo "exact-head-base-commit-missing:$base" >&2
    exit 1
  }
  git merge-base --is-ancestor "$base" "$actual" || {
    echo "exact-head-base-is-not-ancestor:base=$base head=$actual" >&2
    exit 1
  }
fi

mkdir -p exact-head-evidence
{
  printf 'contract=lifeos-exact-head/v1\n'
  printf 'expected_head_sha=%s\n' "$expected"
  printf 'checkout_head_sha=%s\n' "$actual"
  printf 'expected_base_sha=%s\n' "$base"
  printf 'event_name=%s\n' "${GITHUB_EVENT_NAME:-local}"
  printf 'ref=%s\n' "${GITHUB_REF:-local}"
  printf 'ref_name=%s\n' "${GITHUB_REF_NAME:-local}"
  printf 'run_id=%s\n' "${GITHUB_RUN_ID:-local}"
  printf 'status=PASS\n'
} > exact-head-evidence/candidate.txt

echo "CI_EXACT_HEAD_CONTRACT_OK:$actual"
