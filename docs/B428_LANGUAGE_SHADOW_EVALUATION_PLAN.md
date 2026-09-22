# B428 — Language Shadow Evaluation

Preparation base: B427 exact stack

## Goal

Evaluate learned language candidates against target and protected cases before any productive promotion.

## Required invariants

- at least one target case and at least one protected regression case
- target cases must resolve to their expected intent and optional exact SemanticActionGraph fingerprint
- protected cases must preserve intent, SemanticActionGraph fingerprint and executable external-effect status
- a candidate may never introduce a new executable external side effect in shadow
- report != promotion authority != rollback authority != execution
- productive language runtime is never mutated by B428

## Existing architecture reused

B361 already established the personal-language shadow principle. B428 generalizes that evidence contract for B422–B427 candidate families. B429 consumes only passed B428 reports for controlled promotion/rollback.

## Gates

1. `:core:language:test --tests '*LanguageShadowEvaluatorTest'`
2. `:core:language:test`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold
