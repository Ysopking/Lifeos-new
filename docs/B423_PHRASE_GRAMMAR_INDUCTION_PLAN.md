# B423 — Phrase-/Grammar Induction

Preparation base: B422 exact head `7fc8cc061ec0319b8340aefb7785f09e0a73b719`

## Goal

Recurring formulations and sentence patterns are abstracted from B421 learning episodes without changing the productive parser.

## Rules

- learning requires at least two independent cycles
- evidence scopes stay isolated: OWNER_LANGUAGE, VERIFIED_GENERAL_LANGUAGE, EXTERNAL_LANGUAGE_OBSERVATION
- owner-language grammar evidence requires explicit owner confirmation/correction
- candidates sharing a surface shape but not the exact semantic-action-graph identity are not merged
- only bounded token-slot abstraction is allowed
- grammar candidate != truth != parser mutation != promotion != execution
- exact duplicate replay is idempotent and order independent

## Existing architecture reused

B423 produces inactive phrase/grammar candidates only. Existing B361 personal-grammar shadow/promotion remains a later authority path; B428/B429 will connect new induced rules to shadow evaluation and promotion/rollback rather than bypassing it.

## Gates

1. `:core:language:test --tests '*PhraseGrammarInductionEngineTest'`
2. `:core:language:test`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold
