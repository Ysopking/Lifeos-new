# B302–B312 Exact Execution Plan

Baseline audited against `main@5188f243c8b684495f9751c0f4f745620ae8836a`.

This is the execution contract after B301 and B302.1. B311/release is intentionally omitted per owner direction. All line numbers below are **baseline line numbers on this exact main SHA**. After every merged block, re-resolve the same symbol anchors before editing; never apply stale absolute line numbers blindly.

## Global merge rule

Every productive block is rebuilt from the then-current `main`, runs Core Fast + Android Debug + Android Emulator Recovery + LIFEOS Product Gold on one exact head, and merges with an expected-head SHA guard. BuildStudio Candidate Host Gate may remain skipped when its own workflow conditions do not apply.

No block may silently overwrite a later main fix. Tree moves must be content-preserving unless the block explicitly calls out a semantic change.

---

# B302 — Finish runtime decomposition

## B302.2 — introduce runtime-contracts seam

### Purpose
Create a cycle-free home for small cross-runtime contracts before moving DeepSearch/BuildStudio packages out of the monolithic `:core:runtime`.

### Exact baseline edits
- `settings.gradle.kts:9-23`
  - insert `":core:runtime-contracts",` immediately after `":core:runtime",`.
- NEW `core/runtime-contracts/build.gradle.kts:1-10`
  - Kotlin JVM plugin, JVM 17.
  - dependencies: `:core:model`, `:core:field`, coroutines-core, kotlin(test).
- MOVE contract-only types, preserving package names initially:
  - `core/runtime/.../capability/CapabilityModels.kt:1-96` -> `core/runtime-contracts/src/main/kotlin/app/lifeos/core/runtime/capability/CapabilityModels.kt`.
  - `core/runtime/.../topology/SubsystemManifest.kt:1-135` -> runtime-contracts only after removing its direct dependence on `LifeOsSubsystemDescriptor` from `descriptor()`; move descriptor DTO with it or replace that method with a contract-local DTO.
  - `core/runtime/.../topology/LifeOsRuntimeBindings.kt:1-22` models move; registry implementation stays in `:core:runtime`.
  - `core/runtime/.../trace/DecisionTraceModels.kt:1-142` may move only if all consumers compile without runtime implementation dependency; otherwise defer trace models to B302.3.
- `core/runtime/build.gradle.kts:3-11`
  - add `api(project(":core:runtime-contracts"))`.
- `app/build.gradle.kts:28-54`
  - no direct dependency required unless compiler exposes a direct import; do not add speculative edges.
- `core/data/build.gradle.kts:9-16`
  - add direct runtime-contracts dependency only for types imported by data repositories; keep `:core:runtime` until B302.4 proves it can be removed.
- NEW `.github/scripts/ci-runtime-contracts-boundary.sh:1-70`
  - fail if runtime-contracts imports `app.lifeos.core.runtime` packages outside the explicitly moved contract namespaces.
  - fail if runtime-contracts depends on `:core:runtime`.
  - require `:core:runtime-contracts:test` in Core Fast.
- `.github/scripts/ci-core-fast.sh:39-47`
  - insert boundary contract + `:core:runtime-contracts:test` immediately after `:core:runtime:test` and before runtime-personal checks.

### Tests
- NEW `core/runtime-contracts/src/test/.../RuntimeContractsDependencyTest.kt`.
- Existing capability/topology model tests must move with their source contract files.
- No behavioral changes to generated-tool activation, topology state, or trace identity.

### Definition of done
`:core:runtime-contracts` compiles without a dependency on `:core:runtime`; Core Fast proves the dependency direction.

---

## B302.3 — split runtime-deepsearch

### Baseline source set
Move these production files from `core/runtime/src/main/kotlin/app/lifeos/core/runtime/deepsearch/` into NEW `:core:runtime-deepsearch` preserving package names:
- `DeepSearchCheckpointResultProjector.kt`
- `DeepSearchCheckpointStore.kt`
- `DeepSearchClaimCompatibility.kt`
- `DeepSearchEvaluator.kt`
- `DeepSearchExternalRuntimeRegistry.kt`
- `DeepSearchFrontier.kt`
- `DeepSearchMissionCodec.kt`
- `DeepSearchMissionCoordinator.kt:1-261`
- `DeepSearchMissionLedger.kt`
- `DeepSearchMissionRecoveryAudit.kt:1-149`
- `DeepSearchMissionVerifier.kt:1-72`
- `DeepSearchModels.kt`
- `DeepSearchPlanner.kt`
- `DeepSearchPlannerCheckpoint.kt`
- `DeepSearchPlannerV2.kt:1-733`
- `DeepSearchSourceSnapshot.kt`

