# B373 — CausalCreditAssignment — exact implementation plan

Base head: `e51b30b2540bbd553193274fba9fefcde703fa61`
Branch: `b373-causal-credit-assignment-v1`
Module: `:core:runtime-reasoning`

## Reuse / non-duplication contract

B373 does not create a second causal-inference system:
- `CausalObservation` remains the causal evidence carrier.
- `CausalEvidenceKind` remains the evidence-type authority.
- `CausalInductionCandidate.create(...)` remains the existing admission gate that rejects temporal-correlation-only causal authority.
- B370 `ExperimentPlan`, B371 `OutcomeExpectationModel`, and B372 `PredictionErrorReport` provide exact experiment → expectation → observed-error lineage.

B373 adds causal-credit assignment over already controlled experiment evidence. It does not mutate the world/equation, promote causal rules, or grant causal authority.

## Prepared production code

NEW `core/runtime-reasoning/src/main/kotlin/app/lifeos/core/runtime/reasoning/CausalCreditAssignmentEngine.kt`

- L1–6: package/imports; reuse `StableFieldIds`, `CausalEvidenceKind`, `CausalObservation`, and existing `CausalInductionCandidate`.
- L8–12: `CausalCreditRelation`: SUPPORTS / CONTRADICTS / UNRESOLVED.
- L14–33: `CausalCreditObservationInput`; binds exact B370 evidence-action id + controlled causal observation + relation for every competing candidate.
- L35–74: immutable `CausalCreditAssignment`; exact discrimination-request fingerprint, B372 error-entry fingerprint, intervention variable, candidate relation map, causal observation fingerprint and existing induction candidate; `causalAuthority=false`, `promotionAllowed=false`.
- L76–104: `CausalCreditAssignmentReport`; exact B370/B371/B372 fingerprints and canonical assignments; no causal/promotion authority.
- L106–114: architectural invariant comment: prediction error alone != causal evidence.
- L115–210: `CausalCreditAssignmentEngine.assign`; requires exact plan/model/report lineage, verified-complete B372 report, exact one-per-action causal observations, CONTROLLED_INTERVENTION only, exact observation-provenance fingerprint match, and complete candidate relation coverage; creates existing `CausalInductionCandidate`.
- L212–231: deterministic assignment fingerprint.
- L233–247: deterministic report fingerprint.

## Prepared tests

NEW `core/runtime-reasoning/src/test/kotlin/app/lifeos/core/runtime/reasoning/CausalCreditAssignmentEngineTest.kt`

- L16–46: verified controlled interventions produce existing `CausalInductionCandidate` values and expose no causal/world/equation/promotion authority.
- L49–77: TEMPORAL_CORRELATION is rejected for B370 experiment credit.
- L80–101: causal-observation provenance must equal the exact B372 `observationInputFingerprint`.
- L104–125: unverified prediction-error reports are rejected.
- L128–158: candidate relations must cover the exact B370 competing candidate set.
- L161–186: input ordering cannot change assignment-report identity.
- L189–224: missing or unknown action coverage fails closed.
- L227–288: deterministic B370/B371/B372 fixture.
- L290–334: controlled `CausalObservation` construction tied to exact B372 provenance.
- L336–356: causal-discrimination and fixture helpers.

## Pre-implementation verification

Before writing:
1. confirm no existing `CausalCreditAssignmentEngine` / `CausalCreditAssignmentReport` symbol exists;
2. confirm `CausalInductionCandidate.create` already requires CONTROLLED_INTERVENTION or INDEPENDENT_VERIFIED_OUTCOME and exposes no mutation/causal authority;
3. confirm B372 stores the exact `ObservedOutcomeInput.fingerprint()` as `observationInputFingerprint`;
4. confirm B370 request exposes exact candidate ids + intervention variable;
5. confirm implementation remains in `:core:runtime-reasoning`, not the 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-reasoning:test --tests 'app.lifeos.core.runtime.reasoning.CausalCreditAssignmentEngineTest'`
2. `./gradlew :core:runtime-reasoning:test`
3. stacked CI only after B372 promotion order.
