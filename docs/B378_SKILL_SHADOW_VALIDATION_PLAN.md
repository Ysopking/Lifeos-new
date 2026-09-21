# B378 — SkillShadowValidation — exact implementation plan

Base head: `a426c01ae533c1c2686f604a5b33c224752ee58f`
Branch: `b378-skill-shadow-validation-v1`
Module: `:core:runtime-reasoning`

## Reuse / non-duplication contract

B378 does not create another execution or promotion system:
- `ShadowEvaluationEngine` remains the evolution-shadow evaluator.
- `PersonalLanguageShadowEvaluator` remains the language holdout gate.
- `GeneratedToolTrialRunner` remains the only generated-tool TRIAL execution bridge.
- B376/B377 provide inactive procedural/generalized skill candidates.

B378 is a deterministic, side-effect-free evaluator over already-produced SHADOW observations. It never executes, activates, or promotes a skill.

## Prepared production code

NEW `core/runtime-reasoning/src/main/kotlin/app/lifeos/core/runtime/reasoning/SkillShadowValidationEngine.kt`

- L5–8: `SkillShadowSubjectKind`.
- L10–65: `SkillShadowSubject`; exact adapter for B376 procedural and B377 generalized candidates, including source-candidate and target-domain lineage for generalized skills.
- L67–89: `SkillShadowTestCase`; exact input/output expectation and protected-case flag.
- L91–93: `SkillShadowExecutionMode.SHADOW`.
- L95–132: `SkillShadowObservation`; candidate/case identity, output, success, quality, productive-effect attempt, safety violation, latency and evidence fingerprint.
- L134–154: `SkillShadowValidationPolicy`; minimum cases, success rate, mean quality and latency ceiling.
- L156–181: `SkillShadowValidationStats`.
- L183–187: `SkillShadowValidationDecision`: VALIDATED / INSUFFICIENT_EVIDENCE / REJECTED.
- L189–230: `SkillShadowValidationReport`; canonical evidence fingerprints and explicit `promotionAllowed=false`, `activationAllowed=false`.
- L232–239: architecture invariant: evaluator is side-effect-free; SHADOW observations are supplied, never executed here.
- L240–384: `SkillShadowValidationEngine.evaluate`; independent evaluator, unique holdout cases/inputs, exact one observation per case, exact candidate identity, SHADOW-only mode, productive-effect/safety rejection, exact-output/protected-case regression rejection, bounded quality/success/latency policy.
- L386–414: deterministic report fingerprint.

## Prepared tests

NEW `core/runtime-reasoning/src/test/kotlin/app/lifeos/core/runtime/reasoning/SkillShadowValidationEngineTest.kt`

- L19–39: generalized B377 skill passes valid shadow evidence but gains no promotion/activation authority.
- L42–58: productive-effect attempt rejects otherwise-correct output.
- L61–76: safety violation rejects immediately.
- L79–101: missing or duplicate case coverage fails closed.
- L104–120: expected-output/protected-case regression is rejected.
- L123–136: too few otherwise-valid cases remain INSUFFICIENT_EVIDENCE.
- L139–159: case/observation ordering cannot change report identity.
- L162–179: observation from another skill candidate fails closed.
- L182–190: B376 procedural candidate adapter carries no generalized metadata.
- L192–201: deterministic holdout case fixture.
- L203–223: deterministic SHADOW observation fixture.
- L225–258: exact B377 generalized-skill fixture using existing StructuralSimilarityEngine.
- L260–267: exact B376 source-skill fixture.
- L269–312: completed GoalPlan trace fixture.
- L314–370: immutable verified B374 episode fixture.

## Pre-implementation verification

Verified before writing:
1. no existing `SkillShadowValidationEngine`, `SkillShadowValidationReport`, `SkillShadowObservation`, or `SkillShadowTestCase` collision;
2. existing `ShadowEvaluationEngine` is already side-effect-free, requires exact case coverage and rejects productive-effect attempts;
3. existing `PersonalLanguageShadowEvaluator` evaluates in isolation before promotion;
4. generated-tool TRIAL execution remains in `GeneratedToolTrialRunner`; B378 will not execute tools/skills;
5. B378 remains entirely in `:core:runtime-reasoning`, adding nothing to the 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-reasoning:test --tests 'app.lifeos.core.runtime.reasoning.SkillShadowValidationEngineTest'`
2. `./gradlew :core:runtime-reasoning:test`
3. Core Fast after B377/B376 promotion order.
