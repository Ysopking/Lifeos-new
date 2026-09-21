# B370 — ExperimentPlanner — exact implementation plan

Base: B369 head `d02be85bbaa316de7bc7c340ef82d88204add2f5`
Branch: `b370-experiment-planner-v1`

## Reuse audit

B370 reuses:
- `CausalDiscriminationRequest` from the existing causal model-search stack;
- `CausalDiscriminationEngine` for bounded proposal construction;
- `BoundedCausalActionProposal` for resource cost;
- `EvidenceActionRequest` / `EvidenceActionKind.SAFE_SANDBOX_EXPERIMENT` for the existing active-evidence contract.

B370 adds planning/provenance only. It does not execute experiments, mutate the productive world, or grant causal authority.

## Production file

NEW `core/runtime-reasoning/src/main/kotlin/app/lifeos/core/runtime/reasoning/ExperimentPlanner.kt`

Implement:
1. exact source-cycle, reasoning-search and counterfactual-batch fingerprints;
2. bounded conversion of causal discrimination requests through existing `CausalDiscriminationEngine`;
3. deterministic request gap fingerprint;
4. safe-sandbox-only evidence actions;
5. cumulative resource budget admission;
6. explicit omitted/truncated request identities;
7. immutable plan/item fingerprints;
8. `executionAuthority == false` throughout.

## Tests

Seal:
- highest-information-gain requests are planned first through the existing engine;
- resource budget stops admission deterministically;
- every action is SAFE_SANDBOX_EXPERIMENT and has no execution authority;
- source/search/counterfactual fingerprints participate in plan identity;
- reordered requests produce identical plan;
- invalid/empty provenance fails closed.

## Gate

B370 remains stacked/draft behind B369.

## Promotion run

B370 is cleanly restacked on merged B369/main. This exact head is the promotion candidate for main-targeted CI.
