# B377 — SkillGeneralization — exact implementation plan

Base head: `2ee7dc0ef4d5de8f5b928879e16935198a49eeca`
Branch: `b377-skill-generalization-v1`
Module: `:core:runtime-reasoning`

## Reuse / non-duplication contract

B377 does not create a second transfer engine:
- `StructuralSignature` remains the structural-domain signature.
- `StructuralSimilarityEngine` remains the similarity calculator.
- `StructuralTransferCandidate` remains the validated cross-domain structural-transfer carrier.
- B376 `ProceduralSkillCandidate` remains the source procedure candidate.

B377 adapts a B376 procedure to a different domain only through an already-created `StructuralTransferCandidate`. Structural similarity never establishes semantic identity:
`semanticIdentityEstablished == false`, `executionAuthority == false`, `activationAllowed == false`, `promotionAllowed == false`.

## Prepared production code

NEW `core/runtime-reasoning/src/main/kotlin/app/lifeos/core/runtime/reasoning/SkillGeneralizationEngine.kt`

- L1–5: package/imports; reuse `StableFieldIds`, `StructuralSignature`, and `StructuralTransferCandidate`.
- L7–21: `SkillGeneralizationPolicy`; minimum structural similarity and bounded maximum step count.
- L23–46: `SkillGeneralizationRequest`; exact B376 source-skill + existing transfer candidate + one target-domain objective per source step.
- L48–69: `GeneralizedSkillStep`; preserves source step key, dependency-key topology and priority while allowing a target objective.
- L71–135: immutable `GeneralizedSkillCandidate`; binds exact source-skill id/fingerprint, source/target structural signatures, transfer candidate/fingerprint, validation fingerprint, similarity, generalized steps and policy fingerprint; no semantic/execution/activation/promotion authority.
- L137–144: architecture invariant comment: structure != semantics; generalized candidate cannot self-activate.
- L145–238: `SkillGeneralizationEngine`; canonical request order, duplicate rejection, inactive source-skill checks, similarity threshold, maximum-step bound, exact source topology == B376 shape fingerprint, exact one-to-one target-objective coverage, dependency/priority preservation and deterministic candidate construction.
- L240–270: deterministic generalized-skill fingerprint helper.

No existing transfer, planning, strategy-learning, or execution file is modified.

## Prepared tests

NEW `core/runtime-reasoning/src/test/kotlin/app/lifeos/core/runtime/reasoning/SkillGeneralizationEngineTest.kt`

- L20–47: validated structural transfer generalizes a B376 skill while preserving no semantic/execution/activation/promotion authority.
- L50–75: transfer source topology must equal the exact B376 skill-shape fingerprint.
- L78–111: target objectives must cover every source step exactly; missing/unknown step mappings fail closed.
- L114–138: dependency topology and priorities are preserved while target objectives may change.
- L141–167: below-threshold structural similarity fails closed.
- L170–185: request ordering cannot change generalized candidate order or identity.
- L188–199: transfer validation fingerprint participates in generalized identity.
- L202–208: duplicate generalization requests fail closed.
- L211–223: canonical generalization-request helper.
- L225–255: existing `StructuralSimilarityEngine` / `StructuralTransferCandidate` fixture.
- L257–264: exact B376 source-skill fixture from two verified independent cycles.
- L266–309: exact completed GoalPlan trace fixture.
- L311–367: immutable verified B374 episode fixture.

## Pre-implementation verification

Before writing code:
1. confirm no existing `SkillGeneralizationEngine`, `GeneralizedSkillCandidate`, or `SkillGeneralizationRequest` collision;
2. confirm `StructuralSimilarityEngine` already calculates similarity from topology/relation/dimension matches;
3. confirm `StructuralTransferCandidate` already requires different source/target domains and exposes `semanticIdentityEstablished=false` and `directTransferActivationAllowed=false`;
4. confirm B376 candidates already expose exact procedural shape, ordered steps and no execution/activation/promotion authority;
5. confirm implementation remains in `:core:runtime-reasoning` and does not add to the 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-reasoning:test --tests 'app.lifeos.core.runtime.reasoning.SkillGeneralizationEngineTest'`
2. `./gradlew :core:runtime-reasoning:test`
3. Core Fast after B376/B375 promotion order.
