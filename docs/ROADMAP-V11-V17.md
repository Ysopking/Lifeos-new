# LIFEOS V11–V17 Execution Roadmap

This roadmap starts from the V10 hot-swap stack. It is intentionally implementation-oriented: every block names the existing runtime surfaces it must reuse, the new durable state it may introduce, the effect/resource gates it must pass, and the acceptance evidence required before the next block is declared complete.

## Global invariants for V11–V17

- Private APK only. No Google Play or app-store publication workflow.
- Reuse productive process instances and existing registries; do not create parallel Tool/Capability/Health/Goal/World-Formula runtimes.
- Every autonomous or host-facing action remains subject to the existing epistemic and effect boundaries: V5 Convergence, V14 Owner Policy, V16 Resource Budgets / World Formula allocation, hardware health, generated-tool sandbox/trial/promotion evidence, and explicit-user gates where already required.
- Durable state is append-only or CAS-protected, encrypted locally on Android, restart-safe and fail-closed on unreadable/corrupt history.
- No process restart may multiply user-visible effects, generated-tool executions, DeepSearch work, artifact mutations, resource reservations or policy authority.
- Outcomes, measurements, artifacts and important decisions become provenance-bound Photon evidence and re-enter the field / learning loop.
- Hard owner/system quotas are absolute ceilings. Learned or hardware-adaptive policies may only reduce or distribute capacity, never silently raise authority or hard limits.
- A block is not accepted from static inspection alone. Acceptance requires real compiler/tests and, where applicable, Android process-kill/restart/emulator/device evidence.

---

## V11 — Autonomous ToolWorkshop

### Goal
Turn the already existing ToolWorkshop/Genesis/Trial pipeline into a durable, bounded autonomous capability-gap resolution loop without bypassing current promotion and hot-swap safety boundaries.

### Existing surfaces to reuse
- `ToolWorkshopCoordinator`
- `PrivateToolWorkshopAdapters`
- `GeneratedToolGenesisCoordinator`
- `GeneratedToolRequestCoordinator`
- `GeneratedToolTrialRunner`
- `GeneratedToolRegistry` and encrypted generated-tool state/artifact repositories
- V8 Controlled Evolution / Canary evidence
- V10 atomic hot-swap for replacement providers
- V16 `TOOL_WORKSHOP` resource domain

### Work packages

#### V11-A — Deterministic workshop job identity
- Add a content-addressed `ToolWorkshopJobId` derived from capability gap, source Photon/revision, specification policy and workshop version.
- Replace autonomous dependence on random UUID identity for durable execution. Human/debug-only helper IDs may remain non-authoritative.
- Persist a durable workshop job ledger with states such as `REQUESTED`, `SPECIFIED`, `DESIGNED`, `IMPLEMENTED`, `BUILT`, `TESTED`, `SECURITY_VALIDATED`, `VERIFIED`, `TRIAL_READY`, `REJECTED`, `INTERRUPTED`.
- A restart must reconstruct the exact stage and never rebuild/retest a completed stage unless its bound input revision changed.

#### V11-B — Autonomous trigger and admission
- Capability gaps emitted by routing/V5/V7 may create a workshop request Photon.
- Deduplicate repeated equivalent gaps against active/completed workshop jobs.
- Require V14 `TOOL_REQUEST` authority before autonomous generation begins.
- Require current V16/World Formula `TOOL_WORKSHOP` allocation before each resource-consuming stage.
- Explicitly keep network usage at zero unless a future owner grant and a declared provider require it.

#### V11-C — Stage-level durable execution
- Reserve before implementation/build/test/security/verification work.
- Persist stage output before budget settlement.
- Commit actual usage once; release unused reservations.
- Persist exact source/build/test/security/verifier fingerprints so restart recovery cannot substitute new artifacts under an old job identity.

#### V11-D — Trial and promotion handoff
- A verified artifact may automatically enter bounded TRIAL only through the current sandbox admission path.
- Trial evidence remains authoritative and immutable.
- Novel capabilities use the existing bounded novel-promotion path.
- Replacement capabilities use V10 hot-swap and standby/revert semantics.
- Generation must never imply activation.

#### V11-E — Learning and rejection feedback
- Rejected build/test/security/verification outcomes become evidence Photons.
- Feed repeated failure signatures back to specification/design selection without weakening policy, sandbox, test or security requirements.
- Track success rate, latency, resource use and post-activation health by workshop strategy/version.

### V11 Definition of Done
- Capability gap -> durable workshop job -> verified artifact -> trial -> guarded promotion/hot-swap works end-to-end.
- Restart at every stage resumes without duplicate stage work or duplicate activation.
- Owner revocation and V16 exhaustion stop before new work.
- Corrupt workshop ledger fails closed.
- Generated artifacts remain non-routable until existing promotion/hot-swap gates succeed.

Planned branch: `v11/autonomous-toolworkshop`

---

## V12 — DeepSearch v2

### Goal
Upgrade the current bounded DeepSearch path into a restart-safe evidence exploration engine that reasons over competing hypotheses, provenance and uncertainty while remaining explicitly resource-governed.

### Existing surfaces to reuse
- `DEEP_SEARCH` V16 resource domain and current DeepSearch budget mapping
- Local Photon/evidence stores
- Field/V5 convergence and unresolved-hypothesis semantics
- V7 durable goals/plans/outcomes
- Capability router and V11 workshop requests for missing search capabilities

### Work packages

#### V12-A — Search mission ledger
- Introduce content-addressed `DeepSearchMissionId` bound to goal/source Photon/revision, search policy version and declared source scopes.
- Durable states: `PLANNED`, `EXPLORING`, `SYNTHESIZING`, `VERIFYING`, `COMPLETED`, `UNRESOLVED`, `BLOCKED`, `CANCELLED`.
- Persist frontier/checkpoints so process death resumes the exact search rather than restarting breadth/depth from zero.

#### V12-B — Evidence graph and hypothesis competition
- Represent findings as provenance-bound evidence Photons with authority, confidence, temporal freshness and semantic relations.
- Maintain competing hypotheses instead of forcing a winner when evidence is insufficient.
- Explicit contradiction/conflict edges and unresolved branches.
- V5 convergence decides whether a search result is actionable, unresolved or requires capability expansion.

#### V12-C — Adaptive budgeted planner
- Derive depth, breadth, elapsed time, candidate count and work units from the current World Formula allocation rather than fixed constants.
- Rebalance remaining search budget based on information gain / unresolved uncertainty, but never exceed the durable hard reservation.
- Thermal/battery constraints can shrink or suspend the mission.

#### V12-D — Source/capability policy
- Current local-only sources remain valid without network authority.
- Any future external/network search provider must declare capability, provenance and V14 `NETWORK_ACCESS` / provider authority; no implicit network fallback.
- Missing source capability may emit a V11 workshop request rather than silently weakening the query.

#### V12-E — Durable synthesis
- Synthesis result stores exact supporting/contradicting evidence IDs and search-policy revision.
- Final result becomes an outcome Photon and can complete a V7 plan step.
- Restart after result persistence but before plan settlement must rebind the same result, not repeat search.

### V12 Definition of Done
- Goal -> mission -> evidence graph -> competing hypotheses -> convergence-aware synthesis works end-to-end.
- Budget allocation controls real planner depth/breadth/work.
- Restart/corruption/revocation/exhaustion behavior is deterministic and fail-closed.
- Local-only operation remains fully usable.

Planned branch: `v12/deepsearch-v2`

---

## V13 — Collaborative Artifacts

### Goal
Make document/report/plan/artifact creation a real multi-module collaboration process where every contribution is provenance-tracked and the finished artifact re-enters LIFEOS as a Photon.

### Architecture
A single module must not own the whole artifact. Relevant modules contribute through typed roles such as analysis, research, drafting, verification, enrichment and finalization.

### Work packages

#### V13-A — Artifact project ledger
- Content-addressed `ArtifactProjectId` bound to user goal, source revisions, artifact type and workflow version.
- Durable artifact states: `PLANNED`, `COLLECTING`, `DRAFTING`, `VERIFYING`, `MERGING`, `FINALIZED`, `BLOCKED`.
- Immutable revision graph; every artifact revision identifies parent revisions and contributor evidence.

#### V13-B — Typed contributor protocol
- Define contribution contracts: `ANALYSIS`, `RESEARCH`, `DRAFT`, `FACT_CHECK`, `CONSISTENCY_CHECK`, `ENRICHMENT`, `FORMAT`, `FINAL_REVIEW`.
- Each contribution records module/capability, input Photon IDs, confidence, assumptions, unresolved questions and produced revision.
- Contributors cannot overwrite another contribution; merge creates a new revision.

#### V13-C — Convergence-aware merge
- Conflicting contributions are surfaced as competing artifact hypotheses/sections.
- Merge/finalization uses V5/Field evidence rather than last-writer-wins.
- Unsupported claims remain marked unresolved instead of being silently invented.

#### V13-D — Resource and effect gates
- Artifact work receives its own explicit V16 demand/profile (or a dedicated domain if justified by measured contention).
- File export/share is a separate V14 host effect from internal artifact generation.
- External handoff remains distinct from successful internal finalization.

#### V13-E — Photon reintegration
- Finalized artifact and major revisions become Photons with provenance, relationships, confidence and lifecycle.
- Later goals/DeepSearch may cite/reuse exact artifact revisions.

### V13 Definition of Done
- One artifact is demonstrably produced by at least three typed contributors with preserved provenance.
- Conflicts are represented and resolved/left unresolved explicitly.
- Restart preserves the revision graph and does not duplicate contributions.
- Finalization and external export are distinct audited transitions.

Planned branch: `v13/collaborative-artifacts`

---

## V14 — Owner Policy Completion

### Goal
Finish the V14 foundation as the single shared effect-authority layer for every current autonomous/effectful path.

### Existing foundation
The encrypted append-only Owner Policy ledger, revisioned grants/revokes, resource/capability/provider constraints and re-read-on-evaluation behavior already exist.

### Work packages
- Inventory every host/effect boundary and require a typed `OwnerEffectRequest`: file write/export, network, reminder, communication/share preparation, provider activation, tool request, tool execution, external app handoff and future connector effects.
- Ensure every resumed/prepared effect re-reads policy immediately before exposure.
- Bind relevant grants to exact capability/provider version and durable V16 account/reservation where applicable.
- Add policy decision reason codes and stable decision IDs for V15 explainability.
- Add a read-only policy simulation API: “would this action currently be allowed?” without granting or executing anything.
- Explicitly test revocation in every crash window: before reservation, after reservation, after preparation and before effect exposure.
- No subsystem may seed/recreate default authority after non-pristine policy history exists.

### V14 Definition of Done
- Zero productive host/effect paths bypass the shared policy layer.
- Revocation wins across process restart and prepared work.
- Policy corruption fails closed while preserving readable unrelated stores.

Planned branch: `v14/owner-policy-completion`

---

## V15 — Explainability and Decision Trace

### Goal
Expose why LIFEOS reached a decision using the actual durable evidence/decision chain, not post-hoc generated explanations.

### Work packages

#### V15-A — Cross-runtime trace identity
- Introduce stable `DecisionTraceId` propagated through goal, plan step, convergence checkpoint, routing, policy decision, World Formula allocation, resource reservation, DeepSearch mission, workshop job, evolution/hot-swap and artifact revision where applicable.

#### V15-B — Explanation graph
- Store typed nodes/edges referencing existing durable IDs rather than copying mutable prose.
- Example edges: `GOAL -> PLAN_STEP`, `PLAN_STEP -> CONVERGENCE`, `CONVERGENCE -> ROUTE`, `ROUTE -> POLICY`, `POLICY -> BUDGET`, `BUDGET -> EXECUTION`, `EXECUTION -> OUTCOME`.
- Record blocked alternatives and the exact reason they were rejected.

#### V15-C — Human-readable projection
- Build a read-only explanation projector from the trace graph.
- Distinguish observed facts, policy constraints, resource constraints, inferred hypotheses and unresolved uncertainty.
- Never claim external delivery/activation/success that the durable trace does not prove.

#### V15-D — Diagnostics
- Add private diagnostics for current/previous trace, resource spend, policy revision, health state and active provider.
- Explanations must still work after restart from durable IDs.

### V15 Definition of Done
- Any important goal/effect can answer “why this action/provider/result?” from durable evidence.
- Explanation reconstruction is deterministic and references exact source IDs/revisions.
- No explanation path can mutate runtime authority or execution state.

Planned branch: `v15/explainability-trace`

---

## V16 — Resource Intelligence Completion

### Goal
Finish the already advanced V16 foundation by making hardware-aware World Formula allocation, durable reservations and measured execution feedback universal across LIFEOS.

### Existing foundation
- Shared resource dimensions: elapsed time, work units, memory, I/O, network and candidate count.
- Encrypted durable CAS accounts/reservations.
- Hardware state -> World Formula readiness -> adaptive soft envelope.
- Shared BudgetBroker / resource domains for major work classes.
- Goal, DeepSearch/Cognition and Evolution integration is partially present in the stacked roadmap.

### Work packages

#### V16-A — Complete domain coverage
- Goal execution
- Cognition
- DeepSearch
- ToolWorkshop
- Controlled Evolution
- Hot-Swap / revert
- Self-Healing
- Artifact collaboration
- Background learning/maintenance

Every resource-consuming path must reserve before work and settle actual usage after durable outcome/checkpoint persistence.

#### V16-B — Measurement Photons
- Record measured elapsed time, work units, peak/estimated memory, I/O, network, thermal/battery context, success/failure and outcome utility where observable.
- Measurements become provenance-bound Photons and feed the next allocation cycle.

#### V16-C — Learned soft-cost model
- Estimate expected resource cost by operation/capability/provider/workshop strategy from prior measurements.
- Learning may improve scheduling/allocation but cannot raise hard quotas or owner authority.
- Unknown operations use conservative estimates.

#### V16-D — Cross-domain arbitration
- Prioritize using goal relevance, urgency/deadline, expected utility, confidence, health state and starvation/fairness history.
- Preserve hard per-domain and global ceilings.
- Avoid starvation while still allowing thermal/emergency suspension.

#### V16-E — Restart-safe settlement audit
- Detect and reconcile all orphaned reservations at boot using durable execution state.
- Never infer “completed” solely from resource spend; settlement follows authoritative outcome state.

### V16 Definition of Done
- All significant work is covered by one shared resource-intelligence contract.
- No domain can create capacity or escape the World Formula/hard quota envelope.
- Real measured feedback demonstrably changes only future soft allocation estimates.
- Restart cannot double-charge or leak held reservations.

Planned branch: `v16/resource-intelligence-completion`

---

## V17 — Final 100% Hardening

### Goal
Prove the complete private LIFEOS stack survives realistic failures, remains owner-controlled, and is operationally usable before the roadmap marker is allowed to reach 100%.

### Work packages

#### V17-A — Crash-window matrix
For every durable subsystem, inject process death at all persistence/exposure boundaries:
- Goal planning/execution/outcome
- Convergence checkpoint
- Owner policy
- Resource reservation/settlement
- Self-healing action/probe
- Evolution Canary/outcome/promotion
- Hot-swap/revert
- ToolWorkshop stages/trials
- DeepSearch frontier/synthesis
- Artifact revision/finalization

Acceptance: restart yields one valid authoritative state and no duplicate effect.

#### V17-B — Corruption and migration
- Corrupt/truncate each encrypted ledger/store independently and verify fail-closed behavior.
- Validate versioned codec/schema migration strategy and bounded payload handling.
- Verify unrelated readable data remains available where safe.

#### V17-C — Device/resource endurance
- Long-running private APK soak tests.
- Thermal throttling, low battery, storage pressure, memory pressure and background/foreground transitions.
- Verify budget broker reduces/suspends work without corrupting ledgers.

#### V17-D — Owner-control acceptance
- Revoke authority while work is prepared/in-flight and verify the next effect boundary blocks.
- Validate no default authority is silently recreated after history exists.
- Validate safe diagnostic visibility of blocked/recovered/quarantined states.

#### V17-E — Functional end-to-end journeys
At minimum:
1. Goal -> plan -> convergence -> action -> persisted outcome -> explanation.
2. Missing capability -> autonomous ToolWorkshop -> trial -> guarded promotion -> goal completion.
3. Existing provider regression -> self-healing/evidence -> authorized hot-swap revert.
4. Complex question -> DeepSearch v2 -> evidence graph -> artifact collaboration -> finalized artifact Photon.
5. Process death during each of the above -> deterministic recovery without duplicate user-visible effect.

#### V17-F — Build/acceptance evidence
- Core unit/integration tests green on a real runner.
- Android lint/debug assembly green.
- Emulator process-kill/restart recovery green.
- Physical private-device smoke/recovery checks where Android Keystore/process behavior matters.
- Record accepted APK commit SHA and exact roadmap completion evidence.

### V17 Definition of Done / 100% marker
The roadmap reaches 100% only when V11–V16 are accepted, all V17 crash/corruption/owner/resource/end-to-end gates pass on real execution infrastructure, and no known path bypasses Convergence, Owner Policy, durable resource accounting or restart-safe outcome binding.

Planned branch: `v17/final-hardening`

---

## Planned stacked branch order

`v10/hot-swap`
→ `v11/autonomous-toolworkshop`
→ `v12/deepsearch-v2`
→ `v13/collaborative-artifacts`
→ `v14/owner-policy-completion`
→ `v15/explainability-trace`
→ `v16/resource-intelligence-completion`
→ `v17/final-hardening`

Later branch heads should be created only when their immediate predecessor is stable enough to avoid freezing stale snapshots of unfinished stack work.

## Immediate preparation after V10

1. Create the V11 branch from the final accepted V10 head.
2. Add a durable WorkshopJob ledger + codec/repository first, before autonomous triggers.
3. Replace autonomous random tool identity with content-addressed job/tool identity.
4. Route each workshop stage through V14 `TOOL_REQUEST` / `TOOL_EXECUTION` and V16 `TOOL_WORKSHOP` allocation.
5. Reuse the current Workshop -> Trial -> Controlled Evolution -> V10 Hot-Swap path for activation.
6. Only after V11 recovery tests pass, fork V12.
