# B424 — Discourse Pattern Learning

Preparation base: B423 exact head `e960956cde84d8e75f4a1f8d96160f3d27cff2f0`

## Goal

Learn recurring follow-up and context-continuation shapes from verified B421 episodes while reusing B351 discourse-state evidence.

## Rules

- concrete before/after discourse fingerprints remain evidence only
- learned unit is a bounded transition shape across at least two independent cycles
- OWNER_LANGUAGE requires explicit owner confirmation/correction
- OWNER_LANGUAGE, VERIFIED_GENERAL_LANGUAGE and EXTERNAL_LANGUAGE_OBSERVATION stay isolated
- context continuation evidence != reference authority
- discourse candidate != context mutation != promotion != execution
- exact duplicate replay is idempotent and ordering deterministic

## B425 handoff

B425 may use exact B424 transition candidates as evidence for personal reference/coreference habits. It must not reinterpret a discourse candidate as a direct reference resolution.

## Gates

1. `:core:language:test --tests '*DiscoursePatternLearningEngineTest'`
2. `:core:language:test`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold
