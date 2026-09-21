# B366 — ProblemStateGraph — exact implementation plan

Base head: `52d84d712e089e0316972998bf888ea8f55b19ae`
Branch: `b366-problem-state-graph-v1`
Scope: B366 only. No B367 hypothesis generation and no productive action authority.

## Pre-implementation audit

Existing primitives to reuse instead of duplicate:
- `core/language/.../LanguageModels.kt:164-203`: `GoalConstraint` and `GoalFrame`.
- `core/field/.../FieldEvidence.kt:85-155`: exact source Photon id/revision, evidence confidence, reliability, authority and payload.
- `core/field/.../FieldHypothesis.kt:64-112`: existing hypothesis representation remains authoritative for B367+; B366 does not create a second hypothesis model.
- `core/runtime/.../thought/ThoughtGraphModels.kt:22-62`: HYPOTHESIS/GOAL/EXPERIMENT graph kinds already exist and are not replaced.
- `core/runtime/.../level7/CausalModelSearchEngine.kt:67-123`, `CounterfactualWorldFormulaRunner.kt:43-69`, `HierarchicalPlanningEngines.kt:101-146`: existing search/simulation/planning engines remain downstream consumers, not duplicated.
- `core/runtime/.../learning/OutcomePrediction.kt:25-76` and `OutcomeLearningCoordinator.kt:17-54`: prediction/outcome learning remains B371+ infrastructure.

Repository-tree check at the base head found no existing `ProblemStateGraph`, `ProblemStateNode`, `ProblemFactInput`, `ProblemUnknownInput`, or `ProblemAssumptionInput` collision.

## Prepared production code

NEW `core/reasoning/src/main/kotlin/app/lifeos/core/reasoning/ProblemStateGraph.kt`

- L1-9: package/imports; depend only on existing language/model/field contracts.
- L11-35: deterministic graph/node/edge ID wrappers.
- L37-53: exact `ProblemSourceRevision` binding `PhotonId + revision`.
- L55-88: `ProblemEvidenceRef`; bind evidence to exact source revision and evidence fingerprint.
- L90-92: node/edge kinds: GOAL, CONSTRAINT, FACT, UNKNOWN, ASSUMPTION and typed relations.
- L94-170: immutable `ProblemStateNode`; FACT requires evidence; ASSUMPTION cannot carry evidence; canonical source/evidence ordering; deterministic content identity.
- L172-215: immutable typed `ProblemStateEdge` with deterministic ID.
- L217-268: immutable `ProblemStateGraph`; exactly one goal, closed endpoints, canonical nodes/edges, content-derived graph fingerprint/id; typed projections for constraints/facts/unknowns/assumptions.
- L270-291: `ProblemFactInput`; non-empty matching evidence required; confidence may not exceed weakest evidence bound.
- L293-303: `ProblemUnknownInput`.
- L305-315: `ProblemAssumptionInput`.
- L321-446: `ProblemStateGraphBuilder`; maps GoalFrame → goal/constraints, accepts only explicit evidence-backed facts, converts language ambiguities and unresolved semantic roles/references/conditions to UNKNOWN, adds only explicit assumptions, and links every child to the root goal.
- L448-484: canonical ordering and graph fingerprint helpers.

No existing file needs modification for B366. This keeps the block additive, independently revertible, and unable to change productive execution.

## Prepared tests

NEW `core/reasoning/src/test/kotlin/app/lifeos/core/reasoning/ProblemStateGraphTest.kt`

- L1-23: imports/fixture.
- L29-87: full decomposition test: goal + constraint + fact + unknown + assumption; exact evidence revision asserted.
- L90-108: language ambiguity remains UNKNOWN and never becomes FACT.
- L111-128: FACT creation fails without explicit matching evidence.
- L131-147: FACT confidence cannot exceed weakest evidence/reliability/authority bound.
- L150-158: changing only the source Photon revision changes graph identity/fingerprint.
- L161-184: input-order invariance for canonical graph identity.
- L187-240: deterministic test fixtures.

## Invariants sealed by B366

- Understanding != Truth: GoalFrame can frame the problem but cannot emit FACT by itself.
- Observation != Learned Rule: raw evidence becomes only an explicit fact input, not a promoted rule.
- Exact revision > latest object: every graph and evidence binding carries exact source revision.
- Assumption != Fact: assumptions cannot carry evidence in this contract and facts cannot exist without evidence.
- B366 has no execution authority, no owner-policy bypass, no world-state mutation and no hypothesis promotion.

## Gate

After implementation:
1. `./gradlew :core:runtime:test --tests 'app.lifeos.core.runtime.reasoning.ProblemStateGraphTest'`
2. `./gradlew :core:runtime:test`
3. Core Fast Gate
4. Android Debug CI
5. B366 remains unmerged until B365/PR #444 exact-head recovery + Product Gold are green.

## Architecture-budget response

B366 is isolated in the new `:core:reasoning` JVM module so the established `core/runtime` monolith budget remains at 505 Kotlin files. The module depends only on `:core:model`, `:core:language`, and `:core:field`.

## Exact-head Gold rerun note

The B366 code path is isolated from app startup, but GitHub rerunning the same emulator workflow can leave multiple same-name artifacts on one run. Product Gold intentionally rejects ambiguous sibling evidence. Therefore final promotion uses one fresh PR head so Core Fast, Android Debug, Emulator Recovery and Product Gold each produce one unambiguous exact-head evidence set.
