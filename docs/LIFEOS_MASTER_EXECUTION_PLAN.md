# LIFEOS — Master Execution Plan

Baseline: `main@d01710a97960e3c26bd42eadc21b4dbfa706b6c8` (2026-09-08)

This document is the authoritative implementation order for LIFEOS-new. It consolidates the previously planned runtime/health/boot/cognition/tool-genesis work with the newer universal field architecture. It is deliberately execution-oriented: every block has concrete source targets, planned line ownership, tests, activation gates and a Definition of Done.

## Line-precision convention

Existing files are addressed by **file + symbol anchor** because later merged blocks shift physical line numbers. New files are planned with stable **line ownership ranges** before implementation. When a file is created, its implementation should preserve the listed section order so review can map the plan directly to code. If a later refactor shifts exact physical lines, the named symbol remains the binding anchor.

Status vocabulary:
- `DONE` — merged and productive on `main`.
- `DONE/ISOLATED` — merged but intentionally not switched into the productive path yet.
- `HARDEN` — implementation exists; acceptance gaps remain.
- `NEW` — not yet implemented.
- `GATED` — may exist only behind explicit verification/promotion policy.

Global invariants for every block:
1. offline-first by default; no hidden network dependency;
2. immutable source Photon/Evidence revisions;
3. deterministic IDs for deterministic inputs;
4. durable tasks keep owner/lease/checkpoint safety;
5. no silent tie-breaking where uncertainty remains;
6. generated code is never equivalent to activated code;
7. security/crypto/task-ownership/recovery/signing invariants are not self-replaced;
8. every productive block must pass `gradle test :app:lintDebug :app:assembleDebug --stacktrace` before merge.

---

# A. Existing foundation inventory

## A00 — Build, app shell and module graph — DONE

Existing targets:
- `.github/workflows/android.yml` — CI gate and Debug APK artifact.
- `settings.gradle.kts` — module inclusion.
- `app/src/main/java/app/lifeos/next/*` — Android shell, ViewModel, voice capture and kernel entry.

Preserve:
- Debug-only development gate.
- Java 17 / Gradle CI contract.
- no release-signing automation in autonomous development path.

DoD: existing CI remains green after every following block.

## A01 — Photon/task/checkpoint durability — DONE

Existing targets:
- `core/model/.../Photon.kt`, `PhotonCodec.kt`, `PhotonRepository.kt`.
- `core/model/.../task/*`.
- `core/model/.../checkpoint/*`.
- `core/data/.../EncryptedPhotonStore.kt`.
- `core/data/.../task/EncryptedTaskRepository.kt`.
- `core/data/.../checkpoint/EncryptedCheckpointRepository.kt`.
- `core/runtime/.../tasks/*`.
- `core/runtime/.../checkpoints/*`.

Preserve:
- AES-GCM protected durable state.
- task CAS/ownership semantics.
- lease heartbeat/recovery.
- field-level checkpoint resume and signature compatibility.

DoD: no later autonomous subsystem may bypass durable task ownership for long-running work.

## A02 — Runtime Health Core — DONE + HARDEN

Existing targets:
- `core/runtime/.../health/HealthModels.kt`.
- `FailureClassifier.kt`.
- `HealthGraph.kt`.
- `CircuitBreaker.kt`.
- `QuarantineRegistry.kt`.
- `HealthGate.kt`.
- `RecoveryCoordinator.kt`.
- `core/runtime/.../recovery/*`.

Already implemented:
- `UNKNOWN`, `HEALTHY`, `DEGRADED`, `UNHEALTHY`, `RECOVERING`, `QUARANTINED`, `DISABLED` state model.
- typed observations/failure classification.
- circuit breaker with generation-safe permits.
- quarantine and recovery coordination.

Hardening targets:
- productive Health observer coupling from worker/runtime/field/store outcomes.
- durable protection/safe-mode reason persistence.
- component-specific repair probes.
- explicit escalation integration from section R below.

## A03 — Boot/Rehydration — DONE + HARDEN

Existing targets:
- `core/runtime/.../boot/BootContracts.kt`.
- `BootModels.kt`.
- `BootDefaults.kt`.
- `BootCoordinator.kt`.
- `PhotonRehydrator.kt`.
- `app/.../kernel/LifeOsKernelFactory.kt` and `LifeOsKernel.kt`.

Already implemented:
- integrity/store probes.
- lease recovery.
- HOT/WARM/COLD Photon rehydration.
- deterministic runtime activation after boot validation.

Hardening targets:
- active conversation/project/goal reconstruction.
- capability and worker registry rehydration.
- field snapshot rehydration.
- durable BootCognitionReport photon.
- learning watermark and missed-event analysis.

## A04 — Continuous Cognition — DONE + HARDEN

Existing targets:
- `core/runtime/.../cognition/CognitiveModels.kt`.
- `CognitiveScheduler.kt`.
- `ContinuousCognitionEngine.kt`.
- `DurableCognitionDispatcher.kt`.
- `DurableCognitiveTriggerSink.kt`.
- `CognitiveOutcomePipeline.kt`.
- `CognitiveEventJournal.kt`.
- `PhotonTransaction.kt`.
- `SalienceEngine.kt`.

Already implemented:
- durable dispatch of accepted cognitive work.
- idempotent processing ledger/event journal.
- bounded trigger feedback for reevaluation/convergence.

Hardening targets:
- field-aware cognition work items.
- persistent learning watermark.
- explicit observation/inference/user-confirmed/outcome provenance class.
- escalation integration.

## A05 — Capability / Tool Workshop foundation — DONE + HARDEN

Existing targets:
- `core/runtime/.../capability/CapabilityModels.kt`.
- `CapabilityRegistry.kt`.
- `CapabilityRouter.kt`.
- `CapabilityComposer.kt`.
- `ToolWorkshopModels.kt`.
- `ToolWorkshopCoordinator.kt`.
- `GeneratedToolRegistry.kt`.
- `GeneratedToolSandbox.kt`.
- `GeneratedToolTrialLifecycle.kt`.
- `GeneratedToolGenesisCoordinator.kt`.

Already implemented:
- structured capabilities and gap detection.
- generated-tool sandbox admission.
- trial lifecycle and explicit promotion.
- low-trust generated-tool registration only after promotion.

Hardening targets:
- BuildStudio source/build bridge.
- reproducible build provenance.
- hot-swap candidate interface.
- field/cognition evidence feeding promotion decisions.

## A06 — Language/acoustic field stack — DONE + one open extension

Existing targets:
- `core/language/*`.
- Android foreground voice capture.

Already implemented:
- deterministic language understanding.
- linguistic fields, phonetic/grapheme/compound feedback.
- acoustic phoneme and lexical bidirectional fields.
- local voice capture.

Open existing PR work:
- multiword phrase-field decoder must remain isolated until its own CI/merge gate is satisfied.

## A07 — Image/scene/MMSI stack — DONE

Existing targets:
- `core/image/*`.
- `core/image-native/*`.
- `core/scene/*`.
- app image-photon display.

Preserve:
- deterministic CPU reference path.
- native/Vulkan paths as optimized implementations with fallback.
- encrypted binary asset store and Photon provenance.

## A08 — Universal Field Core — DONE/ISOLATED + current hardening

Merged:
- `core/field/FieldIds.kt`.
- `FieldEvidence.kt`.
- `FieldGraphModels.kt`.
- `FieldHypothesis.kt`.
- `FieldContext.kt`.
- `FieldForceCalculator.kt`.
- `FieldState.kt`.
- `DomainField.kt`.
- `FieldConvergenceEngine.kt`.

Current open implementation block:
- field-set registry.
- complete physics input fingerprints.
- immutable trace.
- content-addressed snapshot/codec/rehydration.
- snapshot registry.

Activation rule: do not switch existing `CognitiveRuntime` to universal field execution until B01-B03 are green.

---

# B. Universal field completion and runtime coupling

## B01 — Field trace/snapshot/registry completion — IN PROGRESS

### New/updated source line map

`core/field/.../DomainFieldRegistry.kt`
- lines 1-18: canonical `DomainFieldKey`.
- lines 19-61: deterministic registry/resolve/override semantics.
- lines 62-86: field-set fingerprint.

`core/field/.../FieldTrace.kt`
- lines 1-35: seed trace structures.
- lines 36-85: iteration/domain evaluation trace.
- lines 86-end: `FieldTrace` fingerprint contract.

`core/field/.../FieldSnapshot.kt`
- lines 1-45: snapshot models and content ID.
- lines 46-end: deterministic bounded codec.

`core/field/.../FieldFingerprint.kt`
- lines 1-105: complete graph/evidence/hypothesis/config/force physics input fingerprint.
- lines 106-end: canonical floating-point/config helpers.

`core/field/.../FieldSnapshotRegistry.kt`
- lines 1-20: `RehydratedFieldState`.
- lines 21-40: registry contract.
- lines 41-end: verified in-memory reference registry.

`FieldConvergenceEngine.converge`
- replace coarse input fingerprint with `request.physicsFingerprint(...)`.
- emit trace and snapshot for every terminal status.

Tests:
- field version changes run identity.
- convergence config changes run identity.
- force calculator weights change run identity.
- identical physics repeats same run/trace/snapshot IDs.
- codec exact round trip.
- registry idempotency and rehydration.

DoD: PR CI green; merge; no runtime switch.

## B02 — Durable encrypted FieldSnapshot store — NEW

New file: `core/data/src/main/kotlin/app/lifeos/core/data/field/EncryptedFieldSnapshotRepository.kt`
- lines 1-35: repository contract adapter and storage key model.
- lines 36-90: AES-GCM encode/write with atomic replacement.
- lines 91-145: read/decrypt/codec verification.
- lines 146-190: list/latest-by-domain index.
- lines 191-end: corruption-preserving failure paths.

New model interface: `core/field/.../FieldSnapshotRepository.kt`
- lines 1-25: put/get/latest/list/delete contract.

Tests:
- round trip.
- wrong key/corruption rejection.
- write failure cannot acknowledge success.
- latest deterministic selection.
- old readable snapshots survive corrupt newer entry.

DoD: pure snapshot persistence is durable; still no productive runtime switch.

## B03 — Runtime field adapter — NEW

New file: `core/runtime/.../field/UniversalFieldRuntimeAdapter.kt`
- lines 1-35: request adapter contracts.
- lines 36-95: Photon/context -> field request construction.
- lines 96-145: convergence call with HealthGate.
- lines 146-195: snapshot persistence/checkpoint link.
- lines 196-end: bounded result projection back to cognition.

New file: `core/runtime/.../field/FieldRuntimeModels.kt`
- lines 1-80: typed input/result/failure models.

Update anchors:
- `CognitiveTaskWorker` — insert adapter behind feature flag/strategy interface; legacy path remains fallback.
- `CognitiveOutcomePipeline` — add field run/snapshot provenance.
- `LifeOsKernelFactory` — construct registry/store/adapter.

Tests:
- same task retry reuses compatible field snapshot.
- stale Photon revision cannot overwrite newer field output.
- unhealthy/quarantined field is gated.
- failure leaves durable task retry semantics intact.

DoD: shadow execution only; productive output still comes from current runtime path.

## B04 — Shadow equivalence and cutover gate — NEW

New file: `core/runtime/.../field/FieldShadowValidator.kt`
- lines 1-60: legacy vs universal-field observation capture.
- lines 61-120: semantic equivalence/difference classification.
- lines 121-end: bounded evidence ledger and cutover recommendation.

Gate:
- minimum deterministic replay corpus.
- zero task-ownership regressions.
- zero source Photon mutation.
- unresolved states remain unresolved.

DoD: only after passing may universal field output become authoritative for selected domains.

---

# C. Health, recovery and full escalation

## C01 — Productive Health observability completion — HARDEN

Update anchors:
- `CognitiveTaskWorker` success/failure boundaries.
- `DurableRuntimeStateBridge` observer composition.
- `RuntimeSupervisor` state changes.
- `UniversalFieldRuntimeAdapter` field failures.
- encrypted repositories store failures.

New file: `core/runtime/.../health/HealthTaskExecutionObserver.kt`
- lines 1-40: structured task outcome mapping.
- lines 41-95: field/worker/store/runtime node routing.
- lines 96-end: best-effort observer isolation.

DoD: observability can never convert a successful task into failure.

## C02 — Durable protection/safe-mode state — NEW/HARDEN from prior draft

New files:
- `core/model/.../health/RuntimeProtectionState.kt` lines 1-100.
- `core/data/.../health/EncryptedProtectionStateRepository.kt` lines 1-220.
- `core/runtime/.../health/ProtectionCoordinator.kt` lines 1-180.

State fields:
- generation/revision.
- active reasons.
- affected node IDs.
- enteredAt/lastVerifiedAt.
- actor/provenance.
- resumability policy.

DoD:
- process death preserves quarantine/safe mode.
- corrupt protection store is not silently reset.
- user-requested resume performs probes first.

## C03 — Repair probes — NEW

New file: `core/runtime/.../health/RepairProbe.kt`
- lines 1-35: probe contract/result.
- lines 36-75: store probe.
- lines 76-115: worker probe.
- lines 116-155: field probe.
- lines 156-195: capability/tool probe.
- lines 196-end: composite repair evidence.

DoD: RecoveryCoordinator decisions are evidence-backed, not time-only.

---

# D. Boot Cognition and continuous learning

## D01 — BootSnapshotLoader — NEW

New file: `core/runtime/.../boot/BootSnapshotLoader.kt`
- lines 1-55: source contracts and generation identity.
- lines 56-115: Photon/task/checkpoint load.
- lines 116-170: active conversation/project/goal projections.
- lines 171-220: capability/worker/tool state.
- lines 221-265: field snapshot state.
- lines 266-end: consistent snapshot result and partial-read failures.

DoD: ViewModel memory is never the source of boot truth.

## D02 — BootIntegrityScanner — NEW

New file: `core/runtime/.../boot/BootIntegrityScanner.kt`
- lines 1-50: finding/severity/repairability models.
- lines 51-110: vault/schema checks.
- lines 111-165: task/checkpoint invariants.
- lines 166-215: duplicate/orphan/stale projection checks.
- lines 216-end: capability/tool/field snapshot integrity.

Rule: report first; destructive repair is never default.

## D03 — Boot context + delta analysis — NEW

New files:
- `BootContextRehydrator.kt` lines 1-220.
- `BootDeltaAnalyzer.kt` lines 1-240.

Must restore/compare:
- active conversation/project/goal.
- latest relevant Photons and relations.
- missed durable tasks/automations.
- field snapshot watermark.
- capability/permission/tool version changes.

DoD: first post-restart input like `weiter` can resolve against durable context.

## D04 — BootCognitionReport photon — NEW

New file: `core/runtime/.../boot/BootCognitionReport.kt`
- lines 1-100: report model.
- lines 101-160: deterministic codec/fingerprint.
- lines 161-end: Photon factory with provenance.

DoD: every nontrivial boot produces inspectable durable evidence.

## D05 — ContinuousLearningCoordinator — NEW

New file: `core/runtime/.../learning/ContinuousLearningCoordinator.kt`
- lines 1-50: input/output contracts.
- lines 51-110: event dedupe/watermark.
- lines 111-175: context update.
- lines 176-240: memory/procedure/outcome update.
- lines 241-300: capability-gap detection.
- lines 301-end: durable derived work creation.

New file: `LearningWatermark.kt`
- lines 1-100: source-wise durable watermarks and revision CAS.

Critical provenance enum:
- OBSERVATION.
- INFERENCE.
- USER_CONFIRMED.
- GENERATED_STRATEGY.
- VERIFIED_OUTCOME.

DoD: learning continues after boot and resumes after process death.

---

# E. WorkerRegistry, capability matching and execution topology

## E01 — WorkerRegistry — NEW

New file: `core/runtime/.../workers/WorkerModels.kt`
- lines 1-120: worker descriptor/version/state/capability/load/health.

New file: `WorkerRegistry.kt`
- lines 1-45: registry contract.
- lines 46-110: deterministic registration/version conflict rules.
- lines 111-170: health-aware candidate queries.
- lines 171-end: snapshot/rehydration.

DoD: workers are explicit runtime entities, not implicit factory side effects.

## E02 — CapabilityMatcher — NEW

New file: `core/runtime/.../capability/CapabilityMatcher.kt`
- lines 1-55: request/scoring models.
- lines 56-125: contract/permission/health filtering.
- lines 126-190: trust/cost/latency/history scoring.
- lines 191-end: unresolved equal candidate handling.

DoD: deterministic provider selection with explainable score trace.

## E03 — Worker lifecycle supervisor — NEW

New file: `WorkerLifecycleSupervisor.kt`
- lines 1-70: start/stop/drain semantics.
- lines 71-140: heartbeat/lease health link.
- lines 141-210: replacement/canary worker handling.
- lines 211-end: escalation signals.

DoD: hot-swap/evolution never manipulates workers outside this boundary.

---

# F. ThoughtMatrix v2 and memory

## F01 — ThoughtMatrix v2 — HARDEN/REPLACE COMPATIBLY

Existing anchor: `core/runtime/.../ThoughtMatrix.kt`.

New files:
- `core/runtime/.../thought/ThoughtNode.kt` lines 1-120.
- `ThoughtRelation.kt` lines 1-120.
- `ThoughtMatrixV2.kt` lines 1-260.
- `ThoughtMatrixSnapshot.kt` lines 1-180.

Required fields:
- Photon/revision provenance.
- field domain and semantic key.
- semantic mass/energy/confidence.
- temporal validity.
- relation type/weight/source.
- lifecycle and verification status.

DoD: matrix is a projection rebuildable from durable truth, not an opaque primary store.

## F02 — Episodic/Semantic/Procedural memory projections — NEW

New package: `core/runtime/.../memory/`

Files/line ranges:
- `MemoryModels.kt` 1-200.
- `MemoryProjector.kt` 1-260.
- `MemoryRetriever.kt` 1-240.
- `MemoryConsolidator.kt` 1-260.

Rules:
- no repeated observation silently becomes user preference.
- every memory item links to source evidence.
- conflicts remain visible.

---

# G. Chat context and project continuity

## G01 — Conversation/Project/Goal context core — NEW

New files:
- `ContextModels.kt` lines 1-220.
- `ConversationContextStore.kt` lines 1-200.
- `ProjectContextStore.kt` lines 1-200.
- `GoalContextStore.kt` lines 1-200.
- `ReferenceContextResolver.kt` lines 1-280.

Update anchor:
- `core/language/.../ReferenceResolver.kt` becomes semantic parser input, while durable context resolver supplies candidate context.

DoD:
- `weiter`, `das Bild`, `die APK`, `das andere Modul`, `wie gestern` resolve via durable context with confidence/alternatives.

## G02 — Context field integration — NEW

New `ContextDomainField.kt` lines 1-220.

Purpose:
- emit attraction/repulsion/bias into universal field convergence from active conversation/project/goal context.
- never mutate source Evidence.

---

# H. DeepSearch, Genesis and Convergence

## H01 — DeepSearch planner — NEW

New package: `core/runtime/.../deepsearch/`

Files:
- `DeepSearchModels.kt` lines 1-180.
- `DeepSearchPlanner.kt` lines 1-260.
- `DeepSearchFrontier.kt` lines 1-220.
- `DeepSearchEvaluator.kt` lines 1-260.

Properties:
- bounded depth/breadth/time/work budget.
- deterministic offline search where inputs are local.
- external/network search only via explicit capability and permission path.
- every branch produces traceable hypotheses/evidence.

DoD: unresolved search is explicit; no infinite autonomous loop.

## H02 — Genesis proposal engine — NEW/HARDEN tool foundation

New file: `core/runtime/.../genesis/GenesisCoordinator.kt`
- lines 1-70: trigger policy.
- lines 71-145: capability-gap analysis.
- lines 146-220: smallest-safe-solution selection.
- lines 221-300: proposal creation.
- lines 301-end: handoff to tool/procedure/module BuildStudio paths.

Solution priority:
1. procedure;
2. existing capability composition;
3. connector/interaction wrapper;
4. query/analysis pipeline;
5. code tool;
6. module implementation.

DoD: Genesis proposes; it does not directly activate executable code.

## H03 — Cross-domain convergence coordinator — NEW

New file: `core/runtime/.../convergence/ConvergenceCoordinator.kt`
- lines 1-70: domain isolation and request graph.
- lines 71-150: domain field runs.
- lines 151-230: cross-domain evidence bridge.
- lines 231-300: conflict preservation.
- lines 301-end: final unresolved/converged outcome.

DoD: typed context boundaries prevent legal/finance/science/global-memory leakage.

---

# I. Weltformel / universal field orchestration v2

## I01 — Field-of-fields model — NEW

New package `core/field/.../world/`:
- `WorldFieldModels.kt` lines 1-220.
- `WorldFieldGraph.kt` lines 1-260.
- `WorldFieldEquation.kt` lines 1-320.
- `WorldFieldState.kt` lines 1-180.

Role:
- combine domain field outputs as typed influences over Photon/thought/capability/health states.
- preserve dimensional/type boundaries.
- use explicit coefficients/version fingerprints.

DoD: no mystical scalar “truth score”; every term is typed and inspectable.

## I02 — Weltformel runtime coordinator — NEW

New file: `core/runtime/.../world/WorldFormulaCoordinator.kt`
- lines 1-80: input snapshots.
- lines 81-170: equation/version registry.
- lines 171-260: bounded iterations.
- lines 261-340: anomaly/conflict outputs.
- lines 341-end: snapshot persistence and cognition triggers.

DoD: deterministic replay from same snapshots/configuration.

---

# J. Toolwerkstatt + BuildStudio

## J01 — BuildStudio v1 — NEW

New package `core/runtime/.../buildstudio/`:
- `BuildSpec.kt` lines 1-180.
- `SourcePatchPlan.kt` lines 1-220.
- `BuildStudioCoordinator.kt` lines 1-320.
- `BuildVerification.kt` lines 1-260.

Required pipeline:
`CapabilityGap -> DesignSpec -> isolated branch -> source/tests -> test/lintDebug/assembleDebug -> evidence -> candidate`.

No direct main mutation by generated candidate.

## J02 — Build provenance — NEW

New files:
- `BuildProvenance.kt` 1-220.
- `CandidateArtifact.kt` 1-180.

Must record:
- source commit.
- generated/modified files.
- test commands and results.
- APK artifact hash.
- permissions/capability delta.
- reviewer/promotion actor.

## J03 — ToolWorkshop evidence coupling — HARDEN

Update anchors:
- `GeneratedToolTrialLifecycle`.
- `GeneratedToolRegistry`.
- `ToolWorkshopCoordinator`.

Add:
- field snapshot IDs used in design decision.
- trial outcome evidence.
- health incidents.
- rollback count.

DoD: promotion depends on verified outcomes, not proposal confidence alone.

---

# K. Verified Hot-Swap

## K01 — HotSwap contract — NEW/GATED

New package `core/runtime/.../hotswap/`:
- `HotSwapModels.kt` lines 1-220.
- `HotSwapCandidateRegistry.kt` lines 1-220.
- `HotSwapCoordinator.kt` lines 1-340.

Allowed targets:
- implementations behind stable interfaces.
- workers/fields/tools whose state is externalized and versioned.

Forbidden targets:
- crypto root.
- task ownership/CAS semantics.
- protection state policy.
- signing/update trust root.

## K02 — Shadow/canary promotion — NEW

New file: `HotSwapShadowValidator.kt` lines 1-300.

Stages:
1. static/build verification;
2. replay shadow;
3. bounded canary;
4. health comparison;
5. explicit promotion;
6. instant rollback path retained.

DoD: no candidate can delete its predecessor before rollback window closes.

---

# L. Mutation and bounded evolution

## L01 — Mutation proposal model — NEW/GATED

New package `core/runtime/.../evolution/`:
- `MutationModels.kt` lines 1-220.
- `MutationGenerator.kt` lines 1-280.
- `MutationEvaluator.kt` lines 1-320.

Mutation targets initially limited to:
- coefficients/configuration.
- field weights.
- routing heuristics.
- procedure plans.
- generated non-root tools.

No direct arbitrary source mutation of trust roots.

## L02 — Evolution population/trials — NEW/GATED

New files:
- `EvolutionPopulation.kt` lines 1-260.
- `EvolutionTrialCoordinator.kt` lines 1-340.
- `EvolutionSelectionPolicy.kt` lines 1-280.

Selection requires multiple objective dimensions:
- correctness/replay agreement.
- task success.
- latency/resource cost.
- health incidents.
- rollback rate.
- uncertainty calibration.

DoD: “better” is never a single untyped score.

## L03 — Evolution rollback ledger — NEW

