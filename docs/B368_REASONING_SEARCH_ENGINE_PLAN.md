# B368 — ReasoningSearchEngine — exact implementation plan

Base: B367 head `3ea9103ec51755a1c127e85de7c7e567d1a4d4dc`
Branch: `b368-reasoning-search-engine-v1`

## Reuse audit

B368 does not replace:
- `FieldConvergenceEngine`: still owns evidence-driven convergence and truth-status.
- `ConvergenceDecisionEngine`: still owns actionable / unresolved / evidence-required decisions.
- `CausalModelSearchEngine`: remains causal graph structure search.
- `PlanSearchEngine`: remains hierarchical action-plan selection.

B368 searches combinations of already-admitted B367 competing hypotheses before convergence. It is structural search only and has no productive execution authority.

## Production file

NEW `core/reasoning/src/main/kotlin/app/lifeos/core/reasoning/ReasoningSearchEngine.kt`

Implement:
1. bounded deterministic frontier search over B367 `CompetitionGroup` values;
2. exactly one hypothesis per competition group in a complete state;
3. conflict-aware pruning using existing `HypothesisConflictLink`;
4. structural metrics only:
   - distinct supporting evidence count/weight,
   - contradiction weight,
   - explicit assumption dependency weight,
   - unresolved competition count;
5. deterministic state fingerprint from selected hypothesis ids + metrics;
6. canonical frontier ordering;
7. explicit `truncated` marker when max-state budget stops exploration;
8. no winner/truth label and no mutation of hypotheses, field state, world state, goals, policy, or actions.

## Tests

NEW `core/reasoning/src/test/kotlin/app/lifeos/core/reasoning/ReasoningSearchEngineTest.kt`

Seal:
- Cartesian alternatives across two questions produce complete states;
- pairwise conflict links prune incompatible combinations;
- support / contradiction / assumption metrics derive only from the existing seed;
- changing input order does not change result identity/order;
- bounded state budget reports truncation;
- malformed seed mapping fails closed.

## Gate

B368 stays stacked/draft until B365 → B366 → B367 promotion order is satisfied.

## Module placement

B368 stays in `:core:reasoning`; it consumes B367 domain models without growing `core/runtime`.

## Promotion run

B368 is cleanly restacked on merged B367/main. This exact head is the promotion candidate for main-targeted CI.
