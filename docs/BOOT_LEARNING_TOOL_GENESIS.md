# LIFEOS P0 — Boot Cognition, Continuous Learning & Tool Genesis

## Why this is P0

The original LIFEOS boot/recovery/adaptation/tool modules describe an important product behavior that LIFEOS-new does not yet implement: on first start and every later restart, LIFEOS should not merely open stores and replay work. It should reconstruct its usable world state, identify what changed, recover the current user/project context, and derive what must be learned, repaired, planned, automated, or newly built.

This capability is therefore P0 alongside Chat Context Core.

## Architectural rule: boot-time cognition is not the whole learning system

Boot has two responsibilities only:

1. create a consistent initial snapshot and restore durable cognitive state;
2. start the continuous learning/runtime layer.

Learning must then remain active for the entire process lifetime and resume durably after process death. The system must not depend on app startup as the only moment when learning happens.

## P0-G — Boot Cognition Core

Original guidance includes the useful intent behind:

- `ForensicBootEngine`
- `PreInitRecoveryKernel`
- `ForensicRecoveryEngine`
- `DeepCoreConvergenceEngine`
- `DuplicateResolver`
- `CrashGuard`
- `AutonomousExpansionEngine`
- `AdaptationSynthesizer`
- `SelfValidationEngine`
- `ToolWerkstatt`
- `BuiltInToolEngine`
- `SynthesizedModule`
- `ShadowValidator`

The old implementations are not copied 1:1. Their capabilities are rebuilt on the new encrypted photon/task runtime.

## Boot sequence target

```text
PROCESS START
    ↓
BootSnapshotLoader
    ↓
Vault / schema / task integrity scan
    ↓
Lease + interrupted-work recovery
    ↓
Capability & permission discovery
    ↓
Conversation / project / goal rehydration
    ↓
Memory projection rehydration
    ↓
Automation state rehydration
    ↓
External-source delta discovery
    ↓
Tool / capability registry rehydration
    ↓
Gap & anomaly analysis
    ↓
BootCognitionReport
    ↓
start Durable Runtime
    ↓
start ContinuousLearningCoordinator
```

## Required boot components

### `BootSnapshotLoader`
Builds a consistent snapshot from durable sources without making the UI or ViewModel the source of truth.

Reads/reconstructs:
- photons and their latest revisions;
- task and checkpoint state;
- active conversation/project references;
- open goals and decisions;
- current day plan and automations;
- financial/planning projections when available;
- tool/capability registry;
- external connector/account capability state;
- last successful learning/consolidation watermark.

### `BootIntegrityScanner`
Checks:
- unreadable/corrupt vault items;
- schema versions;
- orphaned checkpoints;
- impossible task states;
- duplicate logical identities;
- missing projections that can be rebuilt;
- stale capability/tool registrations.

It reports findings first. Destructive repair is never the default.

### `BootContextRehydrator`
Restores enough context that the first chat message after restart can resolve references such as:
- "weiter";
- "mach da weiter";
- "die APK";
- "das andere Modul";
- "wie gestern".

This is the bridge between Boot Cognition and Chat Context Core.

### `CapabilityDiscoveryEngine`
Builds a current capability snapshot:
- built-in modules/fields/workers;
- connected services;
- Android permissions;
- notification/accessibility capabilities;
- external app adapters;
- repository/build capabilities;
- available tools and their health/version.

### `BootDeltaAnalyzer`
Compares current state with the last durable watermark and identifies:
- new/changed photons;
- unfinished goals/tasks;
- changed calendars/messages/financial inputs when adapters are available;
- stale or invalid context references;
- automation events that were missed while the app was not running;
- capability changes after app/update/permission changes.

### `BootCognitionReport`
A durable, inspectable result containing at minimum:
- boot generation;
- restored sources and counts;
- unreadable/recoverable items;
- resumed tasks;
- context confidence;
- detected deltas;
- capability gaps;
- suggested learning/recovery actions;
- tool/function proposals created during boot;
- safe-mode/recovery recommendation if invariants fail.

The report itself becomes a photon with provenance.

## P0-H — Continuous Learning Coordinator

Learning starts from boot but does not stop there.

```text
new photon / task result / message / app event / automation outcome
    ↓
ContinuousLearningCoordinator
    ↓
Context update
    + Memory update
    + Outcome evaluation
    + Pattern extraction
    + Goal progress update
    + Capability-gap detection
    ↓
new derived photons / projections / tasks
```

### Learning inputs

- user chat and corrections;
- accepted/rejected plans;
- task success/failure;
- retry/recovery history;
- email/calendar/WhatsApp events once Live Data Hub exists;
- financial outcomes;
- external app interaction outcomes;
- code/build/test results;
- explicit user preferences and recurring behavior patterns.

### Learning outputs

