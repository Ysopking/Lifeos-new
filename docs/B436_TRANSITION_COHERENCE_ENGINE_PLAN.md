# B436 — Transition / Coherence Engine

Preparation base: B435 exact stack

## Goal

Add presentation-only paragraph transitions while preserving exact B435 source paragraph text and evidence-bounded B433 relations.

## Invariants

- first paragraph receives no transition
- without semantic relation evidence only a presentation SEQUENCE connector is allowed
- SUPPORT / ELABORATION / CONTRAST transitions require exact B433 relation evidence crossing the paragraph boundary
- B435 source paragraph text is never rewritten
- transition text does not add factual claims
- no citation or artifact-finalization authority

## B437 handoff

B437 binds claim/sentence/paragraph positions to exact evidence references. It may add citation markers but cannot modify claims or factual text.

## Gates

1. `:core:creative:test --tests '*TransitionCoherenceEngineTest'`
2. `:core:creative:test`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold
