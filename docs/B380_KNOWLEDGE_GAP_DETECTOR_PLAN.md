# B380 — KnowledgeGapDetector — exact implementation plan

Base head: `13a06a06adfe0373a2e9ee60605ea62d7f374e08`
Branch: `b380-knowledge-gap-detector-v1`
Module: `:core:runtime-reasoning`

## Reuse / non-duplication contract

B380 does not replace existing world/extension gap detection:
- `ExtensionGapDetector` remains authoritative for missing world representations, signals, couplings, causal relations and equation coverage.
- `EvidenceActionKind` remains the evidence-acquisition vocabulary.
- B366 `ProblemStateGraph`, B367 `ProblemHypothesisSeed`, B368 `ReasoningSearchResult`, B371/B372 expectation/error lineage, and B379 `FailureLearningResult` remain their respective source authorities.

B380 detects epistemic gaps only and emits recommended evidence kinds. It does not create executable `EvidenceActionRequest` values, start research/experiments, or mutate the current cycle.

## Prepared production code

NEW `core/runtime-reasoning/src/main/kotlin/app/lifeos/core/runtime/reasoning/KnowledgeGapDetector.kt`

- L9–17: `KnowledgeGapKind`: explicit unknown, search truncation/no-complete-state, missing/incomplete/unverified outcome, verified failure pattern.
- L19–68: `KnowledgeGapDetectionInput`; binds source cycle + exact B366/B367/B368/B371/B372/B379 lineage and fails closed on mismatches.
- L70–154: immutable `KnowledgeGap`; canonical refs/severity/recommended existing `EvidenceActionKind` values; `executionAuthority=false`, `currentCycleWorldMutationAllowed=false`.
- L156–179: `KnowledgeGapDetectionResult`; deterministic ordering/identity and no execution authority.
- L181–186: architectural invariant separating epistemic B380 gaps from B152 extension gaps.
- L187–352: `KnowledgeGapDetector.detect`; emits:
  - B366 explicit UNKNOWN gaps,
  - B368 truncated search gap,
  - B368 no-complete-state gap,
  - B372 missing/incomplete/unverified outcome gaps,
  - B379 verified failure-pattern gaps.
  It deliberately emits no gap for verified WITHIN/OUTSIDE outcomes directly; OUTSIDE enters via B379 failure learning.
- L354–358: deterministic severity-first gap order.
- L360–382: deterministic knowledge-gap fingerprint.

## Prepared tests

NEW `core/runtime-reasoning/src/test/kotlin/app/lifeos/core/runtime/reasoning/KnowledgeGapDetectorTest.kt`

- L30–50: explicit B366 unknown becomes non-executable gap with evidence recommendations.
- L53–76: bounded B368 truncation exposes SEARCH_TRUNCATED + NO_COMPLETE_REASONING_STATE.
- L79–93: cross-lineage B367/B368 substitution fails closed.
- L96–115: missing, incomplete and unverified B372 outcomes remain distinct gaps.
- L118–147: verified B379 failure learning becomes SIMULATION / SAFE_SANDBOX_EXPERIMENT-oriented gap.
- L150–171: B372 report must match exact B371 expectation model and source cycle.
- L174–185: identical exact input produces deterministic result.
- L188–198: outcome detection helper.
- L200–229: deterministic B366 problem fixture.
- L231–253: B367 two-alternative hypothesis seed fixture.
- L255–321: B370/B371/B372 outcome fixture covering missing/incomplete/unverified/outside states.
- L323–334: observed-outcome helper.
- L336–346: fixture enums/data.

## Pre-implementation verification

Verified before writing:
1. no existing `KnowledgeGapDetector`, `KnowledgeGapDetectionInput`, or `KnowledgeGap` collision;
2. `ExtensionGapDetector` is already a pure detector for architecture/world-model gaps and remains untouched;
3. `EvidenceActionKind` already contains LOCAL_RETRIEVAL, MEMORY_LOOKUP, DEEP_SEARCH, SOURCE_REFRESH, SIMULATION, SAFE_SANDBOX_EXPERIMENT, ASK_USER, ABSTAIN;
4. B366 exposes explicit UNKNOWN nodes;
5. B368 exposes `truncated` and `completeStates`;
6. B372 keeps missing/incomplete/unverified states explicit;
7. B379 exposes exact verified failure evidence;
8. B380 remains in `:core:runtime-reasoning`, adding nothing to the 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-reasoning:test --tests 'app.lifeos.core.runtime.reasoning.KnowledgeGapDetectorTest'`
2. `./gradlew :core:runtime-reasoning:test`
3. Core Fast after B379/B378 promotion order.