New `EvolutionLedger.kt` lines 1-260.

Every mutation stores parent, candidate, evidence, activation window, failures and rollback.

---

# M. Personal operations layer

These blocks are user-facing and should consume the architecture above rather than create parallel truth stores.

## M01 — Live Data Hub — NEW
- connector/account capability snapshots.
- message/calendar/file delta ingestion.
- provenance-preserving Photons.
- explicit permission state.

## M02 — Personal Planner + Automation Orchestrator — NEW
- goals/constraints/time blocks.
- durable schedules/tasks.
- conflict detection.
- missed-event recovery.

## M03 — Finance Core — NEW
- transaction/budget projections only from explicit data sources.
- strict domain context boundary.
- no silent inferred transaction truth.

## M04 — External App Interaction Gateway — NEW/GATED
- Android intent/accessibility/connector adapters.
- permission broker.
- dry-run/preview where possible.
- health/escalation integration.

---

# N. Artifact/document collaboration layer

## N01 — Collaborative Artifact Photon pipeline — NEW

New package `core/runtime/.../artifact/`:
- `ArtifactModels.kt` lines 1-220.
- `ArtifactContribution.kt` lines 1-200.
- `ArtifactCoordinator.kt` lines 1-320.
- `ArtifactValidator.kt` lines 1-260.

Rule:
- document/image/code/report creation is cross-module collaboration.
- each contribution records module/field/source/provenance/confidence.
- final artifact becomes a new Photon and re-enters cognition/memory/field processing.

---

# R. Complete escalation ladder

Escalation is centralized. Individual modules may emit incidents but may not invent private escalation semantics.

New package: `core/runtime/.../escalation/`

## R00 — Models

`EscalationModels.kt`
- lines 1-40: `EscalationLevel`.
- lines 41-110: trigger/evidence models.
- lines 111-180: action/result models.
- lines 181-end: durable escalation record.

Levels:
- `L0_RETRY` — bounded retry/backoff inside normal task policy.
- `L1_REEVALUATE` — re-read state, rebuild context, rerun convergence.
- `L2_RECOVER_COMPONENT` — component-specific repair probe/restart/reopen circuit.
- `L3_QUARANTINE` — isolate failing field/worker/tool/provider.
- `L4_FALLBACK` — switch to known-good implementation/provider/reference backend.
- `L5_ROLLBACK` — revert promoted tool/hot-swap/mutation/config generation.
- `L6_SAFE_MODE` — protected runtime, only recovery/read-safe functions.
- `L7_REPAIR_PROPOSAL` — produce a BuildStudio/Genesis repair candidate; **never autonomous production activation**.

## R01 — EscalationPolicy

`EscalationPolicy.kt`
- lines 1-55: policy thresholds/budgets.
- lines 56-125: failure-history evaluation.
- lines 126-200: state/health/protection constraints.
- lines 201-270: de-escalation conditions.
- lines 271-end: deterministic decision trace.

Key rules:
- retry budget exhaustion moves to L1/L2 based on classification.
- repeated component-local failure moves to L3.
- a healthy known-good implementation enables L4.
- failure after recent promotion/mutation prioritizes L5.
- systemic/root/store integrity failure may jump directly to L6.
- L7 creates a proposal only after L2-L6 evidence demonstrates a missing/defective capability.

## R02 — EscalationCoordinator

`EscalationCoordinator.kt`
- lines 1-65: dependencies and durable record transaction.
- lines 66-125: L0 retry signal.
- lines 126-185: L1 reevaluation task creation.
- lines 186-245: L2 repair coordination.
- lines 246-305: L3 quarantine.
- lines 306-365: L4 fallback selection.
- lines 366-430: L5 rollback.
- lines 431-500: L6 safe-mode protection state.
- lines 501-end: L7 repair proposal handoff.

## R03 — Escalation integration points

Update anchors:
- `RetryPolicy` — report exhaustion; do not own higher levels.
- `RecoveryCoordinator` — becomes L2 executor.
- `QuarantineRegistry` — L3 executor.
- `CapabilityMatcher`/runtime backend selection — L4 executor.
- `HotSwapCoordinator`/EvolutionLedger — L5 executor.
- `ProtectionCoordinator` — L6 executor.
- `GenesisCoordinator`/BuildStudio — L7 proposal executor.
- `CognitiveOutcomePipeline` — records escalation evidence.

