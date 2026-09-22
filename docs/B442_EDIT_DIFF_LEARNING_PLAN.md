# B442 — Edit-Diff Learning — exact implementation plan

Base: B441 exact head `0c3a98e8fcb155e079c5dbacd81c426c0c1406ff`
Branch: `b442-edit-diff-learning-v1`

## Purpose

B442 converts explicit owner edits into bounded learning evidence.

The engine compares exact before/after document revisions, records deterministic structural deltas and emits an owner-approved B441 style sample for the edited result.

## Evidence boundary

Every edit requires:

- exact source artifact fingerprint
- distinct before/after revision fingerprints
- exact owner-confirmation fingerprint
- exact language tag
- non-empty before/after text

Raw before/after prose is not retained by the learning artifact. Exact surface fingerprints and bounded metrics are retained instead.

## Signals

The first bounded signal set includes:

- surface changed
- total text shorter / longer
- average sentence length shorter / longer
- average paragraph length shorter / longer
- fewer / more paragraphs

## Learning handoff

The edited result becomes one `OWNER_APPROVED` B441 style sample.

One edit is never enough to establish a global owner-writing profile: B441 still requires independent evidence from at least two distinct source artifacts.

## Hard boundaries

- edit != global owner preference
- edit != style rule promotion
- edit != rewrite authority
- edit != factual authority
- edit != finalization authority

Authority invariant:

`owner edit evidence != global style rule != preference command != rewrite != final artifact`
