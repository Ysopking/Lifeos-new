# B388 — AutonomousStudyLoop — exact implementation plan

Base prepared head: `d88853fa8af2fcd44661d506993f8c0f3ceadeb5`
Branch: `b388-autonomous-study-loop-v1`

## Pre-implementation audit

1. B381 owns non-authoritative learning-goal generation from B380 KnowledgeGap.
2. B382 owns dependency-aware curriculum ordering but not execution.
3. B399 already owns bounded recursive DeepSearch mission planning/replay.
4. B400 already owns mission-level predicted information-gain ranking.
5. B386/B387 model owner utility and candidate utility without granting selection or execution authority.
6. B388 therefore must **compose** these layers, not create a second research engine, scheduler, network transport, truth score or durable goal engine.

## Goal

Create a single bounded study-state view:

`KnowledgeGap -> B381 learning goal -> B382 curriculum -> B399 mission -> B400 information priority -> research result -> reviewable knowledge candidate`

The loop is state composition. Existing DeepSearch execution remains external to B388.

## Contracts

`AutonomousStudyGoalState`
- exact B381 goal/gap identity,
- B382 curriculum position/deferred state,
- exact B399 mission ids/statuses,
- optional B400 mission priority scores,
- derived bounded study state.

`AutonomousStudyPlan`
- exact B381/B382/B399 fingerprints,
- optional exact B400 decision fingerprint,
- deterministic canonical goal states,
- no execution/network/permission/world-mutation/Owner Policy authority.

`StudyKnowledgeCandidate`
- emitted only for exact B399 RESOLVED missions,
- binds exact goal, gap, semantic key, mission id and result fingerprint,
- requires next-cycle validation,
- grants no truth, execution, world-mutation or promotion authority.

## Fail-closed rules

- curriculum must cover the exact B381 goal set.
- each B399 mission/non-research/deferred gap must belong to the B381 plan.
- learning cycle must match B399 source cycle.
- B400 decision must bind exact B399 plan revision.
- blocked/unresolved/deferred missions never become knowledge candidates.
- a RESOLVED result is a review/consolidation candidate, not truth.

## Tests

- exact lineage composes deterministically.
- mismatched B400/B399 lineage fails closed.
- RESOLVED result yields review candidate with no truth authority.
- UNRESOLVED/PERMISSION_BLOCKED never yield knowledge candidates.
- exact same inputs produce exact same plan.
- no execution/network/permission/world/policy authority.

## Gates

1. `:core:runtime-research:test`
2. Core Fast
3. Android Debug
4. Recovery
5. Product Gold

## Authority invariant

`research priority != research execution != resolved result != validated knowledge != world truth`
