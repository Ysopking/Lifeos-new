# B372 — PredictionErrorEngine — exact implementation plan

Base head: `0d39b1043870dc0f9cee9507da78a196b484e271`
Branch: `b372-prediction-error-engine-v1`
Module: `:core:runtime-reasoning`

## Reuse / non-duplication contract

B372 does not replace the productive outcome-learning stack:
- `OutcomeScorer` remains authoritative for productive `OutcomePrediction + OutcomeEvidence` scoring.
- `OutcomeLearningCoordinator` remains the only durable adaptation coordinator.
- `OutcomeSignal` remains the shared four-dimensional observed-outcome vocabulary.
- B371 `OutcomeExpectationModel` remains the expectation source.

B372 only computes explicit deviation between B371 expectation bands and observed outcome signals. It performs no adaptation, causal inference, promotion, or execution.

## Prepared production code

NEW `core/runtime-reasoning/src/main/kotlin/app/lifeos/core/runtime/reasoning/PredictionErrorEngine.kt`

- L1–4: package/imports; reuse `StableFieldIds` and existing `OutcomeSignal`.
- L6–12: `PredictionErrorState`: MISSING_OBSERVATION, INCOMPLETE_OBSERVATION, UNVERIFIED_OBSERVATION, WITHIN_EXPECTED_BAND, OUTSIDE_EXPECTED_BAND.
- L14–19: `PredictionErrorDimension`: exact mirror of the four existing outcome dimensions.
- L21–45: `ObservedOutcomeInput`; exact evidence-action id, observation ref/fingerprint, signal, confidence and verification state.
- L47–79: `PredictionErrorComponent`; expected band, actual value, signed point error and distance outside the expected band with self-validating arithmetic.
- L81–135: `PredictionErrorEntry`; explicit state and aggregate error values; missing observations cannot synthesize values; only verified complete states are learning-eligible; `causalAuthority=false`.
- L137–172: `PredictionErrorReport`; canonical entries plus explicit missing/incomplete/unverified action ids; separate `complete` and `verifiedComplete`; `causalAuthority=false`.
- L174–181: architectural invariant comment: error != causality, error != adaptation authority.
- L182–292: `PredictionErrorEngine.compare`; rejects duplicate/unknown observations, permits partial observation sets but makes absence explicit, compares every expected dimension, assigns terminal report states deterministically.
- L294–310: map B371 expected dimensions to observed `OutcomeSignal`.
- L312–332: component arithmetic helper.
- L334–357: deterministic entry fingerprint.
- L359–381: deterministic report fingerprint.

## Prepared tests

NEW `core/runtime-reasoning/src/test/kotlin/app/lifeos/core/runtime/reasoning/PredictionErrorEngineTest.kt`

- L11–49: verified within-band vs outside-band states and deviation.
- L53–75: missing observation remains explicit and non-learning-eligible.
- L79–99: missing expected signal dimensions yield INCOMPLETE_OBSERVATION.
- L103–123: unverified complete observations can expose error but remain ineligible.
- L127–146: signed point error and band-deviation arithmetic validation.
- L150–164: observation input ordering does not change report identity.
- L168–195: unknown and duplicate observations fail closed.
- L199–212: report and entries carry no causal authority.
- L216–268: deterministic B370/B371 fixture.
- L270–287: observed-outcome fixture.

## Pre-implementation verification

Before writing:
1. confirm no existing `PredictionErrorEngine`, `PredictionErrorReport`, or `ObservedOutcomeInput` symbol exists;
2. confirm existing `OutcomeScorer` is productive prediction/evidence scoring and is not modified;
3. confirm B371 expectation entries expose exact B370 action id + fingerprint and expected bands;
4. confirm `:core:runtime-reasoning` already depends on `:core:runtime` for `OutcomeSignal`;
5. confirm no `core/runtime` file-count increase.

## Gate

After implementation:
1. `./gradlew :core:runtime-reasoning:test --tests 'app.lifeos.core.runtime.reasoning.PredictionErrorEngineTest'`
2. `./gradlew :core:runtime-reasoning:test`
3. stacked CI only after B371 promotion order.

## Promotion run

B372 is cleanly restacked on merged B371/main. This exact head is the promotion candidate for main-targeted CI.