- stronger/weaker context relations;
- semantic/episodic/procedural memory updates;
- revised automation suggestions;
- planner constraints;
- confidence changes;
- reusable procedures;
- detected missing capability;
- `CapabilityGap` photons;
- `ToolProposal` or `FunctionProposal` tasks.

### Critical rule

Learning must preserve the distinction between:
- observation;
- inference;
- user-confirmed preference;
- generated strategy;
- verified outcome.

Confidence must not be treated as truth, and repeated observations must not silently become irreversible policy.

## P0-I — Capability Gap & Tool Genesis

The system should be able to notice that the current modules/tools cannot satisfy a goal efficiently and convert that into a controlled development process.

```text
Goal / task
    ↓
CapabilityMatcher
    ↓
missing or weak capability
    ↓
CapabilityGap photon
    ↓
ToolNeedAnalyzer
    ↓
ToolProposal / FunctionProposal
    ↓
DesignSpec
    ↓
BuildStudio candidate
    ↓
Static validation
    ↓
Tests / replay / benchmark
    ↓
Risk + permission review
    ↓
Shadow / manual bounded trial
    ↓
CapabilityRegistry
```

## Tool types

A "tool" does not always mean generated executable code. LIFEOS should choose the smallest safe solution:

1. **Procedure tool** — reusable task/automation recipe.
2. **Connector tool** — wrapper around an existing API/app capability.
3. **Interaction tool** — safe Android intent/accessibility workflow.
4. **Query/analysis tool** — structured transformation or search pipeline.
5. **Code tool** — generated/modified source code built through BuildStudio.
6. **Module implementation** — a new verified ForceField/worker/adapter shipped through the debug build path.

This prevents unnecessary code generation when a workflow or adapter is enough.

## BuildStudio relationship

Tool Genesis decides **what capability is missing and what should be built**.

BuildStudio decides **how source changes are designed, patched, tested and built**.

They are separate layers:

```text
CapabilityGap
    ↓
Tool Genesis
    ↓
DesignSpec
    ↓
BuildStudio
    ↓
Patch / tests / debug APK
    ↓
Verification
    ↓
Registration
```

## Activation policy

Generating a tool/function is not permission to activate it.

Initial activation rules:
- procedure/query tools may be registered after deterministic validation;
- external-interaction tools require PermissionBroker/InteractionPolicy;
- code/module changes require tests + `lintDebug` + `assembleDebug`;
- security, crypto, task ownership, permission policy, recovery invariants and signing/update trust are never self-replaced;
- arbitrary downloaded DEX is not part of the initial design;
- later hot-swap is limited to verified implementations and shadow/canary promotion.

## Relationship to the Personal Operations Layer

Boot/learning continuously feeds the P0 user features:

```text
Boot + Continuous Learning
        │
        ├── Chat Context
        ├── Personal Memory
        ├── Live Data Hub
        ├── Day Planner
        ├── Automation
        ├── Finance
        ├── External App Gateway
        └── BuildStudio / Tool Genesis
```

Example:

```text
boot detects:
- work calendar changed
- two important emails arrived
- WhatsApp notification indicates appointment cancellation
- recurring bill due tomorrow
- previous automation failed because no adapter exists

↓
rehydrate context
↓
replan day
↓
create finance reminder
↓
create CapabilityGap for missing adapter
↓
Tool Genesis proposes an interaction/connector tool
↓
BuildStudio only creates code if a workflow/connector cannot solve it
```

## Revised near-term implementation order

1. **3E Health Core** — necessary guardrail for boot self-recovery and tool development.
2. **3F Boot Cognition Core** — snapshot, integrity scan, rehydration, capability discovery, boot report.
3. **3G Continuous Learning Core** — always-on delta/outcome learning and durable learning watermark.
4. **3H Capability Gap / Tool Genesis Core** — proposals only, no autonomous activation yet.
5. **4A Chat Context Core**.
6. **4B Conversation/Project Memory**.
7. **4C Live Data Hub**.
8. **4D Personal Planner + Automation Orchestrator**.
9. **4E Finance Core**.
10. **4F External App Interaction Gateway**.
11. **4G BuildStudio v1** — then connect Tool Genesis to real code/build tasks.
12. **4H WorkerRegistry + CapabilityMatcher**.

After this base is stable: DeepSearch/Genesis/Convergence, Field/Weltformel v2, verified hot-swap, bounded evolution.

## Acceptance criteria for the first useful Boot/Learning release

- After process restart, the active project/chat goal can be reconstructed without relying on ViewModel memory.
- Durable unfinished tasks and checkpoints resume safely.
- A boot report records what was restored, changed and recovered.
- The system can detect at least one structured `CapabilityGap` instead of merely failing a task.
- New task/chat outcomes update a durable learning watermark during runtime, not only at boot.
- Tool/function proposals are persisted with provenance and rationale.
- No proposal can silently activate executable code.
