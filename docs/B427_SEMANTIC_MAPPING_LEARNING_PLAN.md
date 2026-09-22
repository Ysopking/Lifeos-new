# B427 — Semantic Mapping Learning

Preparation base: B426 exact stack

## Goal

Learn bounded candidate mappings from recurring formulations/cues to exact goal and SemanticActionGraph identities.

## Rules

- observation binds one exact B421 learning episode
- source candidate fingerprint identifies lexical, phrase/grammar, pragmatic, discourse or reference evidence
- at least two independent cycles are required
- OWNER_LANGUAGE mappings require explicit owner confirmation/correction
- competing mappings for the same source remain explicit
- candidate != productive mapping != goal mutation != action-graph mutation != promotion != execution
- B362 SemanticCorrectionEngine and B363 SemanticActionGraph remain productive architecture

## B428 handoff

B428 may shadow-evaluate exact B427 candidates against protected language cases. No mapping becomes productive before shadow evaluation.

## Gates

1. `:core:language:test --tests '*SemanticMappingLearningEngineTest'`
2. `:core:language:test`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold
