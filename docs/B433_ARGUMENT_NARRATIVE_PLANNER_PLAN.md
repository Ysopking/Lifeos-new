# B433 — Argument / Narrative Planner

Preparation base: B432 exact stack

## Goal

Create an explicit presentation and argument plan over the exact B432 claim structure without inventing factual relations.

## Invariants

- exact B431/B432/SemanticArtifactPlan fingerprint binding
- every required claim appears exactly once as an argument step
- adjacent claims may receive presentation-only SEQUENCE relations
- SUPPORTS, ELABORATES and CONTRASTS require exact supplied relation evidence
- foreign claims are rejected
- argument planning creates no claims and no prose
- rhetorical ordering is not factual authority

## B434 handoff

B434 realizes one exact argument step and its bound SemanticArtifactClaim into language. It may phrase the claim but may not add factual content.

## Gates

1. `:core:model:test --tests '*DocumentArgumentPlannerTest'`
2. `:core:model:test`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold
