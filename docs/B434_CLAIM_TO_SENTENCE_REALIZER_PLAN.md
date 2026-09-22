# B434 — Claim-to-Sentence Realizer

Preparation base: B433 exact stack

## Goal

Turn one exact resolved claim from the B433 argument plan into a sentence carrier without adding factual content.

## Initial productive realization mode

B434 starts deliberately conservative with `DIRECT` realization:
- collapse whitespace
- preserve existing terminal punctuation
- otherwise add a terminal period
- no paraphrase
- no factual additions
- exact claim fingerprint and Photon revision evidence remain attached

## Invariants

- exact B431/B433/SemanticArtifactPlan binding
- unresolved or foreign claims fail closed
- substituted argument steps fail closed
- sentence != citation
- sentence realization != factual authority
- style/revision work may not erase claim/evidence lineage

## B435 handoff

B435 may compose B434 sentences into paragraphs. It may add formatting boundaries but cannot alter sentence text or claim bindings.

## Gates

1. `:core:creative:test --tests '*ClaimToSentenceRealizerTest'`
2. `:core:creative:test`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold
