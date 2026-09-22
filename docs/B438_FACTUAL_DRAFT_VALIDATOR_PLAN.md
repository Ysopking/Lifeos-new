# B438 — Factual Draft Validator

Preparation base: B437 exact stack

## Goal

Reject a draft unless every factual carrier remains closed over resolved SemanticArtifactClaims and exact B437 evidence bindings.

## Validation boundary

B438 verifies lineage and closure, not external truth:
- exact SemanticArtifactPlan / B433 / B436 / B437 fingerprints
- no unresolved or foreign claims
- one exact B434 sentence per argument step
- claim fingerprint and Photon evidence equality
- one exact B437 citation binding per claim
- lossless B435 paragraph composition
- unchanged B436 source paragraph text

## Invariant

`validated lineage != external truth authority`

B438 creates no claims and grants no finalization authority.

## Gates

1. `:core:creative:test --tests '*FactualDraftValidatorTest'`
2. `:core:creative:test`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold
