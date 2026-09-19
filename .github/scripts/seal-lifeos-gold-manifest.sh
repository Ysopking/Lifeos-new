#!/usr/bin/env bash
set -Eeuo pipefail

evidence_dir="${1:-product-gold-evidence}"
ancestor_contract=".github/lifeos-stack/final-ancestor-contract.txt"
apk_hash_file="$evidence_dir/app-debug.sha256"
candidate_file="$evidence_dir/candidate.txt"
scale_file="$evidence_dir/scale-runtime.txt"
gates_file="$evidence_dir/gates.txt"
manifest="$evidence_dir/lifeos-gold-manifest.txt"

for required in "$ancestor_contract" "$apk_hash_file" "$candidate_file" "$scale_file" "$gates_file"; do
  test -s "$required" || {
    echo "final-gold-required-evidence-missing:$required" >&2
    exit 1
  }
done

final_sha="$(git rev-parse HEAD)"
parent_sha="$(git rev-parse HEAD^)"
candidate_sha="$(sed -n 's/^candidate_sha=//p' "$candidate_file" | tail -n 1)"
checkout_sha="$(sed -n 's/^checkout_sha=//p' "$candidate_file" | tail -n 1)"
apk_sha="$(awk 'NR==1 { print $1 }' "$apk_hash_file")"
scale_fingerprint="$(sed -n 's/^corpus_fingerprint=//p' "$scale_file" | tail -n 1)"

for value in "$final_sha" "$parent_sha" "$candidate_sha" "$checkout_sha" "$apk_sha" "$scale_fingerprint"; do
  [[ "$value" =~ ^[0-9a-f]{40}$ || "$value" =~ ^[0-9a-f]{64}$ ]] || {
    echo "final-gold-invalid-hash:$value" >&2
    exit 1
  }
done

test "$candidate_sha" = "$final_sha" || {
  echo "final-gold-candidate-head-mismatch" >&2
  exit 1
}
test "$checkout_sha" = "$final_sha" || {
  echo "final-gold-checkout-head-mismatch" >&2
  exit 1
}
grep -q '^product_gold=PASS$' "$gates_file" || {
  echo "final-gold-product-gold-seal-missing" >&2
  exit 1
}

while IFS='=' read -r milestone sha; do
  test -n "$milestone" || continue
  [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || {
    echo "final-gold-invalid-ancestor:$milestone:$sha" >&2
    exit 1
  }
  git merge-base --is-ancestor "$sha" "$final_sha" || {
    echo "final-gold-ancestor-missing:$milestone:$sha" >&2
    exit 1
  }
done < "$ancestor_contract"

{
  printf 'contract=lifeos-final-gold/v1\n'
  printf 'final_sha=%s\n' "$final_sha"
  printf 'parent_milestone_sha=%s\n' "$parent_sha"
  printf 'apk_sha256=%s\n' "$apk_sha"
  printf 'scale_corpus_fingerprint=%s\n' "$scale_fingerprint"
  printf 'exact_head_contract=PASS\n'
  while IFS='=' read -r milestone sha; do
    test -n "$milestone" || continue
    printf 'ancestor.%s=%s\n' "$milestone" "$sha"
  done < "$ancestor_contract"
  while IFS='=' read -r gate state; do
    test -n "$gate" || continue
    test "$state" = "PASS" || {
      echo "final-gold-nonpass-gate:$gate=$state" >&2
      exit 1
    }
    printf 'gate.%s=%s\n' "$gate" "$state"
  done < "$gates_file"
} > "$manifest"

test -s "$manifest"
printf 'LIFEOS_FINAL_GOLD_MANIFEST_OK:%s\n' "$final_sha"
