# B435 — Paragraph Composition Engine

Preparation base: B434 exact stack

## Goal

Compose exact B434 sentence carriers into section-bounded paragraphs without rewriting factual language.

## Invariants

- exactly one B434 sentence per B433 argument step
- sentence inputs outside the argument plan fail closed
- paragraph order follows B432 section order and B433 step order
- paragraph text is exactly ordered sentence text joined by one space
- no sentence mutation
- no factual additions
- no claim creation or citation binding
- B436 owns transitions/coherence

## Gates

1. `:core:creative:test --tests '*ParagraphCompositionEngineTest'`
2. `:core:creative:test`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold
