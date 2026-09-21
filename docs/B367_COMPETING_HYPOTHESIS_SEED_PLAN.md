# B367 — Competing Hypothesis Seed — exact implementation plan

Base: B366 head `c640e997ed815d60e325fa4ffe15312b18fa4a33`
Branch: `b367-competing-hypothesis-seed-v1`

## Reuse audit

B367 must not create a second hypothesis/convergence system.

Authoritative primitives already present:
- `core/field/FieldHypothesis.kt`: `FieldHypothesis`, `HypothesisEvidenceLink`, `HypothesisConflictLink`, `HypothesisState`.
- `core/field/FieldGraphModels.kt`: `FieldNode`, `FieldRelation`, `CompetitionGroup`, `FieldGraph`.
- `core/field/FieldConvergenceEngine.kt`: downstream deterministic competition/convergence.
- B366 `ProblemStateGraph.kt`: epistemically typed GOAL / CONSTRAINT / FACT / UNKNOWN / ASSUMPTION and exact evidence/source revision binding.

## Production file

NEW `core/reasoning/src/main/kotlin/app/lifeos/core/reasoning/ProblemHypothesisSeed.kt`

The block will:
1. accept explicit alternative hypotheses for an existing B366 UNKNOWN;
2. require at least two alternatives per question;
3. map B366 nodes deterministically into the existing FieldGraph model;
4. resolve every B366 fact evidence reference against exact `FieldEvidence` id + source Photon revision + content fingerprint;
5. create existing `FieldHypothesis` values in COMPETING state;
6. attach SUPPORTS / CONTRADICTS evidence links only through B366 FACT nodes;
7. attach assumptions only as DEPENDS_ON graph relations, never as evidence links;
8. create a `CompetitionGroup` per unknown and explicit pairwise `HypothesisConflictLink` values;
9. preserve unresolved competition for the existing `FieldConvergenceEngine`.

No productive action, owner-policy bypass, mutation, outcome learning, or world-state authority is introduced.

## Tests

NEW `core/reasoning/src/test/kotlin/app/lifeos/core/reasoning/ProblemHypothesisSeedTest.kt`

Seal:
- two alternatives become competing existing `FieldHypothesis` objects;
- exact evidence revision/fingerprint is mandatory;
- assumption references never become evidence links;
- one-alternative questions fail closed;
- non-UNKNOWN targets fail closed;
- proposal ordering does not change seed/graph/hypothesis identity.

## Promotion gate

B367 remains stacked/draft until:
1. B365 PR #444 exact head is green and merged;
2. B366 retargets to main and passes Core Fast + Debug;
3. B367 then retargets to the B366 accepted head and passes `:core:runtime:test`.

## Module placement

B367 stays in `:core:reasoning`; it does not add files to the bounded `core/runtime` monolith.

## Promotion run

B367 is cleanly restacked on merged B366/main. This exact head is the promotion candidate and must pass the main-targeted CI gates before merge.