Move the existing DeepSearch tests with the source package.

### Required decoupling
Current baseline cross-runtime imports that must be made legal through runtime-contracts or narrow interfaces:
- `DeepSearchExternalRuntimeRegistry.kt`: `GeneratedToolRuntimeProcessRegistry`.
- `DeepSearchMissionCoordinator.kt`: `DecisionTraceRuntimeRegistry`.
- `DeepSearchModels.kt`: `CapabilityId`.
- `DeepSearchPlanner.kt`: `CapabilityRegistry`, `ProviderState`.

Do **not** allow `:core:runtime-deepsearch -> :core:runtime`; that would create a false split.

### Gradle edits
- `settings.gradle.kts:9-23`: add `":core:runtime-deepsearch"`.
- NEW `core/runtime-deepsearch/build.gradle.kts:1-14`
  - depend on runtime-contracts, model, field, coroutines.
- `core/runtime/build.gradle.kts:3-11`: add implementation/api edge to runtime-deepsearch only after all reverse imports are removed.
- `core/data/build.gradle.kts:9-16`: use runtime-deepsearch for DeepSearch repositories; retain runtime only for unrelated repositories.
- `app/build.gradle.kts:28-54`: add runtime-deepsearch because `ProcessRuntimeInstaller.kt` constructs DeepSearch types directly.

### Wiring anchors
- `app/src/main/java/app/lifeos/next/ProcessRuntimeInstaller.kt:345-386`
  - keep the existing `installDeepSearchRuntime` composition.
  - imports must point to the same preserved package names, so application behavior remains unchanged.
- `app/src/main/java/app/lifeos/next/kernel/WebDeepSearchRuntime.kt:17-42`
  - preserve owner-policy + source installation semantics.

### Boundary gate
Extend `.github/scripts/ci-runtime-module-boundary-contract.sh:1-42`:
- no production file under `core/runtime/src/**/deepsearch/**`;
- runtime-deepsearch cannot depend on runtime monolith;
- exact production/test counts are asserted from the post-move tree;
- Core Fast runs `:core:runtime-deepsearch:test`.

---

## B302.4 — split BuildStudio runtime contracts from runtime monolith

### Source move
Move `core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/` into NEW `:core:runtime-buildstudio` after contract decoupling:
- `BuildProvenance.kt`
- `BuildSpec.kt`
- `BuildStudioCoordinator.kt:1-235`
- `BuildStudioExpansionRequest.kt`
- `BuildStudioHostRuntime.kt:1-206`
- `BuildVerification.kt`
- `CandidateArtifact.kt`
- `CandidateRuntimeVerification.kt`
- `CandidateSealCryptography.kt`
- `RuntimeCandidateRecovery.kt`
- `SourcePatchPlan.kt`

Move all existing BuildStudio unit tests in the same package.

### Current cross-runtime dependencies to resolve
- `BuildProvenance.kt`: capability IDs/requirements/tool permissions.
- `BuildSpec.kt`, `BuildStudioExpansionRequest.kt`: capability gap + Genesis handoff contracts.
- `BuildStudioHostRuntime.kt:3-11`: capability registry/models + topology binding registry.
- `CandidateRuntimeVerification.kt`: capability ID/tool permission.

Put only stable DTO/interfaces into runtime-contracts. Keep generated-tool mutation implementation in runtime monolith.

### Gradle/wiring
- `settings.gradle.kts:9-23`: add `":core:runtime-buildstudio"`.
- NEW `core/runtime-buildstudio/build.gradle.kts`.
- `host/buildstudio/build.gradle.kts:1-11`
  - replace broad `implementation(project(":core:runtime"))` with `implementation(project(":core:runtime-buildstudio"))` plus only required contracts.
- `.github/scripts/ci-core-fast.sh:57-58`
  - add runtime-buildstudio unit tests before host tests.
- Extend runtime boundary script to forbid BuildStudio source remaining in runtime monolith.

### DoD
Host module no longer receives the entire runtime monolith transitively just to implement BuildStudio.

---

## B302.5 — monolith budget closure

Baseline `:core:runtime` production distribution contains large domains (capability 55, root 47, world 45, cognition 34, life 27, evolution 24, level7 24, informationasset 21, etc.). B302 does not attempt to split all domains blindly.

Create NEW `.github/scripts/ci-runtime-monolith-budget.sh` with initial exact post-B302.4 baselines:
- forbid `personal/`, `deepsearch/`, `buildstudio/` production source under the monolith;
- record exact Kotlin-file count and fail on growth unless an allowlisted migration updates the budget;
- forbid new top-level runtime package directories without explicit budget update.

Wire this contract into `.github/scripts/ci-core-fast.sh` directly after the runtime module boundary contract.

B302 closes when runtime-personal, runtime-deepsearch and runtime-buildstudio are physically separate and growth of the monolith is blocked.

---

# B303 — Unified Vault

## B303.1 — central cryptographic primitive

Current duplicated helpers:
- `core/data/.../security/EncryptedLedgerVaultSupport.kt:18-135`
  - key management: 23-40
  - unversioned AES-GCM encode/decode: 42-102
  - atomic file IO: 104-131
  - size bound: 133-134
- `core/data/.../security/VersionedPathBoundVaultSupport.kt:18-150`
  - delegates key loading: 22-23
  - versioned AES-GCM encode/decode: 25-115
  - AtomicFile IO: 117-146
  - size bound: 148-149

Create:
- NEW `UnifiedVaultKeyProvider.kt`: AndroidKeyStore alias validation + AES-256 key creation.
- NEW `UnifiedVaultEnvelope.kt`: one parser/serializer for bounded AES-GCM envelopes.
- NEW `UnifiedVaultIo.kt`: bounded AtomicFile read/write for File and AtomicFile targets.
- NEW `UnifiedVault.kt`: public-in-module facade supporting:
  - legacy v1 ledger envelope;
  - versioned container+codec envelope;
  - mandatory AAD mode for path-bound stores.

Refactor both existing support objects into thin compatibility adapters so wire format remains byte-compatible.

### Tests
- preserve `VersionedPathBoundVaultSupportTest.kt:14-97`;
- add legacy envelope round-trip, wrong AAD, wrong codec/container version, truncated container, oversized payload, invalid IV, and atomic write-failure tests;
- prove no repository migration is required for existing ciphertext.

## B303.2 — path binding closure
Audit every repository still calling `EncryptedLedgerVaultSupport.encrypt/decrypt` with default empty AAD. Classify each store:
1. singleton file — bind AAD to stable logical store id;
2. per-id/revision file — bind AAD to exact relative path + id/revision;
3. migration reader — preserve legacy read path, rewrite only after successful verified decode.

Priority repositories from current main:
- `EncryptedOwnerPolicyRepository.kt:23-229`
- `EncryptedDeepSearchMissionRepository.kt:19-219`
- `EncryptedDeepSearchCheckpointRepository.kt:24-107`
- `EncryptedResourceBudgetRepository.kt:19-123`

Add relocation/collision tests for every migrated physical-id store.

---

# B304 — Permission Profiles

## B304.1 — typed permission model
Current permission logic is concentrated in `PrivatePermissionController.kt:10-118`:
- runtime permission discovery 15-43;
- broad-file access 45-61;
- initial-data schema 63-84;
- all-runtime schema 86-105;
- SharedPreferences storage 107-117.

Create NEW:
- `PermissionProfile.kt`
  - stable profile id;
  - runtime Android permissions;
  - special-access requirements;
  - owner-effect requirements;
  - rationale/source tags.
- `PermissionProfileState.kt`
  - GRANTED / MISSING / OWNER_ACTION_REQUIRED / UNSUPPORTED.
- `PermissionProfileEvaluator.kt`
  - pure evaluation from SDK level + granted permissions + broad-access state.
- `PermissionProfileStore.kt`
  - persistence only for prompt/schema acknowledgement, not authority.

Refactor `PrivatePermissionController.kt:15-105` into profile projection/delegation. Keep public `LifeOsApplication.kt:276-288` methods source-compatible for UI migration.

## B304.2 — UI and owner-policy alignment
- `ChatMainActivity.kt:140-176`: request profiles, not ad-hoc permission lists.
- `AndroidManifest.xml:3-18`: every declared permission must map to exactly one profile or explicit infrastructure exemption.
- `AndroidSharedFilesInitialDataSource.kt:105-121`: consume storage profile state.
- `PrivateOwnerPolicyBaseline.kt:35-140`: owner-effect grants stay separate from Android permission grants; profiles reference effects but must not silently grant them.
- Extend `.github/scripts/ci-private-debug-security.sh:4-125` with manifest-to-profile coverage.

---

# B305 — BuildStudio wrapper-only

## B305.1 — seal runtime entry point
Current trusted seam is `BuildStudioHostRuntime.kt:33-45` and process registry `52-205`.
- Keep `BuildStudioHostAdapter` as the only executable host interface.
- `BuildStudioHostProcessRegistry.run():108-126` and `expand():128-147` remain the only productive dispatch paths.
- Add contract test that production app/runtime code cannot instantiate `BuildStudioCoordinator` with local workspace/gate/collector implementations.

## B305.2 — host-only implementation
`host/buildstudio/JvmBuildStudioHost.kt:31-472` owns:
- process executor 31-94;
- workspace/index 96-235;
- Gradle gate runner 237-255;
- APK collector 257-271;
- expansion spec binding 273-313;
- candidate publication 315-386;
- host implementation 388-472.

Add `.github/scripts/ci-buildstudio-wrapper-contract.sh`:
- fail if `GitIsolatedBuildWorkspace`, `GradleBuildGateRunner`, `DebugApkArtifactCollector`, process execution, `git` or `gh` host implementations appear outside `host/buildstudio`;
- require runtime productive calls to pass through `BuildStudioHostProcessRegistry`;
- require candidate `activationAllowed == false` invariant from `BuildStudioCoordinator.kt:68,87`.

Wire into Core Fast before host tests.

---

# B306 — Supply-chain hardening

Existing baseline:
- immutable action refs already enforced by `ci-action-pin-contract.sh:14-47`;
- Gradle wrapper jar/distribution integrity enforced by `ci-gradle-wrapper-contract.sh:4-54`;
- wrapper distribution fixed at `gradle-wrapper.properties:3-6`;
- required main checks encoded by `ci-main-authority-contract.sh:22-81`.

Add NEW `.github/scripts/ci-dependency-supply-chain-contract.sh`:
- fail dynamic Gradle versions (`+`, ranges, `latest.*`);
- fail project repositories because settings already declares FAIL_ON_PROJECT_REPOS;
- maintain explicit allowlist of remote Maven repository roots;
- generate/verify Gradle dependency verification metadata;
- fail unverified plugin/dependency artifacts.

Add NEW `.github/scripts/ci-native-toolchain-contract.sh`:
- pin/verify NDK/CMake version in `core/image-native/build.gradle.kts`;
- forbid environment-derived compiler selection in CI.

Wire both into Core Fast before compilation.

External B295 admin debt remains separate: repository-level GitHub ruleset must still be enabled outside code if API permissions do not allow it.

---

# B307 — DeepSearch resilience

Do this after B302.3 so resilience code lands in runtime-deepsearch.

Primary anchors:
- `DeepSearchPlannerV2.kt:19-733`
  - search orchestration 24-457;
  - unfinished branch recovery 458-522;
  - resolved/result materialization 523-655;
  - deterministic ordering/error helpers 656-733.
- `DeepSearchMissionCoordinator.kt:30-260`
  - mission lifecycle `run` at 37-205;
  - product/status materialization 206-249;
  - registry 251-260.
- `DeepSearchMissionRecoveryAudit.kt:24-149`.
- `DeepSearchMissionVerifier.kt:7-72`.
- encrypted checkpoint repository baseline `EncryptedDeepSearchCheckpointRepository.kt:24-107`.

Required changes:
- add explicit retry classification: retryable source failure vs permanent policy/input failure;
- persist retry attempt + backoff decision before external retry;
- enforce idempotent checkpoint advancement on process death;
- reject checkpoint/mission divergence rather than auto-heal silently;
- add cancellation checkpoint before propagating cancellation;
- bound total attempts per source/branch and mission;
- add recovery tests for: source timeout, partial evidence then crash, checkpoint corruption, mission event gap, duplicate terminal result, cancellation during reservation, and replay after process death.

No network access may bypass `WebDeepSearchOwnerPolicy` or dynamic owner effect gates.

---

# B308 — Orphan/dormant module closure

Use topology as source of truth:
- `LifeOsRuntimeTopology.kt:60-179` canonical manifest.
- `LifeOsRuntimeTopology.kt:190-250` live snapshot projection.
- `SubsystemManifest.kt:63-133` graph validation.
- `LifeOsRuntimeWiring.kt:46-94` manifest binding/wiring.

Create NEW `.github/scripts/ci-runtime-orphan-contract.sh`:
- every productive runtime registry/binding must map to a canonical manifest subsystem;
- every canonical subsystem must have either a productive startup owner/binding path or an explicit `external/optional` classification;
- fail string subsystem IDs not present in canonical manifest;
- fail dormant production packages with tests but no wiring only after a reviewed allowlist.

Add NEW `RuntimeTopologyCoverageTest.kt` to assert:
- no duplicate subsystem ownership;
- all startup owners produce deterministic layers;
- BuildStudio remains EXTERNAL_HOST;
- DeepSearch remains DEEP_SEARCH;
- optional runtime stays explicitly optional, never implicitly ACTIVE.

