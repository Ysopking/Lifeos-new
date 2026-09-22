# B389 — AutonomousExperimentLoop — exact implementation plan

Base prepared head: `3ac8fa9a302473649dd94c034c709f73bda143bf`
Branch: `b389-autonomous-experiment-loop-v1`

## Pre-implementation audit

1. B370 already creates bounded SAFE_SANDBOX_EXPERIMENT plans with no execution authority.
2. B371 already binds explicit expected outcome bands to exact experiment actions.
3. B372 already classifies missing/incomplete/unverified/verified prediction error.
4. B373 already assigns provenance-safe causal credit only from verified controlled-intervention evidence.
5. B389 must compose those blocks, not create another experiment executor, causal engine, truth layer or world mutation path.
6. Actual experiment execution remains behind the existing evidence/capability/Owner Policy/ResourceBudget boundaries.

## Goal

Create a bounded autonomous experiment assessment loop:

`B370 plan -> B371 expectation -> authorized external execution -> observation -> B372 prediction error -> optional B373 causal credit -> next-cycle learning candidate`

B389 begins after planning and resumes only when observations are supplied. It never performs the productive effect itself.

## Contracts

`AutonomousExperimentCycle`
- exact B370/B371/B372 fingerprints,
- optional exact B373 fingerprint,
- explicit missing/incomplete/unverified observation sets,
- state: waiting / verified within / verified outside / mixed / causal-credit available,
- no execution/external-effect/causal/world-mutation/promotion authority.

`ExperimentLearningCandidate`
- only from B372 learning-eligible verified entries,
- exact evidence action, expectation, error and observation lineage,
- optional exact B373 assignment fingerprint,
- always enters the next cycle only,
- no truth/causal/execution/world-mutation/promotion authority.

## Fail-closed rules

- B371 model must bind exact B370 plan.
- unknown/substituted observation action ids fail in B372.
- missing/incomplete/unverified observations create no learning candidate.
- causal observations are rejected until B372 is verified-complete.
- causal credit remains a candidate; B373/Controlled Evolution invariants remain unchanged.
- current-cycle productive world state is never mutated.

## Tests

- preparation preserves non-execution authority.
- missing/unverified observations stay waiting.
- verified expected outcome yields next-cycle learning candidate.
- verified prediction error remains explicit but non-causal.
- causal-credit attempt before verified completeness fails closed.
- substituted expectation-plan lineage fails closed.

## Gates

1. `:core:runtime-research:test`
2. `:core:runtime-reasoning:test`
3. Core Fast
4. Android Debug
5. Recovery
6. Product Gold

## Authority invariant

`experiment plan != execution permission != observation != prediction error != causal credit != promoted knowledge`
