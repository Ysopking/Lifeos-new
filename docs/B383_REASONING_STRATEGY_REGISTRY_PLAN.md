# B383 — ReasoningStrategyRegistry — exact implementation plan

Base prepared head: `d6890a345ea081aa2e180fc2f3cbc4ddfbb7a346`
Branch: `b383-reasoning-strategy-registry-v1`

## Pre-implementation audit

1. B368 already implements bounded structural hypothesis search.
2. B369/B370 already own counterfactual simulation and safe experiment planning.
3. B399/B400 own recursive research planning and information-gain ranking.
4. Legacy Level7 `StrategyLearningCandidate` / `StrategyGeneralizer` model **world/action strategies learned from independently verified transitions**. B383 must not reuse that type for reasoning-method identity because the semantics differ.
5. B190 already admits verified learned action-strategy candidates into Controlled Evolution. B383 grants no promotion/evolution authority.
6. B384 will learn which reasoning strategy performs well for a problem class. B383 therefore supplies stable strategy identities, compatibility metadata and a deterministic catalog only; it does not learn weights or choose a winner.

## Model

Introduce:
- `ReasoningStrategyId`
- `ReasoningStrategyKind`
- `ReasoningStrategyExecutionClass`
- `ReasoningStrategyDescriptor`
- `ReasoningStrategyRegistry`

Canonical built-ins:
- structural hypothesis search (B368)
- counterfactual simulation (B369)
- safe experiment design (B370)
- recursive research (B399)
- active evidence selection (existing Level7 evidence planner)
- explicit abstention

Descriptor metadata:
- immutable strategy id/version,
- applicable B380 gap kinds,
- supported/relevant evidence-action kinds,
- execution class,
- whether external observation is required,
- bounded deterministic fingerprint.

## Registry semantics

- immutable after construction,
- duplicate strategy ids rejected,
- same id with another version/content cannot shadow silently,
- canonical ordering by strategy id,
- compatibility query returns all applicable strategies, not a selected winner,
- empty compatibility result is valid and explicit,
- no reliability score, learned utility or owner preference field in B383,
- no runtime execution/promotion/mutation authority.

## B384 boundary

B384 may refer to exact `ReasoningStrategyId + descriptorFingerprint` when recording verified outcomes. Learned performance overlays must remain separate from the B383 immutable base descriptor.

## Tests

- built-in registry deterministic across construction/input order.
- duplicate ids fail closed.
- descriptor identity changes with version/compatibility metadata.
- compatibility query is deterministic.
- gap substitution changes compatible set only according to explicit metadata.
- no strategy selection/winner is produced.
- descriptors expose no execution/promotion/world-mutation authority.
- Level7 action-strategy learning is not imported into or mutated by this registry.

## Files

NEW `core/runtime-research/src/main/kotlin/app/lifeos/core/runtime/research/ReasoningStrategyRegistry.kt`
NEW corresponding tests.
No persistence changes.

## Gates

1. `:core:runtime-research:test`
2. Core Fast
3. Android Debug
4. Recovery
5. Product Gold

## Authority invariant

`reasoning strategy descriptor != learned strategy performance != strategy selection != execution != truth`


Validation note: Exact-main validation trigger; merge requires Core Fast, Android Debug, Android Emulator Recovery, and LIFEOS Product Gold green on this exact head.
