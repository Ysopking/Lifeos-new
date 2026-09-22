# B422 — Lexical Self-Learning

Preparation base: B421 exact head `c12ed91b94e47f1864c1a28ee3f4265aa74fe9ee`

## Goal

Create bounded lexical learning candidates for new words, variants and meanings while preserving the separation:

`OWNER_LANGUAGE != VERIFIED_GENERAL_LANGUAGE != EXTERNAL_LANGUAGE_OBSERVATION`

## Rules

- OWNER_LANGUAGE requires explicit owner confirmation or correction.
- VERIFIED_GENERAL_LANGUAGE requires repeated independent verified usage.
- EXTERNAL_LANGUAGE_OBSERVATION never becomes owner language by passive observation.
- candidates do not mutate the active parser/lexicon.
- lexical evidence is not truth, grammar authority, promotion authority or execution authority.
- exact duplicate replay is idempotent and ordering deterministic.

## Gates

1. `:core:language:test --tests '*LexicalSelfLearningEngineTest'`
2. `:core:language:test`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold
