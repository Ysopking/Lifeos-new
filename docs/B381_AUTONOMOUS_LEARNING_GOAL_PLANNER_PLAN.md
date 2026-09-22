# B381 — AutonomousLearningGoalPlanner — exact implementation plan

Base prepared head: `c7391732558830f88ee5f04510fd3055897972ff`
Branch: `b381-autonomous-learning-goal-planner-v1`

## Pre-implementation audit

1. B380 `KnowledgeGapDetector` already emits deterministic `KnowledgeGap` values with kind, semantic key, rationale, severity, exact source fingerprint, related refs and recommended `EvidenceActionKind` values.
2. B380 explicitly grants no execution/current-cycle mutation authority. B381 must preserve that boundary.
3. Existing V7 durable goal infrastructure already owns `GoalPlanDefinition`, step ids, append-only transitions, crash-safe replay and next-action derivation. B381 must not create a second durable goal engine.
4. B399/B400 own recursive research planning and information-gain ranking for already-defined research missions. B381 should generate learning-goal candidates, not duplicate research-mission ranking.
5. B382 will own cross-goal curriculum ordering. B381 therefore evaluates each gap independently and does not globally schedule goals.

## Target model

Introduce `AutonomousLearningGoalCandidate`:
- deterministic id,
- exact source `KnowledgeGap.id`,
- source cycle id,
- semantic objective,
- gap kind/severity,
- allowed evidence kinds,
- success criteria,
- bounded stop criteria,
- estimated value/effort metadata for B382,
- no execution authority.

Introduce `LearningGoalSuccessCriterion`:
- required evidence/observation kind,
- exact semantic key,
- minimum verification state where applicable,
- no truth inference from completion alone.

## Planner

`AutonomousLearningGoalPlanner.plan(gaps)`:
- canonical gap ordering,
- one bounded candidate per unique semantic gap unless exact-compatible gaps can be deterministically coalesced,
- maps gap kind to explicit learning objective/stop condition,
- preserves B380 recommended evidence kinds as an upper bound,
- never invents an evidence action kind that B380 did not recommend,
- caps total candidate count,
- records truncation explicitly.

Mappings include:
- EXPLICIT_UNKNOWN -> resolve semantic unknown with allowed retrieval/search/user evidence.
- SEARCH_TRUNCATED -> obtain discriminating evidence or explicit abstention condition.
- NO_COMPLETE_REASONING_STATE -> seek new evidence/experiment capable of changing hypothesis completeness.
- OUTCOME_* -> close exact observation/verification deficit.
- VERIFIED_FAILURE_PATTERN -> discriminate failure cause with simulation/sandbox evidence.

## Integration with existing durable goals

B381 does not persist a `GoalPlanDefinition` directly.

A later admission bridge may:
1. persist/project the accepted learning candidate into canonical Photon/Goal semantics,
2. bind its exact revision,
3. create the existing V7 `GoalPlanDefinition`,
4. route execution through existing durable goal runtime.

This avoids fake Photon revisions and keeps learning-goal proposal separate from durable action admission.

## Tests

- same B380 input -> same learning-goal ids/order.
- gap substitution changes candidate identity.
- recommended evidence kinds cannot be widened.
- duplicate exact gaps coalesce deterministically.
- truncation explicit and stable.
- each gap kind yields bounded success/stop criteria.
- candidates expose no execution or world-mutation authority.
- existing V7 durable goal types are reused only after explicit admission.

## Files

NEW `core/runtime-research/src/main/kotlin/app/lifeos/core/runtime/research/AutonomousLearningGoalPlanner.kt`
NEW corresponding tests.
MOD B381 docs only otherwise unless compilation requires a direct existing-module dependency already transitively available.

## Gates

1. `:core:runtime-research:test`
2. Core Fast
3. Android Debug
4. Android Emulator Recovery
5. Product Gold

## Authority invariant

`knowledge gap != learning goal candidate != curriculum priority != durable goal admission != action authority`
