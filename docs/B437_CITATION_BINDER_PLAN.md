# B437 — Citation Binder

Preparation base: B436 exact stack

## Goal

Bind every realized claim to the exact Photon revision evidence already approved by the SemanticArtifactPlan.

## Invariants

- no source search or source invention
- every B433 claim must have exactly one binding
- binding chain is exact: claim -> B434 sentence -> B435 paragraph -> Photon revision evidence
- B434 sentence evidence must equal SemanticArtifactClaim evidence
- unresolved claims fail closed
- citation binding does not mutate claims, evidence or draft text
- formatting citations is separate from selecting evidence

## B438 handoff

B438 validates that every factual draft carrier is covered by an exact resolved claim and B437 citation binding before a draft can proceed.

## Gates

1. `:core:creative:test --tests '*CitationBinderTest'`
2. `:core:creative:test`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold
