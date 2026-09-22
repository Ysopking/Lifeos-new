# B432 — Document Structure Planner

Preparation base: B431 exact stack

## Goal

Convert an exact B431 DocumentGoal into a deterministic claim-bounded section structure.

## Invariants

- exact B431 goal to SemanticArtifactPlan fingerprint binding
- only resolved required claims may enter the structure
- every required claim is assigned exactly once
- section count is bounded deterministically by DocumentDepth
- structure planning creates no new claims and no prose
- section role is structural metadata, not a rendered heading
- B433 may add argument relations but may not change claim coverage

## Gates

1. `:core:model:test --tests '*DocumentStructurePlannerTest'`
2. `:core:model:test`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold
