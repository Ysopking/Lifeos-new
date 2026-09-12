# LIFEOS Optimization Round 2 — I–P line-anchored execution plan

Baseline for this plan: `lifeos/optimization-round-2@0b548f2b15daa95a71b0111e45b6bb04731c2832`, based on `main` after merged Blocks A–H Product Gold.

Line windows below are anchored to that baseline. If a preceding block shifts lines, the named symbol is the canonical anchor and the line window must be re-resolved before editing. This prevents stale line-number edits while preserving the same line-exact discipline used for A–H.

## Execution protocol — identical discipline to A–H

Every block I–P is executed separately and in order. A block is never marked COMPLETE because code merely exists.

1. Freeze and record the exact branch head.
2. Inspect the productive path, persistence path, recovery path and existing tests for that block.
3. Re-resolve every file/symbol/line window listed below against the exact head.
4. Implement only the block scope; preserve established security, Owner Policy, resource, canary, recovery and provenance authorities.
5. Add deterministic unit tests, migration/backward-compatibility tests and negative/containment tests.
6. Integrate the block into the productive Android/runtime composition; dead helper code does not count.
7. Run targeted module tests and debug build.
8. Run exact-head Android Debug CI.
9. Run exact-head cold-restart/recovery evidence where the block can affect persisted/runtime state.
10. Run exact-head LIFEOS Product Gold.
11. Record evidence and only then mark the block COMPLETE and start the next block.

Global invariants for all I–P:
- source/evidence Photons are immutable;
- the encrypted Photon repository remains authoritative evidence storage;
- causal provenance is domain state, not optional telemetry;
- Owner Policy and platform/security constraints define the admissible action space;
- generated capabilities cannot self-activate and cannot bypass canary/promotion authority;
- no Android sandbox, encryption, credential, account or permission bypass is introduced;
- replay-sensitive identities are deterministic/versioned; wall-clock/random defaults cannot silently enter deterministic paths;
- old persisted formats stay readable until an explicit migration removes them with evidence.

---

## Block I — Durable cognition and replay closure

### Goal
Close the remaining gap between the new durable causal ledger and older in-memory cognition journals so process death cannot erase relevant causal events, transaction observations, outcomes or passive trigger evidence.

### Existing implementation already on the branch
- full v2 causal trace identity includes runtime, policy and module/descriptor set;
- full causal ledger payload is persisted and v1 replay guards remain readable;
- module-version and policy-version changes produce distinct historical reinterpretation traces.

### Baseline line/symbol anchors
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/CausalCognitionEngine.kt` L19–326 — `CausalCognitionEngineConfig`, `process`, `executionFingerprint`, `descriptorParametersHash`.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/CausalLedgerCodec.kt` L1–221 — entire v2 codec.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/PhotonBackedCausalLedgerStore.kt` L13–160 — `append`, `load`, `toLedgerPhoton`, v1/v2 decoding.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/cognition/CognitiveEventJournal.kt` L8–58 — `CognitiveEvent`, `CognitiveEventJournal`, `InMemoryCognitiveEventJournal`.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/cognition/CognitiveModels.kt` L24–96 — `PhotonDelta`, `CognitiveWorkItem`; random UUID/time defaults are replay-sensitive hazards when used productively.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/cognition/CognitiveDeltaIdentity.kt` L5–13 — existing stable photon-revision identity helper.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/cognition/PhotonTransaction.kt` L15–111 — `PhotonTransactionJournal`, in-memory implementation, deterministic task/state transaction IDs.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/cognition/CognitiveOutcomePipeline.kt` L20–220 — `CognitiveOutcomeJournal`, `CognitiveTrigger`, `CognitiveTriggerSink`, observers.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/cognition/DurableCognitiveTriggerSink.kt` L12–112 — safe durable feedback task conversion while underlying trigger journal is still injected.
- `app/src/main/java/app/lifeos/next/kernel/LifeOsKernelFactory.kt` around productive cognition construction (`val cognitiveEventJournal`, `val photonTransactions`, `val cognitiveOutcomes`, `val cognitiveTriggers`) — currently composes in-memory journals/sink.

### Exact implementation sequence
I.1 Introduce durable codecs/records for cognitive event, photon transaction, outcome and trigger records, using stable schema/version headers and bounded decode limits.
I.2 Back the four journal interfaces with the existing encrypted Photon repository or dedicated encrypted repositories without creating a second unprotected persistence universe.
I.3 Preserve interface compatibility so existing callers/tests do not require broad rewrites.
I.4 Replace productive `InMemory*` construction in `LifeOsKernelFactory` with durable implementations; in-memory implementations remain test fixtures only.
I.5 Route replay-sensitive event/delta/work identities through stable identity factories when produced by the productive pipeline; retain constructor defaults only where backward source compatibility requires them.
I.6 Ensure append is idempotent and conflict-detecting: same stable id + same content is replay; same stable id + different content is corruption/conflict.
I.7 Rehydrate offsets/order deterministically after process death.
I.8 Ensure telemetry persistence failure cannot rewrite an already-authoritative durable task outcome; preserve `CompositeDurableTaskExecutionObserver` authority ordering.
I.9 Add explicit migration/read compatibility for any prior persisted version introduced in this branch.

### Required tests
- same input/revision/runtime/policy/module set -> identical causal trace and durable record identities;
- module/policy/runtime version change -> distinct trace;
- event/transaction/outcome/trigger survive store recreation;
- duplicate append is idempotent;
- conflicting duplicate is rejected;
- legacy v1 causal replay guard remains readable;
- process restart does not duplicate durable reevaluation tasks;
- recovery/quarantine trigger evidence survives restart even when it does not become a photon-reprocess task;
- corrupted/truncated payload is contained and reported rather than accepted.

### Definition of Done
Productive composition uses durable cognition journals; cold restart reconstructs them; targeted tests pass; exact-head Debug CI + recovery + Product Gold pass.

---

## Block J — Semantic cognition and typed domain evidence

### Goal
Turn field attraction and domain reasoning from tag-centric routing into evidence-preserving semantic cognition that can represent support, contradiction, uncertainty and typed facts without inventing truth.

### Existing implementation already on the branch
- `semanticHints`, `goalHints`, `expectedInformationGain`, `estimatedCost` exist;
- field attraction uses semantic/goal overlap, information gain and cost;
- curiosity/legal/debt/business modules can attract untagged text and emit structured candidate facts.

### Baseline line/symbol anchors
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/CognitiveModule.kt` L14–42 — `CognitiveModuleDescriptor` semantic/cost metadata and validation.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/FieldAttractionEngine.kt` L35–160 — ranking, `score`, semantic/goal overlap and tokenizer.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/life/DomainCognitionModules.kt` L1–110 — curiosity/legal/debt/business module descriptors and processors.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/life/DomainEvidenceExtractor.kt` L1–154 — `DomainFactKind`, `DomainFact`, deterministic extractors.
- `core/model/src/main/kotlin/app/lifeos/core/model/Photon.kt` L1–65 — provenance, `RelationType`, relations and field influences used by derived evidence.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/CausalCognitionEngine.kt` module-output normalization and fan-in window around L120–220.

### Exact implementation sequence
J.1 Extend structured domain output so each fact carries stable fact identity, source state hash, extractor/module version and evidence span/fingerprint.
J.2 Map semantic result meaning into typed Photon relations (`DERIVED_FROM`, `SUPPORTS`, `CONTRADICTS`, `REFERENCES`, `TRANSFORMS`) instead of relying only on free-form tags.
J.3 Add explicit `SUPPORTED`, `CONTRADICTED`, `UNCERTAIN`, `IRRELEVANT`/no-output semantics at module branch level without mutating the source Photon.
J.4 Preserve contradictory facts simultaneously; convergence may summarize disagreement but may not silently erase either branch.
J.5 Bound semantic fan-out by descriptor max outputs, novelty/dedupe and recursion budget.
J.6 Separate evidence extraction from legal/financial/business conclusion strength: extracted candidate fact != verified real-world fact.
J.7 Feed knowledge gaps/questions into the same causal path so they can attract Future/DeepSearch logic later.

### Required tests
- untagged legal/debt/business text attracts correct module(s);
- ambiguous text does not exceed bounded confidence/output limits;
- same evidence -> same structured fact ids;
- source revision/module version change -> new fact interpretation identity;
- contradictory branches coexist and remain traceable;
- domain module cannot execute external side effects;
- branch ordering does not change canonical convergence hash.

### Definition of Done
Typed evidence/contradiction lineage is productive, deterministic and bounded; exact-head Debug CI + recovery + Product Gold pass.

---

## Block K — Future evidence, future delta and proactive planning

### Goal
Make present evidence produce explicit possible future states and admissible next-step candidates so LIFEOS can reduce future load instead of waiting for a user question or deadline.

### Existing implementation already on the branch
- `FutureEvidenceEngine` projects deadline/debt/risk/opportunity/knowledge scenarios;
- explicit horizon, probability, resource cost, source lineage and actionability exist;
- `FutureCognitionModule` converts scenarios to future-evidence Photons and participates in causal cognition.

### Baseline line/symbol anchors
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/life/FutureEvidenceRuntime.kt` L1–156 — `FutureHorizon`, `FutureScenarioType`, `FutureEvidenceScenario`, `FutureEvidenceEngine`.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/life/FutureCognitionModule.kt` L1–67 — descriptor and scenario -> Photon handoff.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/life/SeinModeOptimization.kt` — `LifeStateVector`, `FutureDeltaCandidate`, `SeinModeEvaluator`, `LifePlanner` ranking seam; re-resolve exact lines at K start.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/life/LifeOsIntegratedCognitionSuite.kt` L5–20 — shared `futureEvidence`, planner and module registration.
- Owner Policy/resource guard composition in `app/src/main/java/app/lifeos/next/kernel/GoalActionExecutionGuard.kt` — re-resolve exact lines at K start before integration.

### Exact implementation sequence
K.1 Add a productive bridge from persisted future-evidence Photons/scenarios into policy-filtered `LifePlanner.rank`.
K.2 Keep two separate outputs: observation-only future hypotheses and action candidates; observations can never execute.
K.3 Add typed FutureDelta/Opportunity/KnowledgeGap photons carrying source evidence ids, probability, horizon, expected pressure/opportunity and planner score.
K.4 Evaluate candidate trajectories only inside Owner Policy + resource admissible space; Sein optimization never overrides hard constraints.
K.5 Connect selected admissible planning output to goal/opportunity generation, not directly to high-impact external action.
K.6 Persist planning decision provenance: candidate set hash, policy version, Sein target version, resource snapshot fingerprint and selected/non-selected reasons.
K.7 Re-evaluate when source revision, domain module version, policy version, resource state or relevant memory changes.
K.8 Keep wording/model contract explicit: future evidence is hypothesis/projection, not asserted future fact.

### Required tests
- deadline -> inaction pressure + preemptive candidate;
- disallowed candidate never reaches executable planning path;
- equal future improvement uses deterministic resource-aware tie break;
- source revision/module/policy change creates a new future interpretation;
- observation-only future photon cannot dispatch action;
- selected candidate retains source lineage and Sein/policy decision fingerprint;
- no-action case is represented cleanly when all candidates are blocked.

### Definition of Done
Future evidence reaches productive planning through policy/resource gates with replayable decision provenance; exact-head Debug CI + recovery + Product Gold pass.

---

## Block L — Life graph, Boot reconstruction and four-stage long-term memory

### Goal
Create a durable historical/present life model from legitimately accessible evidence, continuously ingest new authorized data and compact memory without losing source evidence.

### Existing implementation already on the branch
- deterministic life entity/event/relationship projection from explicit evidence tags;
- permission-aware `ContinuousLifeIngestEngine` with source cursor contract;
- `HOT -> WARM -> COLD -> CRYSTALLIZED` LongTermMemoryEngine;
- WARM episodes, COLD atoms, CRYSTALLIZED semantic cores;
- protected unresolved debt/contracts/deadlines/goals/important relationships/expected events;
- future/goal relevance can rehydrate compact memory;
- legacy HOT/WARM/ARCHIVE projection remains source-compatible.

### Baseline line/symbol anchors
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/life/LifeGraphRuntime.kt` L1–209 — `LifeGraphProjector`, entity/event/relationship types, source cursor/record and ingest engine.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/life/LongTermMemoryRuntime.kt` L1–498 — complete four-stage memory runtime.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/life/LifeMemoryRuntime.kt` L1–130 — legacy tier bridge, boot rehydrator and long-term projection handoff.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/life/CognitiveResilience.kt` around `CognitiveMemoryCompactor` — compatibility facade over four-stage source of truth.
- `core/runtime/src/test/kotlin/app/lifeos/core/runtime/life/LongTermMemoryRuntimeTest.kt` L1–150 — current lifecycle tests.
- Android boot composition: `app/src/main/java/app/lifeos/next/kernel/LifeOsKernelFactory.kt` and `LifeOsKernel.kt` boot/rehydration symbols — re-resolve exact lines at L integration step.

### Exact implementation sequence
L.1 Add durable source checkpoint/cursor persistence keyed by authorized source id and adapter version.
L.2 Make ingest atomic enough that a crash cannot advance a cursor without durable evidence or duplicate source records after restart.
L.3 Add a durable/rebuildable memory-access ledger for last access, count, goal relevance, relationship weight, future relevance and Sein relevance.
L.4 Persist memory atoms/crystals through the normal encrypted Photon repository as derived Photons while preserving source parent ids/state hashes/producer versions.
L.5 Prevent recursive compaction of memory-atom/crystal management photons unless explicitly intended.
L.6 Expand entity resolution from explicit tags to evidence-backed candidate aliases/relationships; confidence and source ids are mandatory.
L.7 Integrate Boot reconstruction: authorized adapters -> immutable evidence Photons -> entity/person resolution -> chronological timeline -> past-self model -> normal cognition.
L.8 Integrate continuous ingest after boot with the same Photon machinery and cursors.
L.9 Represent unavailable/unauthorized sources as explicit source-gap/permission state; never bypass sandbox/encryption/authentication.
L.10 Add deterministic rehydration from CRYSTALLIZED/COLD toward WARM/HOT when current goal/future evidence makes old memory relevant.

### Required tests
- cursor crash before/after persistence does not lose or duplicate source record;
- unauthorized source is rejected before perception;
- cold restart rebuilds identical life graph and stage projection;
- old completed evidence crystallizes with lineage intact;
- open debt/deadline/goal cannot crystallize from age alone;
- future relevance rehydrates old memory deterministically;
- original source Photon remains byte/state equivalent after compaction;
- duplicate source records dedupe across restart, not only within one batch;
- large mixed memory set preserves deterministic fingerprint.

### Definition of Done
Boot reconstruction + continuous authorized ingest + durable memory compaction/re-hydration work across cold restart; exact-head Debug CI + recovery + Product Gold pass.

---

## Block M — LIFEOS-native multimodal perception

### Goal
Make speech, handwriting/graphemes and visual observations first-class typed LIFEOS perception outputs with preserved uncertainty, feeding the same Photon/field cognition loop.

### Baseline line/symbol anchors
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/life/PerceptionRuntime.kt` L8–95 — `PerceptionSource`, `PerceptionSignal`, `PerceptionBatch`, `PerceptionFusionEngine`.
- `core/language` word/linguistic field models — re-search exact implementation names and line anchors before M coding; do not duplicate existing language field logic.
- existing Android voice path `app/src/main/java/app/lifeos/next/AndroidVoiceCapture.kt` — inspect/re-anchor before adapter work.
- any historical `lifeos-multimodal-perception` branch must be verified against current head before code reuse; no blind cherry-pick.

### Exact implementation sequence
M.1 Extend perception with typed modality metadata while retaining generic `PerceptionSignal` source compatibility.
M.2 Add typed `SpeechObservation`, `GraphemeObservation`/`WritingObservation`, and `VisualObservation` candidates with confidence/candidate distribution and source timing.
M.3 Bind LIFEOS word/phoneme/grapheme field outputs to those observations; do not replace the intended LIFEOS-native model with an external black-box model by default.
M.4 Convert recognized speech/writing semantic result into normal immutable user/information Photons and route through the same causal cognition path.
M.5 Keep raw audio/image/binary asset provenance separate from recognized semantic Photon.
M.6 Mark raw visual/OCR capability ACTIVE only when a real visual adapter exists and passes capability/runtime health checks.
M.7 Dedupe repeated frames/chunks without collapsing materially different candidate distributions.
M.8 Preserve timestamps, confidence and source asset references through fusion.

### Required tests
- duplicate typed signal set -> stable batch identity;
- same audio/text candidates -> stable semantic Photon identity;
- confidence/candidate ordering preserved canonically;
- handwriting/speech handoff enters normal language/goal/cognition route;
- visual adapter absent -> capability unavailable/degraded, never falsely ACTIVE;
- raw asset and semantic output provenance remain linked but distinct.

### Definition of Done
Typed speech/writing/visual perception is productive and confidence-preserving with honest adapter availability; exact-head Debug CI + recovery + Product Gold pass.

---

## Block N — Creative workshop and guarded self-expansion

### Goal
Close the recursive capability-gap loop so LIFEOS can design/build missing capabilities while every generated module remains evidence-gated and non-activating until existing authorities approve it.

### Baseline line/symbol anchors
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/life/CreativeCapabilityOrchestrator.kt` L8–85 — expansion stages, request/plan and `fromGap`.
- `app/src/main/java/app/lifeos/next/kernel/GenesisCapabilityExpansionRuntime.kt` — existing Genesis handoff; re-resolve exact lines at N start.
- `app/src/main/java/app/lifeos/next/kernel/AutonomousToolWorkshopRuntime.kt` — existing tool-workshop path; re-resolve lines.
- `.github/workflows/buildstudio-candidate-host.yml` — isolated BuildStudio candidate gate; inspect exact current workflow lines before changes.
- generated tool lifecycle/evolution classes in `core/runtime/.../capability` and `.../evolution` — inspect exact canary/promotion/rollback authorities before integration.

### Exact implementation sequence
N.1 Convert missing capability into durable `CapabilityGapPhoton`/Genesis proposal with source goal/evidence lineage.
N.2 Resolve existing provider first; generate only when no admissible provider satisfies the contract.
N.3 Convert Genesis output into ToolWorkshop/local candidate or BuildStudio candidate according to capability type.
N.4 Attach stable ModuleIdentity, semantic contract, version, implementation hash, capabilities and provenance to every candidate.
N.5 Collect tests/build/security/resource evidence as Photons; candidate remains `activationAllowed=false`.
N.6 Route only evidence-passing candidate into canary; canary failure/quarantine cannot be bypassed.
N.7 Owner Policy/promotion remains final activation authority; record exact promotion seal and rollback target.
N.8 Activated module enters registry/topology/cognition and may emit new derived Photons only after promotion.
N.9 A later module version reinterprets historical evidence via new causal trace rather than rewriting prior outputs.
N.10 Keep GitHub/build credentials outside APK; app requests work through an authorized host seam only.

### Required tests
- existing capability -> no generation;
- missing capability -> non-activating Genesis proposal;
- candidate build/test failure -> no canary/promotion;
- canary rejection -> quarantined/no activation;
- Owner Policy rejection -> no activation;
- successful promotion -> registry/topology receives exact versioned identity;
- rollback restores prior provider deterministically;
- no generated path can set activation authority itself.

### Definition of Done
Capability gap -> generated candidate -> evidence -> canary -> Owner Promotion/rollback is productive end-to-end and cannot self-authorize; exact-head Debug CI + recovery + Product Gold pass.

---

## Block O — Typed runtime topology and dependency-aware startup

### Goal
Replace manual string wiring/sequential startup with typed manifests and a validated dependency DAG, while preserving deterministic lifecycle semantics and capability truth.

### Baseline line/symbol anchors
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/topology/LifeOsRuntimeTopology.kt` L8–150+ — `LifeOsSubsystemDescriptor`, canonical topology and snapshot propagation.
- `app/src/main/java/app/lifeos/next/LifeOsStartupComposition.kt` L3–55 — startup stages/hooks and strictly sequential `start`.
- `app/src/main/java/app/lifeos/next/LifeOsRuntimeWiring.kt` L8–100+ — manual string installation lists and optional runtime refresh.
- `core/model/src/main/kotlin/app/lifeos/core/model/ModuleIdentity.kt` — stable module identity contract; inspect lines before reuse/extension.
- Capability registry/provider state remains authoritative; do not duplicate it in topology manifests.

### Exact implementation sequence
O.1 Introduce typed `SubsystemId` and `SubsystemManifest` with dependencies, required capabilities, startup stage/owner and version/fingerprint metadata.
O.2 Express canonical topology once as manifests; derive descriptor projection and runtime wiring from it.
O.3 Delete/retire duplicated manual subsystem string lists after parity tests prove equivalent topology.
O.4 Build deterministic DAG validation: unique id, missing dependency detection, self-dependency rejection and cycle detection.
O.5 Compute startup layers from DAG; only nodes with satisfied dependencies may start.
O.6 Parallelize independent nodes within a layer only when their side effects/registries are independent; canonical completion ordering remains deterministic for evidence/readiness.
O.7 Propagate failure/degraded dependency state exactly as topology snapshot currently does.
O.8 Preserve `CapabilityRegistry` as capability/provider truth and binding registry as runtime lifecycle truth.
O.9 Add manifest fingerprints to startup/decision evidence so a topology change causes a new reproducible configuration identity.

### Required tests
- current logical subsystem inventory preserved or intentionally versioned;
- duplicate/missing/self/cyclic dependency rejected;
- deterministic layers/order from same manifest set;
- independent nodes may execute concurrently without changing final topology fingerprint;
- failed upstream marks downstream unavailable/degraded correctly;
- no manual wiring path can register an unknown subsystem;
- optional HotSwap/BuildStudio state remains honest.

### Definition of Done
One typed manifest graph drives topology and startup, with dependency-aware parallelism and deterministic evidence; exact-head Debug CI + recovery + Product Gold pass.

---

## Block P — Scale, observability, readiness and final Product Gold hardening

### Goal
Prove I–P under long-horizon/large-memory/restart/failure conditions and expose owner-visible causal/future/memory/readiness surfaces without granting UI authority.

### Baseline line/symbol anchors
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/life/LifeOsCompletionReadiness.kt` L5–75 — currently A–H-only `LifeOsBlock`, readiness fingerprint and six chaos scenarios.
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/life/LifeOsSelfValidation.kt` L10–180+ — currently validates A–H only.
- `app/src/main/java/app/lifeos/next/LifeOsReadinessCard.kt` — current owner-visible A–H card; re-resolve exact lines before extension.
- `app/src/main/java/app/lifeos/next/LifeOsChatViewModel.kt` readiness projection and topology summary — re-resolve exact lines.
- causal ledger, LifeGraph, FutureEvidence and LongTermMemory query/projection seams from Blocks I/K/L.

### Exact implementation sequence
P.1 Add an Optimization Round 2 readiness model I–P without invalidating historical A–H Product Gold evidence; prefer a separate/versioned layer over renaming old A–H semantics.
P.2 Add owner-visible query projections for causal trace/branches, domain facts, knowledge gaps, future evidence, goals/opportunities, memory stages and runtime health.
P.3 Add bounded pagination/filtering so observability does not load the entire long-term store into UI memory.
P.4 Add large-memory probes covering HOT/WARM/COLD/CRYSTALLIZED, atom/crystal lineage and rehydration.
P.5 Add long-horizon replay tests with module/policy/version evolution and historical reinterpretation.
P.6 Extend chaos matrix: restart during fan-out, duplicate ingest across restart, corrupt cursor/checkpoint, module-version reinterpretation, planner partial failure, all-candidates-blocked, generated-tool quarantine, startup-DAG failure, memory-crystal source gap.
P.7 Add deterministic trace/query fingerprints and owner-readable explanation of why an item/action was selected, blocked, rehydrated or generated.
P.8 Run full unit/lint/debug APK, cold-restart/emulator recovery, Product Gold and release evidence on one exact immutable head.
P.9 Seal final evidence with git SHA + APK SHA; do not label final 2.0.0/100% merely from passing unit tests.
P.10 Merge PR only after every I–P readiness item is READY and exact-head gates are green.

### Required tests
- I–P readiness cannot report COMPLETE with any missing/blocked block;
- old A–H readiness remains readable/semantically unchanged;
- 10k+/large synthetic Photon memory projection remains deterministic and bounded by configured budgets;
- repeated cold restart yields identical durable state fingerprints;
- all new chaos probes are contained;
- query pagination/order deterministic;
- UI remains read-only with respect to Owner Policy/promotion authority;
- exact final APK/evidence artifacts correspond to the same git SHA.

### Definition of Done
I–P readiness is fully evidenced, long-horizon/large-memory/recovery tests pass, Product Gold passes on the exact final head, APK/evidence hashes are sealed and PR can be merged.

---

## Block order and dependency rule

`I -> J -> K -> L -> M -> N -> O -> P`

Later blocks may use code already present, but they are not validated out of order. If a later implementation already exists (currently parts of K and L do), it remains `IMPLEMENTED/PENDING_VALIDATION_OR_INTEGRATION` until all earlier block gates and its own Definition of Done are satisfied.

At every block boundary the next plan is re-anchored to the new exact head before editing, exactly as in A–H.