## R04 — Escalation tests

Required deterministic scenarios:
1. transient storage timeout resolves at L0.
2. inconsistent context triggers L1 without quarantine.
3. worker heartbeat failure triggers L2 and recovers.
4. repeated field crash reaches L3; unrelated domains continue.
5. GPU backend failure switches to CPU reference at L4.
6. newly promoted tool regression rolls back at L5.
7. corrupt protection-critical store enters L6.
8. repeated missing-capability failure creates L7 repair proposal but cannot activate candidate.
9. late success from quarantined generation cannot clear newer quarantine.
10. process restart rehydrates active escalation/protection state.

DoD: full ladder is durable, generation-safe, typed and centrally auditable.

---

# S. Verification, benchmarks and rollout

## S01 — Deterministic replay corpus — NEW

Create `core/runtime/src/test/resources/replay/` with versioned fixtures for:
- chat context.
- field conflicts/ties.
- boot recovery.
- capability gaps.
- escalation.
- tool trial/rollback.

## S02 — Property/invariant tests — NEW

Add reusable tests proving:
- input ordering does not alter deterministic result.
- source Photons/Evidence remain immutable.
- stale lease owner cannot write.
- stale hot-swap/mutation generation cannot promote/clear newer state.
- same snapshot/config replays same field/world result.

## S03 — Device validation — NEW

Debug-device acceptance:
- process kill during active task.
- process kill during snapshot write.
- Keystore unreadable/corrupt-store behavior.
- Vulkan failure fallback.
- safe-mode restart/resume.
- hot-swap canary rollback.

## S04 — Private distribution — GATED

Only after all productive blocks needed for a milestone are green:
- build Debug/private APK artifact.
- hash artifact and bind to commit/build provenance.
- no autonomous release signing/change of update trust.

---

# Execution order and merge gates

The implementation sequence is strict:

1. B01 Field trace/snapshot/registry.
2. B02 durable FieldSnapshot store.
3. B03 runtime field adapter.
4. C01 productive Health observability.
5. C02 protection/safe-mode persistence.
6. C03 repair probes.
7. D01-D04 Boot Cognition completion.
8. D05 ContinuousLearningCoordinator.
9. E01-E03 WorkerRegistry/CapabilityMatcher/lifecycle.
10. F01-F02 ThoughtMatrix v2 + memory.
11. G01-G02 Chat/project/goal context + context field.
12. B04 universal-field shadow validation and selective cutover.
13. H01 DeepSearch.
14. H02 Genesis coordinator.
15. H03 cross-domain convergence.
16. I01-I02 Weltformel v2.
17. J01-J03 BuildStudio + ToolWorkshop evidence coupling.
18. R00-R04 complete escalation ladder integration.
19. K01-K02 verified Hot-Swap.
20. L01-L03 bounded Mutation/Evolution.
21. M01-M04 personal operations layer.
22. N01 collaborative artifact Photon pipeline.
23. S01-S04 replay/property/device/private rollout hardening.

Every block is developed on an isolated branch. A next productive block may be started only when its direct dependency is merged and green. Independent documentation/test-fixture preparation may proceed in parallel. No open draft/old branch is merged wholesale without rebasing/reviewing against this plan.

# Global Definition of Done

LIFEOS reaches the planned architecture when all of the following are true:
- Photons/Evidence are persistent typed information units with provenance and field state.
- modules/workers/tools act as typed fields/capabilities with health/version identity.
- boot reconstructs context, capabilities, field state and unfinished work durably.
- continuous learning operates for the whole runtime lifetime.
- ThoughtMatrix/memory are rebuildable projections, not opaque truth stores.
- unresolved conflicts remain explicit through convergence.
- capability gaps lead to smallest-safe Genesis proposals.
- BuildStudio can produce verified candidates but not silently activate them.
- hot-swap is shadow/canary/rollback guarded.
- mutation/evolution is bounded, multi-objective and excludes trust roots.
- escalation L0-L7 is durable and centrally auditable.
- every generated artifact becomes a provenance-bearing Photon and re-enters cognition.
- Debug CI, deterministic replay and device recovery tests are green for the shipped private APK.
