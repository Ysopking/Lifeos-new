# B384 — MetaReasoningLearner — exact implementation plan

Base prepared head: `29ed13f0f6af7092633f9be7425f88905289b27a`
Branch: `b384-meta-reasoning-learner-v1`

## Pre-implementation audit

1. B383 provides immutable reasoning-method identities and compatibility metadata only.
2. Existing Level7 `StrategyLearningCandidate` learns world/action strategies from independently verified transitions; it is not a reasoning-method performance model.
3. Existing `MetaAdaptationEvaluator` evaluates a candidate against holdout scores for Controlled Evolution; it does not learn which reasoning method works for a problem class.
4. B372/B373 already separate prediction error and causal credit. B384 must consume verified outcomes/evidence rather than equating strategy use with causal success.
5. B386/B387 will own owner utility and decision utility. B384 therefore learns empirical reasoning-performance evidence only and grants no strategy-selection authority.
6. B385 will own cross-domain transfer; B384 must not generalize performance between structurally different classes on its own.

## Problem-class signature

Introduce a deterministic structural `ReasoningProblemClass` derived from B366/B380 structure only:
- goal intent/semantic class,
- bounded buckets for constraint/fact/unknown/assumption counts,
- present knowledge-gap kinds,
- search-truncated / complete-state flags where supplied.

Do not include raw private statements or exact user text in the public class id.

## Verified performance episode

`ReasoningStrategyOutcomeEpisode` binds:
- exact B383 strategy id + descriptor fingerprint,
- exact problem-class fingerprint,
- source reasoning/learning cycle id,
- outcome state: VERIFIED_SUCCESS / VERIFIED_FAILURE / INCONCLUSIVE,
- independent verification fingerprint,
- bounded work/cost metrics,
- optional measured uncertainty reduction.

Rules:
- success/failure requires nonblank independent verification evidence;
- INCONCLUSIVE never counts as success or failure;
- duplicate exact episodes are idempotent;
- conflicting episode identity fails closed.

## Learner output

`ReasoningStrategyPerformanceProfile` per exact strategy descriptor + problem class:
- verified success/failure counts,
- inconclusive count,
- empirical success rate only when verified sample count > 0,
- mean verified uncertainty reduction when measured,
- mean normalized effort/cost,
- sample count,
- exact source episode ids,
- fingerprint.

No score is turned into an active strategy choice in B384.

## Persistence boundary

Initial B384 implementation stays as a deterministic pure learner over supplied episodes. Durable episode collection may reuse the existing learning episode/receipt infrastructure when wired; do not create another encrypted ledger merely for B384.

## Tests

- unverified success/failure cannot be constructed.
- input order does not change profile.
- duplicate exact episode is idempotent.
- strategy descriptor substitution rejected.
- problem-class substitution produces a separate profile.
- inconclusive samples do not inflate success/failure.
- empirical rate and uncertainty reduction computed only from verified data.
- no selection, execution, promotion or owner-utility authority.
- legacy Level7 action strategies remain separate.

## Gates

1. `:core:runtime-research:test`
2. Core Fast
3. Android Debug
4. Recovery
5. Product Gold

## Authority invariant

`strategy use != verified outcome != causal credit != empirical performance != strategy selection != owner utility`
