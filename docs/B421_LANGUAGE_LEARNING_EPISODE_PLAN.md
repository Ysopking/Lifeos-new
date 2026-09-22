# B421 — LanguageLearningEpisode

Base: B420 exact stack.

## Purpose

B421 connects one exact language interpretation with later action/outcome evidence and explicit owner feedback without directly changing the language model.

The episode is the durable learning boundary for B422–B430.

## Stored evidence

- hashed utterance identity, not raw utterance text
- language code and interpreted intent
- hashed objective
- exact SemanticActionGraph fingerprint
- interpretation confidence
- optional exact action fingerprint
- optional exact outcome fingerprint
- optional explicit owner-confirmed feedback
- source cycle id
- deterministic episode fingerprint

## Episode states

- UNVERIFIED
- VERIFIED_OUTCOME
- OWNER_CONFIRMED
- OWNER_CORRECTED
- OWNER_REJECTED

## Hard invariants

- passive behavior is not owner language feedback
- owner correction binds an exact replacement interpretation fingerprint
- verified action learning requires both action and outcome evidence
- learning episode != truth
- learning episode != lexical promotion
- learning episode != grammar promotion
- learning episode != execution authority

## B422 handoff

B422 may induce lexical candidates only from learning-eligible B421 episodes and must keep candidate knowledge separate from promoted owner-language knowledge.
