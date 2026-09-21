# B371 — OutcomeExpectationModel — exact implementation plan

Base head: `8f3aacfa91da66d4d0dddf12747e5d318213c194`
Branch: `b371-outcome-expectation-model-v1`
Module: `:core:runtime-reasoning`

## Reuse / non-duplication contract

B371 must not replace the existing productive outcome-learning stack:
- `core/runtime/.../learning/OutcomePrediction.kt` remains the pre-action prediction contract for ACTIONABLE convergence decisions.
- `OutcomePredictionFactory.kt` remains the only factory that may create a productive `OutcomePrediction` from an actionable convergence decision.
- `OutcomeEvidence.kt` / `OutcomeSignal` remain the vocabulary for observed post-action outcome signals.
- `OutcomeLearningCoordinator.kt` remains the durable post-action learning coordinator.

B371 adds an earlier reasoning-stage expectation boundary over B370 experiment plans. It is prediction-only: no evidence authority, no success claim, no execution authority.

## Prepared production code

NEW `core/runtime-reasoning/src/main/kotlin/app/lifeos/core/runtime/reasoning/OutcomeExpectationModel.kt`

- L1–4: package/imports; reuse `StableFieldIds` and existing `OutcomeSignal`.
- L6–25: `ExpectedOutcomeBand`; normalized lower/point/upper interval with deterministic fingerprint.
- L27–56: `ExpectedOutcomeSignal`; optional completion/correctness/usefulness/policy bands, at least one required; `pointSignal()` maps to the existing `OutcomeSignal` vocabulary.
- L58–69: `OutcomeExpectationInput`; explicit caller-supplied expectation per B370 evidence action.
- L71–100: immutable `OutcomeExpectationEntry`; binds exact B370 evidence-action id + discrimination-request fingerprint + expected signal; `executionAuthority=false`, `evidenceAuthority=false`.
- L102–132: immutable `OutcomeExpectationModel`; binds source cycle, B368 search fingerprint, B369 counterfactual batch fingerprint and exact B370 experiment-plan fingerprint; canonical entry ordering; `executionAuthority=false`, `evidenceAuthority=false`.
- L134–143: architectural invariant comment: expectation != evidence, expectation != success, expectation != permission.
- L144–206: `OutcomeExpectationModelBuilder`; requires exact one-to-one coverage of admitted B370 experiment actions; rejects missing/unknown/duplicate expectations; derives entries only from the exact plan.
- L208–221: deterministic entry fingerprint helper.
- L223–239: deterministic model fingerprint helper.

No existing productive file is modified.

## Prepared tests

NEW `core/runtime-reasoning/src/test/kotlin/app/lifeos/core/runtime/reasoning/OutcomeExpectationModelTest.kt`

Planned assertions:
- expected bands enforce 0..1 and lower <= point <= upper;
- every admitted B370 evidence action must have exactly one expectation;
- missing/extra/duplicate action expectations fail closed;
- `pointSignal()` uses existing `OutcomeSignal` dimensions exactly;
- expectation ordering cannot change model identity;
- B368/B369/B370 provenance fingerprints all participate in model identity;
- model and entries have no execution/evidence authority.

## Pre-implementation verification

Before writing code:
1. confirm no existing `OutcomeExpectationModel`, `ExpectedOutcomeBand`, or `ExpectedOutcomeSignal` symbol exists;
2. confirm `:core:runtime-reasoning` already depends on `:core:runtime`, so importing existing `OutcomeSignal` does not add a dependency edge;
3. confirm B370 `ExperimentPlan` exposes exact `evidenceAction.id`, request fingerprint and source/search/counterfactual/plan lineage;
4. confirm no architecture-budget file-count increase occurs in `core/runtime`.

## Gate

After implementation:
1. `./gradlew :core:runtime-reasoning:test --tests 'app.lifeos.core.runtime.reasoning.OutcomeExpectationModelTest'`
2. `./gradlew :core:runtime-reasoning:test`
3. stacked CI only after B369/B370 promotion order.

## Promotion run

B371 is cleanly restacked on merged B370/main. This exact head is the promotion candidate for main-targeted CI.
