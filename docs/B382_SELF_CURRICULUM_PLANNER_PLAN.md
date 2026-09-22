# B382 — SelfCurriculumPlanner — exact implementation plan

Base prepared head: `085f8680e479805acc07eeb4bdd5b5d71b640492`
Branch: `b382-self-curriculum-planner-v1`

## Pre-implementation audit

1. B381 produces non-authoritative learning-goal candidates from exact B380 gaps.
2. Existing V7 goal definitions already model dependency graphs and deterministic next-action ordering; B382 must not create a second execution scheduler.
3. B400 Information-Gain Policy ranks research missions, not long-lived learning goals. B382 may consume information-gain metadata when present but must not duplicate mission ranking.
4. B386/B387 Owner Utility are future blocks. B382 therefore cannot pretend to know owner utility yet; it uses only explicit candidate metadata, dependency readiness, gap severity, bounded effort/difficulty and deterministic tie-breakers.
5. B382 emits a curriculum proposal only. It does not admit durable goals or execute research.

## Model

`LearningCurriculumItem`
- exact B381 candidate id,
- prerequisite candidate ids,
- normalized severity/value proxy,
- bounded estimated effort,
- bounded estimated difficulty,
- readiness state,
- deterministic score components.

`LearningCurriculumPlan`
- canonical ordered items,
- blocked dependency map,
- deferred items,
- explicit truncation,
- fingerprint.

## Planner rules

1. validate all prerequisite refs resolve within candidate set.
2. reject cycles.
3. topological readiness dominates score: blocked items cannot outrank ready prerequisites.
4. among ready items rank by deterministic bounded tuple:
   - higher source gap severity,
   - higher expected evidence value where explicitly available,
   - lower effort,
   - lower difficulty,
   - stable candidate id tie-break.
5. no inferred user preference/utility before B386.
6. maximum active curriculum frontier and total items are bounded.
7. ties remain deterministic without pretending stronger evidence.

## Integration

B382 output may feed later durable-goal admission, but:
- curriculum rank is not execution authority,
- rank is not owner utility,
- rank is not evidence of truth,
- reprioritization creates a new plan fingerprint; it never silently rewrites prior durable history.

## Tests

- dependency order always respected.
- cycle/unknown prerequisite fail closed.
- deterministic ordering independent of input list order.
- severity/value/effort/difficulty components each affect ranking only within ready frontier.
- no owner-utility field before B386.
- truncation/deferred state explicit.
- same exact candidates -> same plan identity.
- candidate revision changes -> new plan identity.

## Files

NEW `core/runtime-research/src/main/kotlin/app/lifeos/core/runtime/research/SelfCurriculumPlanner.kt`
NEW corresponding tests.
No new persistence layer.

## Gates

1. `:core:runtime-research:test`
2. Core Fast
3. Android Debug
4. Recovery
5. Product Gold

## Authority invariant

`learning-goal value proxy != owner utility != curriculum rank != durable admission != execution`


Validation trigger: exact-main B381 GOLD successor.
