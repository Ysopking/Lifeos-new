# B399 — Recursive Research Planner — exact implementation plan

Base head: `aaa0b740d301c0c7b926b942db8f6b6cb4be59e5`
Branch: `b399-recursive-research-planner-v1`
New module: `:core:runtime-research`

## Reuse / non-duplication contract

B399 does not build a second search engine:
- B380 `KnowledgeGapDetector` remains authoritative for epistemic gap detection.
- `DeepSearchPlannerV2` remains authoritative for actual bounded search, source authorization/recheck, checkpointing, retry, work/depth/time budgets, frontier expansion and resolution.
- B399 plans *missions over gaps* and bounded recursive follow-up rounds only.
- B398 remains Web watch observation; B399 does not acquire pages or schedule watches.
- B400 will own information-gain prioritization; B399 uses deterministic severity/lineage ordering only.

The new `:core:runtime-research` module is a leaf over `:core:runtime-reasoning` and `:core:runtime-deepsearch`. It introduces no dependency cycle and adds no files to the bounded `core/runtime` monolith.

## Module wiring

NEW `core/runtime-research/build.gradle.kts`
- Kotlin/JVM, JDK 17.
- `implementation(project(":core:runtime-reasoning"))`.
- `implementation(project(":core:runtime-deepsearch"))`.
- `testImplementation(kotlin("test"))`.
- JUnit platform.

MODIFY `settings.gradle.kts`
- insert `":core:runtime-research",` next to the existing runtime-reasoning module.

MODIFY `.github/architecture-budget.json`
- add `core/runtime-research/build.gradle.kts` with direct dependencies:
  - `:core:runtime-deepsearch`
  - `:core:runtime-reasoning`
- leave `runtime_monolith_max_kotlin_files = 505` unchanged.

MODIFY `.github/scripts/ci-core-fast.sh`
- after the existing runtime-deepsearch test gate insert:
  - `step "gate 03b2: runtime-research unit tests"`
  - `./gradlew :core:runtime-research:test --stacktrace`.

## Prepared production code

NEW `core/runtime-research/src/main/kotlin/app/lifeos/core/runtime/research/RecursiveResearchPlanner.kt`

- L16–25: content-derived `RecursiveResearchMissionId`.
- L27–33: mission states: PLANNED / RESOLVED / FOLLOW_UP_PLANNED / TERMINAL_UNRESOLVED / BLOCKED.
- L35–56: `RecursiveResearchBudget`; global max depth/mission count plus exact existing `DeepSearchBudget` per mission.
- L58–118: immutable `RecursiveResearchMission`; exact root-gap lineage, parent/depth, DeepSearch request, result status/fingerprint and no execution/current-cycle mutation authority.
- L120–154: immutable `RecursiveResearchPlan`; exact B380 detection fingerprint, canonical missions, non-research gaps, budget-deferred research gaps and no execution/permission/current-cycle mutation authority.
- L156–158: architecture invariant: B399 plans rounds only and never executes/authorizes/bypasses DeepSearch budgets.
- L159–355: `RecursiveResearchPlanner`:
  - `seed` accepts one exact B380 result and source cycle;
  - only gaps recommending existing `DEEP_SEARCH` or `SOURCE_REFRESH` become research missions;
  - root mission request identity includes exact gap id/semantic key/related refs and the existing per-mission DeepSearch budget;
  - global mission cap is explicit and overflow is retained as `budgetDeferredGapIds`;
  - `advance` accepts exact mission→DeepSearchResult lineage only;
  - RESOLVED terminates;
  - PERMISSION_BLOCKED / NO_USABLE_SOURCE terminate as BLOCKED and are never recursively bypassed;
  - WORK_BUDGET_EXHAUSTED / TIME_BUDGET_EXHAUSTED terminate instead of silently minting a fresh budget;
  - only genuine UNRESOLVED may create one child when maxDepth and maxMissions permit;
  - child DeepSearch request identity includes exact parent mission and recursion depth, preventing result substitution across levels;
  - exact completed-result replay is idempotent; changed replay fails closed.
- L357–374: deterministic mission identity helper.
- L376–378: researchability predicate reusing existing `EvidenceActionKind`.
- L380–384: deterministic gap ordering.
- L386–390: deterministic mission ordering.
- L392–394: bounded deterministic query normalization.
- L396–424: canonical immutable plan creation.
- L426–441: plan fingerprint across exact mission state and deferred/non-research gap sets.
- L443–456: deterministic DeepSearch-result fingerprint over request/status/best/alternatives/evidence ids/trace/work/blocked/failed state.
- L458–478: dependency-free length-delimited SHA-256 helper.
- L480: 2048-character query bound.

## Prepared tests

NEW `core/runtime-research/src/test/kotlin/app/lifeos/core/runtime/research/RecursiveResearchPlannerTest.kt`

- L23–42: only B380 DEEP_SEARCH/SOURCE_REFRESH gaps create research missions; simulation-only gap remains non-research and all authority flags stay false.
- L45–56: global mission cap records eligible overflow explicitly.
- L59–77: UNRESOLVED creates exactly one depth+1 child with exact parent/root lineage and same bounded DeepSearch budget.
- L80–90: RESOLVED is terminal and creates no child.
- L93–105: PERMISSION_BLOCKED / NO_USABLE_SOURCE remain terminal instead of bypassing authorization.
- L108–126: work/time exhaustion remains terminal instead of refreshing mission budget.
- L129–146: maxDepth=0 converts UNRESOLVED to terminal.
- L149–158: wrong DeepSearch request lineage fails closed.
- L161–175: exact result replay is idempotent; changed replay fails closed.
- L178–185: same exact B380 input yields deterministic plan identity.
- L188–287: deterministic B380 gap/detection and DeepSearch result/branch fixtures.

## Pre-implementation verification

Before writing:
1. verify no existing `RecursiveResearchPlanner`, `RecursiveResearchMission`, `RecursiveResearchPlan`, or `:core:runtime-research` collision;
2. verify `DeepSearchPlannerV2` already owns expansion, authorization, retry, checkpoint, depth/work/time budgets and final resolution;
3. verify B380 exposes exact gap ids/source-cycle lineage, severity and existing `EvidenceActionKind` recommendations;
4. verify `DeepSearchRequest.id` includes query/context/budget, so adding exact parent/depth context makes recursive request substitution detectable;
5. verify no dependency from existing modules to `:core:runtime-research`, keeping it a leaf and preventing cycles;
6. verify B399 does not call `DeepSearchPlannerV2.search`, perform Web acquisition, schedule timers, authorize sources, or mutate world state;
7. verify permission/source failures and exhausted per-mission budgets are terminal in B399 rather than recursively bypassed;
8. verify no file is added to the bounded 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-research:test --tests 'app.lifeos.core.runtime.research.RecursiveResearchPlannerTest'`
2. `./gradlew :core:runtime-research:test`
3. Core Fast.
4. Clean restack after B398/B397/B396/B395/B394/B393/B392/B391/B380 promotion, then Debug / Recovery / Product Gold before merge.