B308 is complete only when every identified orphan is either wired or deleted; do not keep a permanent catch-all allowlist.

---

# B309 — Performance Gold

Existing instrumentation:
- `BootPerformance.kt:3-94` phase timing model/recorder.
- `RuntimeTelemetry.kt:6-74` CPU/heap/native heap/traffic snapshot.
- `AndroidRuntimeTelemetryReader.kt:14-68` platform reader.
- Product Gold evidence pipeline: `ci-product-gold.sh:12-91` + `seal-gold-evidence.py`.

Add:
- NEW `PerformanceBudget.kt` with versioned explicit budgets for cold boot total/phase, heap headroom, native heap, DeepSearch bounded mission, image path.
- NEW `PerformanceGoldSnapshot.kt` with stable evidence schema/fingerprint.
- app instrumentation test that captures cold-start and steady-state measurements without changing product behavior.
- `ci-product-gold.sh`: collect performance artifact after emulator preflight.
- `seal-gold-evidence.py`: include performance JSON and reject missing/invalid budget evidence.

Budgets must be established from measured baseline runs; do not invent thresholds. First PR records baseline only, second PR turns accepted budgets into blocking limits.

---

# B310 — Native safety

Baseline:
- `core/image-native/build.gradle.kts:1-23`.
- `CMakeLists.txt:1-27`; current compile options line 20 is `-O3 -fno-exceptions`.
- `mmsi_native.cpp:181-229` JNI direct-buffer validation and inverse-radiometry entry.
- `NativeMmsiBridge.kt:8-94`; Kotlin validates direct buffers/capacity at 24-34 and loads native library at 73-79.
- `MmsiSyncFence.kt:1-34` owns FD transfer/close.

Required hardening:
- pin NDK/CMake in Gradle.
- add warning policy (`-Wall -Wextra -Wpedantic`) and make CI native warnings blocking after existing code is clean.
- checked multiplication for every byte-capacity calculation before JNI/native allocation.
- JNI guards for null, direct capacity, integer overflow, finite scalar inputs and Java exception state.
- RAII ownership for Vulkan/AHardwareBuffer/sync-fd resources; one explicit owner per handle.
- native negative tests for zero/huge pixel counts, undersized buffers, invalid fence FD, failed Vulkan probe, repeated close/take.
- run native compile/tests in Android Debug + Product Gold.

Sanitizers are host/emulator/toolchain-dependent; add only where the pinned Android toolchain supports them reproducibly.

---

# B312 — Architecture budget gates

This is the final non-release block.

Create NEW `.github/architecture-budget.json` containing versioned budgets for:
- file line counts: `LifeOsKernel`, `LifeOsApplication`, `LifeOsViewModel`, `ProcessRuntimeInstaller`, `DeepSearchPlannerV2`, host BuildStudio implementation;
- runtime-monolith Kotlin file count;
- per-module dependency edges;
- forbidden package locations;
- canonical subsystem count/fingerprint policy;
- direct host/process execution locations;
- vault helper duplication count;
- permission-profile manifest coverage.

Create NEW `.github/scripts/ci-architecture-budget.sh`:
- deterministic JSON parser;
- exact file/module counts;
- no hidden tolerance;
- budget changes require explicit edited JSON diff;
- fail if a protected god-object or monolith grows above accepted post-hardening baseline.

Wire as an early Core Fast gate immediately after repository authority/supply-chain checks.

Update `.github/scripts/ci-gold-coverage-contract.sh` so Product Gold requires the architecture budget gate.

Final DoD:
- B302 decomposition boundaries enforced;
- B303 vault duplication/path-binding contract enforced;
- B304 permission profile coverage enforced;
- B305 wrapper-only BuildStudio enforced;
- B306 supply-chain contracts enforced;
- B307 resilience recovery tests green;
- B308 zero unexplained productive orphans;
- B309 measured performance budgets blocking;
- B310 native safety tests/blockers green;
- B312 architecture budgets cannot regress silently.

---

# Fast execution chain

1. B302.2 runtime-contracts
2. B302.3 runtime-deepsearch
3. B302.4 runtime-buildstudio
4. B302.5 monolith budget
5. B303.1–B303.2 Unified Vault
6. B304.1–B304.2 Permission Profiles
7. B305.1–B305.2 BuildStudio wrapper-only
8. B306 Supply-chain
9. B307 DeepSearch resilience
10. B308 orphan/dormant closure
11. B309 Performance Gold
12. B310 Native Safety
13. B312 Architecture Budget Gates

For speed, aggregate only blocks that touch disjoint ownership domains and have already passed focused tests. Never aggregate a module move with an unverified semantic rewrite of the same files.
