# B431 — DocumentGoal

Preparation base: B430 exact stack

## Goal

Bind document purpose, audience, language, output format and depth to an exact closed SemanticArtifactPlan before structure or prose generation begins.

## Invariants

- only TEXT/PDF semantic plans may seed a DocumentGoal
- every required claim id must exist and be resolved in the exact source plan
- unresolved claims cannot become document requirements
- goal creation does not create claims, citations, factual authority or finalization authority
- audience/language/format/depth are presentation intent, never evidence
- ordering and fingerprinting are deterministic

## Pipeline

`SemanticArtifactPlan -> DocumentGoal -> B432 Structure -> B433 Argument Plan -> Draft`

## Gates

1. `:core:model:test --tests '*DocumentGoalTest'`
2. `:core:model:test`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold
