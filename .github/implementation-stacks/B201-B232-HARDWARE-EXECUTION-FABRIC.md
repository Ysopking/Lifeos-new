# B201-B232 — Hardware Execution Fabric stack

Base verified against `main` at:

`5a541ef3f9b2f7e12317c08ce1973777e1ee3655`

Branch:

`b201-b232/hardware-execution-fabric`

## Verified current facts

- `CognitiveWorkerPool` and `PooledTaskScheduler` already exist and dispatch concurrently. This stack extends that authority; it does not add a second scheduler.
- The current static topology cap is exactly in `LifeOsKernelFactory.kt:592-595`; ACTIVE is capped to three workers.
- `HardwareStateSnapshot` is at line 27, `computeHeadroom()` at 103, `HardwareAdaptiveBudgetPlan` at 197, and `HardwareAdaptiveResourceOptimizer` at 226.
- `AndroidHardwareStateReader.read()` still writes `cpuLoadFraction = null` at line 40.
- `ExecutionMeasurement` and `SoftCostEstimate` already exist in `ResourceIntelligenceCompletion.kt`; no second telemetry model is allowed.
- `LifeTask` / `TaskDraft` / `TaskCodec` are still codec v2, and `TaskType` has no `EXECUTE_WORK_NODE`.
- `BootSnapshotLoader` currently reads its six durable sources sequentially.
- `ThoughtMatrix` is already durably rehydrated through `EncryptedThoughtMatrixStateRepository`; `LifeOsKernel.completeBoot()` nevertheless replays all hot/warm Photons at line 948.
- `ContinuousLearningCoordinator` still owns five projections and commits the source watermark strictly after each event.
- `LearningAdaptationTargetKind` currently contains only `PROVIDER_RELIABILITY` and `FIELD_WEIGHT`.
- `ControlledEvolutionSubjectKind.STRATEGY` already exists.
- `PrivateEscalationRuntime` currently fails closed at L0/L1/L4/L5/L7.
- There is currently no `core/runtime/.../execution` package, no WorkGraph repository, no ExecutionSample repository, and no ExecutionStrategy repository.
- The current Product Gold device PASS seals are appended in `.github/workflows/product-gold.yml`, not in `ci-product-gold.sh`.

## Mandatory design corrections before implementation

### C1 — split evidentiary hardware identity from scheduling identity

Current `HardwareStateSnapshot.fingerprint()` includes `observedAt`; therefore it changes on every read even when hardware state is materially unchanged. Keep this exact observation fingerprint for provenance and World evidence. Add a second quantized/stable execution-capacity fingerprint for scheduling epochs. B201/B202 must use that execution fingerprint when deciding whether a plan materially changed.

### C2 — do not duplicate `availableProcessors`

`Runtime.availableProcessors()` already represents the process-visible logical processor count. Keep it for compatibility and use it as the initial logical processor count. If topology needs a richer model, put that in `HardwareExecutionTopology` rather than adding a duplicate scalar with ambiguous semantics.

### C3 — separate quota optimization from execution planning

`HardwareAdaptiveResourceOptimizer` is currently a V16 hard-quota shrinker used by existing callers. Do not force World/Strategy/Learning dependencies into it. Add a separate `HardwareExecutionPlanner` / `HardwareExecutionPlanProvider` which consumes:
hardware snapshot + active strategy + learned profile + current productive World identity + hard quota result.

The existing budget plan remains a compatible lower-level primitive.

### C4 — Boot parallel reads must not share mutable failure state

When B214 parallelizes the six `BootSnapshotLoader` source reads, each `async` branch must collect its own failures. Merge and canonicalize failures after `awaitAll`. Sharing the current mutable `failures` list across async branches would introduce a race and nondeterministic ordering.

### C5 — reuse the existing durable ThoughtMatrix state

B215 must not create a second ThoughtMatrix head/ledger. `ThoughtMatrixDurableState` already stores the V2 snapshot, and each thought node provenance carries source Photon revision. Delta boot therefore compares current Photon revisions against that durable snapshot and applies only missing/newer revisions. The full `runtimePhotons.forEach { matrix.influence(it) }` loop can then be removed.

### C6 — scheduler capacity needs class-aware occupancy, not only worker exclusion

The current scheduler tracks only `busyWorkers`. Hardware class limits require an atomic assignment map such as worker -> execution class, so the same plan can independently enforce total, compute, IO, background and maintenance limits. Slot selection alone is insufficient when a slot supports multiple classes.

### C7 — strategy evolution must not fake generated-tool subjects

`ShadowEvaluationEngine` and `ControlledEvolutionCoordinator` are presently typed around generated tools/providers. Execution Strategy is already a generic `ControlledEvolutionSubjectKind.STRATEGY`, but strategy shadow/canary execution must use an adapter or extracted generic evolution primitive. Do not manufacture fake tool IDs/provider descriptors merely to reuse tool-only APIs.

### C8 — Product Gold sealing location

JVM/pre-emulator presence checks belong in `ci-gold-coverage-contract.sh` / `ci-product-gold.sh`. Device PASS seals for the two new Android GOLD tests belong in the final `Seal Product Gold evidence` step of `.github/workflows/product-gold.yml`.

## One implementation stack

### S1 — Hardware sensing, execution plan and measurement foundation
Blocks: B201-B203

Primary files:
- `core/runtime/.../resource/HardwareAdaptiveResourceIntelligence.kt`
- `app/.../kernel/AndroidHardwareStateReader.kt`
- new `app/.../kernel/AndroidCpuLoadSampler.kt`
- new `core/runtime/.../resource/HardwareExecutionPlan.kt`
- new `core/runtime/.../resource/HardwareExecutionPlanner.kt`
- `core/runtime/.../resource/ResourceIntelligenceCompletion.kt`
- new `core/runtime/.../resource/ExecutionSampleRepository.kt`
- new `core/data/.../resource/EncryptedExecutionSampleRepository.kt`

Rules:
- process CPU load from process CPU-time delta / wall delta / visible processors;
- nullable IO pressure only when a reliable local signal exists; unknown is not treated as free capacity;
- memory headroom remains sourced from `ActivityManager.MemoryInfo`;
- execution-capacity fingerprints are quantized; observation fingerprints remain exact;
- hard V16 quotas can only shrink, never expand.

Gate:
`HardwareAdaptiveResourceIntelligenceTest` + new `HardwareExecutionPlanTest` + repository restart/corruption tests.

### S2 — Durable WorkGraph semantic layer
Blocks: B204-B208

New package:
`core/runtime/src/main/kotlin/app/lifeos/core/runtime/execution/`

Core:
- `WorkGraph.kt`
- `WorkDecomposer.kt`
- `WorkGraphRepository.kt`
- `WorkResultCache.kt`
- `WorkGraphCoordinator.kt`

Data:
- `EncryptedWorkGraphRepository.kt`
- `EncryptedWorkResultCacheRepository.kt`

Task transport:
- add optional WorkGraph binding to `LifeTask` and `TaskDraft`;
- add `TaskType.EXECUTE_WORK_NODE`;
- bump `TaskCodec.VERSION` 2 -> 3 while preserving v1/v2 decode;
- `DurableTaskEngine.submit()` copies the binding exactly.

Persistence rules:
- immutable revision segments / CAS;
- payload ID + revision must match its physical segment path after decrypt, matching the current hardened ledger pattern;
- pure exact-result cache only;
- no external effects, messages, file writes or irreversible actions in the result cache.

Gate:
`WorkGraphDeterminismTest`, `WorkGraphRecoveryTest`, `WorkDecomposerGranularityTest`, `WorkResultCacheTest`, task codec backward-compatibility tests.

### S3 — Hardware-aware scheduler and cognitive microtasks
Blocks: B209-B213

Primary files:
- `CognitiveWorkerPool.kt`
- `LifeOsKernelFactory.kt`
- `CognitiveTaskWorker.kt`
- `DurableCognitionDispatcher.kt`
- new WorkNode executor registry/result types
- new cognition decomposer/field executor/reducer

Scheduler:
- slots advertise supported execution classes;
- execution plan provider governs active slot envelope;
- busy assignments track execution class;
- TaskStore remains lease/ownership authority;
- WorkGraph repository remains dependency/result authority.

Topology:
- remove the three-worker ACTIVE cap;
- construct physical slots from visible processors without assuming every core is simultaneously usable;
- keep at least one interactive slot;
- execution plan dynamically contracts/expands usable capacity.

Determinism:
Field results reduce only by stable `FieldId`, Photon revision and WorkNodeId; completion order never participates.

Hard gate:
same semantic input + same equation + same module snapshots + same strategy must yield one identical final fingerprint at 1/2/4/8 workers.

### S4 — Boot and startup delta execution
Blocks: B214-B216

- parallelize the six independent boot source reads using local result/failure bundles;
- canonicalization and generation hashing remain serial and deterministic;
- replace full ThoughtMatrix replay with durable revision delta application;
- move `reconcilePersisted`, memory rebuild/consolidation, snapshot refresh and future-planning reconsideration out of pre-ready kernel construction;
- add a post-`COGNITIVE_STATE_READY` optimization/warmup stage or submit equivalent WorkGraphs only after the durable scheduler is available;
- normal startup must not call global `reconsiderAll()`.

Gate:
`BootSnapshotLoaderTest`, `BootCoordinatorTest`, startup-composition tests, exact BootGenerationId parity.

### S5 — Execution Fabric health, self-healing and escalation
Blocks: B217-B220

- add `ExecutionFabricHealthMonitor` using the existing HealthGraph;
- preserve current worker health nodes;
- add typed `ExecutionFabricRepairContext`;
- abstract direct `RecoveryAction.execute()` through `RecoveryActionExecutionAuthority`;
- atomic repairs remain atomic; bounded diagnostic/reconciliation work may become WorkGraphs;
- add execution-specific plan bindings to `PrivateSelfHealingRuntime`;
- enable L0/L1/L4/L5/L7 only for typed execution-fabric contexts; all unrelated health nodes remain fail-closed.

Gate:
partial child-node recovery, exact strategy rollback context, no duplicate completed child execution after restart.

### S6 — Execution evidence -> bounded learning
Blocks: B221-B226

- add `EXECUTION_MEASUREMENT` and `EXECUTION_OPTIMIZATION_REVIEW`;
- add `LearningExecutionUpdater` and sixth projection;
- add execution adaptation target kinds to the existing `LearningAdaptationTargetKind`;
- build transparent `ExecutionCostModel` from persisted samples;
- parallelize projections for one event through a WorkGraph, reduce deterministically, then perform exactly one source-watermark CAS;
- classify telemetry as PRODUCTIVE vs CONTROL_PLANE and exclude the execution-learning control loop from its own training input;
- learning may tune only numeric values inside the active strategy hard bounds.

Gate:
exact watermark behavior under process death, bounded adaptation drift, no self-learning feedback loop.

### S7 — Versioned ExecutionStrategy evolution
Blocks: B227-B230

- add immutable `ExecutionStrategySnapshot`;
- use existing `ControlledEvolutionSubjectKind.STRATEGY` and `StrategyEvolutionAdmissionGate`;
- add strategy-specific shadow adapter with paired identical WorkGraphs;
- semantic fingerprint mismatch is an unconditional hard reject;
- extend canary evidence with execution/hardware measurement through a generic strategy canary adapter or extracted shared evolution authority;
- add encrypted strategy repository, head and activation authority;
- promotion requires admission + shadow + canary + promotion evidence;
- L5 self-healing rollback restores the exact predecessor head.

Gate:
`ExecutionStrategyAuthorityTest`, `ExecutionStrategyRollbackTest`, faster-but-different-result rejection, restart-safe canary evidence.

### S8 — Composition, recovery and GOLD closure
Blocks: B231-B232

Factory order:
hardware reader -> strategy repository/authority -> sample repository -> learning adaptations -> execution cost model -> execution planner -> WorkGraph/result cache -> executors -> coordinator -> worker pool -> scheduler -> fabric health -> learning -> evolution -> self-healing/escalation.

Critical rehydration before productive scheduling:
- Protection
- ExecutionStrategyHead
- WorldEquationHead
- productive World head/model state
- active WorkGraphs
- resource reservations
- task leases

Then noncritical learning/cache/thought reconciliation.

Recovery rule:
completed WorkNode result fingerprints are reused; only missing runnable children are rematerialized.

## GOLD/CI closure

Add JVM/core tests:
- `WorkGraphDeterminismTest`
- `WorkGraphRecoveryTest`
- `WorkDecomposerGranularityTest`
- `WorkResultCacheTest`
- `HardwareExecutionPlanTest`
- `ExecutionCostModelTest`
- `ExecutionLearningProfileTest`
- `ExecutionStrategyAuthorityTest`
- `ExecutionStrategyRollbackTest`
- `ExecutionFabricSelfHealingTest`

Add Android tests:
- `HardwareExecutionFabricGoldDeviceTest`
- `ExecutionStrategyRollbackGoldDeviceTest`

Update:
- `ci-gold-coverage-contract.sh`: require new JVM/device tests and emulator suite names.
- `android-emulator-recovery.sh`: seed execution fabric + strategy before the single force-stop; recover after the same cold restart.
- `ci-product-gold.sh`: pre-emulator presence/integration checks only.
- `product-gold.yml`: append device seals:
  - `hardware_execution_fabric=PASS`
  - `microtask_exact_recovery=PASS`
  - `parallel_determinism=PASS`
  - `execution_learning=PASS`
  - `execution_evolution=PASS`
  - `execution_self_healing=PASS`
  - `execution_strategy_rollback=PASS`

## Stack invariants

1. REUSE before EXECUTE.
2. DELTA before REBUILD.
3. SPLIT only when predicted useful work exceeds scheduling overhead by the active bounded threshold.
4. Independent nodes may run concurrently; dependencies remain strict.
5. Parallelism may never alter the semantic result fingerprint.
6. Learning tunes bounded parameters only; structural strategy changes require Controlled Evolution.
7. Only activation authority can change the active ExecutionStrategy head.
8. Self-healing may roll back only to a durably proven predecessor.
9. TaskStore remains lease/worker authority; WorkGraphStore remains semantic dependency/result authority.
10. No execution optimization widens OwnerPolicy, external-effect, protected-root, WorldEquation or resource hard-quota authority.

## Commit order

1. `B201-B203: execution resource foundation`
2. `B204-B208: durable work graph`
3. `B209-B213: adaptive scheduler and cognition microtasks`
4. `B214-B216: boot and startup deltas`
5. `B217-B220: execution fabric self-healing`
6. `B221-B226: execution learning loop`
7. `B227-B230: controlled execution strategy evolution`
8. `B231-B232: composition recovery and gold seal`

Each commit must keep core-fast green before the next commit is built on it. Product Gold is required only on the final exact stack head, with targeted emulator recovery gates added progressively once their seed/recover fixtures exist.
