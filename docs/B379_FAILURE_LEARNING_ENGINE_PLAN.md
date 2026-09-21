# B379 — FailureLearningEngine — exact implementation plan

Base head: `4c7e176d6578ab11080e882c5722ebf65524923e`
Branch: `b379-failure-learning-engine-v1`
Module: `:core:runtime-reasoning`

## Reuse / non-duplication contract

B379 does not create a second failure or curriculum system:
- existing level7 `PredictionFailure` remains the normalized failure-evidence carrier;
- existing `CurriculumCandidate` remains the reviewable curriculum candidate;
- existing `FailureToCurriculumProjector` remains the failure → curriculum projection path;
- B372 remains authoritative for verified prediction deviation;
- B378 remains authoritative for skill-shadow validation.

B379 only normalizes eligible failures into the existing level7 failure/curriculum path. It never promotes strategy changes, executes corrections, or mutates productive state.

## Prepared production code

NEW `core/runtime-reasoning/src/main/kotlin/app/lifeos/core/runtime/reasoning/FailureLearningEngine.kt`

- L8–11: `FailureLearningSourceKind` for prediction-error and skill-shadow provenance classes.
- L13–69: immutable `FailureLearningResult`; exact B372/B378 report fingerprints, canonical existing `PredictionFailure` values, optional existing `CurriculumCandidate`, and explicit `learningAuthority=false`, `strategyPromotionAllowed=false`, `correctiveExecutionAllowed=false`.
- L71–74: architecture invariant: missing/incomplete/unverified/within-band/insufficient-evidence states are not learned failures.
- L75–179: `FailureLearningEngine.learn`; independent generator/evaluator identities, duplicate-report rejection, verified B372 OUTSIDE_EXPECTED_BAND only, B378 REJECTED only, exact evidence fingerprints, deterministic normalization, and projection through existing `FailureToCurriculumProjector`.
- L181–190: exact existing level7 prediction-failure fingerprint helper.
- L192–215: deterministic failure-learning result fingerprint.

## Prepared tests

NEW `core/runtime-reasoning/src/test/kotlin/app/lifeos/core/runtime/reasoning/FailureLearningEngineTest.kt`

- L20–44: verified outside-band B372 result becomes existing `PredictionFailure` + existing `CurriculumCandidate`, with no authority.
- L47–60: within-band and unverified observations are not failure learning.
- L63–78: B378 REJECTED shadow report becomes failure evidence while INSUFFICIENT_EVIDENCE does not.
- L81–91: generator and evaluator identity separation.
- L94–113: report ordering cannot change result identity.
- L116–125: duplicate reports fail closed rather than inflate support.
- L128–182: deterministic one-action B370/B371/B372 fixture.
- L184–225: deterministic B378 shadow-report fixture.
- L227–234: exact B376 source-skill fixture.
- L236–279: completed GoalPlan trace fixture.
- L281–337: immutable verified B374 episode fixture.

## Pre-implementation verification

Verified before writing:
1. no existing `FailureLearningEngine` or `FailureLearningResult` collision;
2. existing `PredictionFailure` and `CurriculumCandidate` already exist in level7;
3. existing `FailureToCurriculumProjector` already owns failure → curriculum projection;
4. `CurriculumCandidate.strategyPromotionAllowed == false`;
5. B372 marks only verified complete within/outside-band entries as learning-eligible;
6. B378 distinguishes REJECTED from INSUFFICIENT_EVIDENCE and never grants promotion authority;
7. B379 remains inside `:core:runtime-reasoning` and does not grow the 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-reasoning:test --tests 'app.lifeos.core.runtime.reasoning.FailureLearningEngineTest'`
2. `./gradlew :core:runtime-reasoning:test`
3. Core Fast after B378/B377 promotion order.

## Promotion run

B379 is cleanly restacked on merged B378/main. This exact head is the promotion candidate for main-targeted CI.
