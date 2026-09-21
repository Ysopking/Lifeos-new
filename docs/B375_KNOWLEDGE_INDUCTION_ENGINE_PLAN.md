# B375 — KnowledgeInductionEngine — exact implementation plan

Base head: `c8a1ed90f6963d0d867e40a0d59f1d9e26da937c`
Branch: `b375-knowledge-induction-engine-v1`
Module: `:core:runtime-reasoning`

## Reuse / non-duplication contract

B375 does not replace existing concept/abstraction induction:
- `StructuralPatternMiner` remains the structural pattern miner.
- `ConceptInductionEngine` remains the structural concept induction engine.
- `AbstractionCandidate` / `ValidatedAbstraction` remain the abstraction validation path.
- B374 `LearningEpisodeState` becomes the exact verified experiential lineage for knowledge induction.

B375 adds a knowledge-candidate layer over repeated verified episodes. A candidate is reviewable evidence, not truth:
`truthAuthority == false`, `promotionAllowed == false`, `activationAllowed == false`.

## Prepared production code

NEW `core/runtime-reasoning/src/main/kotlin/app/lifeos/core/runtime/reasoning/KnowledgeInductionEngine.kt`

- L1–4: package/imports; reuse `StableFieldIds` and existing `ConceptInductionResult`.
- L6–11: `KnowledgeCandidateKind`: CONCEPT / PROPOSITION / RELATION / CAUSAL_RULE.
- L13–16: `KnowledgeObservationRelation`: SUPPORTS / CONTRADICTS.
- L18–35: `KnowledgeInductionPolicy`; minimum independent support cycles, mean support confidence, counterexample penalty.
- L37–71: `KnowledgeInductionObservation`; exact B374 episode id + cycle id + semantic claim + relation + evidence fingerprint + confidence.
- L73–82: `StructuralConceptBinding`; binds existing `ConceptInductionResult` to semantic identity/text.
- L84–150: immutable `KnowledgeCandidate`; canonical support/counterexample episode sets, independent cycles, evidence fingerprints, confidence, B374 ledger fingerprint, policy fingerprint and optional structural-induction fingerprint; no truth/promotion/activation authority.
- L152–159: architectural invariant: structural induction stays in existing `ConceptInductionEngine`; B375 only binds it to exact B374 experience.
- L160–204: `KnowledgeInductionEngine.induce`; verifies every observation references an exact verified episode, enforces source-cycle identity, and requires causal-credit episodes for CAUSAL_RULE observations.
- L206–259: `bindStructuralConcepts`; consumes existing `ConceptInductionResult`, resolves exact latest verified B374 episode for every source cycle, and refuses insufficient/low-confidence support.
- L261–308: repeated-claim induction; rejects one episode both supporting and contradicting the same claim, requires independent support cycles, applies explicit counterexample penalty.
- L310–358: canonical candidate creation.
- L360–389: deterministic candidate fingerprint over claim, exact episode ids, source cycles, evidence, confidence, ledger and policy lineage.

## Prepared tests

NEW `core/runtime-reasoning/src/test/kotlin/app/lifeos/core/runtime/reasoning/KnowledgeInductionEngineTest.kt`

- L18–44: two independent verified B374 cycles induce only a non-authoritative candidate.
- L47–67: multiple revisions from one source cycle cannot fake independent support.
- L70–94: unverified episode observations fail closed.
- L97–127: causal-rule induction requires causal-credit status on every contributing episode.
- L130–160: one episode cannot both support and contradict the same claim.
- L163–188: counterexamples reduce confidence without granting truth authority.
- L191–210: observation ordering cannot change candidate identity.
- L213–233: existing `ConceptInductionResult` binds to exact verified B374 cycles.
- L236–254: canonical observation helper.
- L256–260: canonical `LearningEpisodeState` helper.
- L262–355: exact immutable B374 episode fixture including content-derived id.
- L357–381: existing structural concept-induction result fixture.

## Pre-implementation verification

Before writing code:
1. confirm no existing `KnowledgeInductionEngine`, `KnowledgeCandidate`, or `KnowledgeInductionObservation` collision;
2. confirm existing `ConceptInductionEngine` already enforces multi-cycle support and information-gain thresholds;
3. confirm existing `AbstractionCandidate` remains non-active/non-mutating until independently validated;
4. confirm B374 exposes exact immutable episode id, source cycle, status and state fingerprint;
5. confirm B375 stays inside `:core:runtime-reasoning` and does not increase the 505-file `core/runtime` budget.

## Gate

After implementation:
1. `./gradlew :core:runtime-reasoning:test --tests 'app.lifeos.core.runtime.reasoning.KnowledgeInductionEngineTest'`
2. `./gradlew :core:runtime-reasoning:test`
3. Core Fast Gate after B374/B373 promotion order.
