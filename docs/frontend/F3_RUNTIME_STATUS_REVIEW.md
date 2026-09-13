# F3 — Compact Runtime Status and Health Drill-down

Base: `main@463783d99d97cdb2b09beb1c04eeff6642887c3d`

Status: planning / two static reviews complete; no productive F3 UI code in this commit.

## Goal

Remove the diagnostic wall from the primary chat surface and replace it with one compact, truthful runtime-health indicator. Preserve the complete A–P readiness and topology evidence under progressive disclosure and in the System destination.

This block is projection-only. It must not grant runtime authority, mutate readiness, infer CI/Product-Gold state, bypass owner policy, or manufacture health from missing evidence.

## Current state

`LifeOsChatScreen` currently renders all of the following above the conversation:

- boot status text;
- registered subsystem count;
- capability provider count;
- unavailable subsystem count;
- generated provider count;
- full `LifeOsReadinessCard` with every A–P row.

The System destination is still a migration placeholder.

`LifeOsChatViewModel` currently collapses a missing `LifeOsProcessTopology.snapshot()` to zero-valued topology counters. That means `0 unavailable` does not prove a topology snapshot was actually observed.

## Static review A — truth-source and precedence review

### Evidence sources

1. `KernelBootstrapStatus` is authoritative for launcher/kernel bootstrap state.
2. `LifeOsReadinessSnapshot` is authoritative for A–P runtime self-validation. It is runtime evidence only and never CI/Product-Gold evidence.
3. `LifeOsRuntimeTopologySnapshot` is authoritative for productive subsystem/provider topology.
4. Existing `LifeOsChatUiState` counters are compatibility projections, not sufficient proof that topology evidence exists.

### Required compact status precedence

The UI status model must be deterministic and fail closed:

1. `FAILED`
   - kernel boot status is `FAILED`.
   - never overridden by readiness/topology values.
2. `STARTING`
   - boot status is `CREATED` or `LOADING`.
3. `VERIFYING`
   - boot is operational (`READY` or `DEGRADED`) but readiness evidence or topology evidence is unavailable.
4. `DEGRADED`
   - boot status is `DEGRADED`; or
   - any A–P block is `DEGRADED` or `BLOCKED`; or
   - topology reports unavailable/unbound/degraded subsystems.
5. `READY`
   - boot status is exactly `READY`;
   - readiness snapshot exists and `complete == true`;
   - topology snapshot has been observed;
   - topology is fully connected and fully operational.

Consequences:

- Missing evidence never becomes green/READY.
- A blocked A–P block cannot be hidden behind a healthy boot status.
- A topology failure cannot be hidden behind complete A–P readiness.
- Product Gold, CI, emulator recovery and release state are never inferred in-app.

### Amendment to the original F3 plan

Add explicit topology-observation evidence to the app UI projection. Do not infer it from four zero counters.

Preferred bounded shape:

```text
RuntimeTopologyUiEvidence
- observed: Boolean
- registeredSubsystems: Int
- operationalSubsystems: Int
- unavailableSubsystems: Int
- unboundSubsystems: Int
- degradedSubsystems: Int
- capabilityProviders: Int
- generatedProviders: Int
- fullyConnected: Boolean
- fullyOperational: Boolean
```

The ViewModel derives this from `LifeOsRuntimeTopologySnapshot`; runtime topology classes remain unchanged.

## Static review B — navigation, ownership and disclosure review

### Primary chat

The chat header must contain exactly one runtime-health surface beneath the LIFEOS title. It may show a short text state plus an accessible state marker, but no raw subsystem/provider count wall and no full A–P card.

Tapping the compact status opens owner-visible health details. The chat flow, event list and F2 composer state must remain unchanged.

### System destination

The current System placeholder becomes the durable home for runtime-health details. F3 should move/reuse the existing readiness projection there rather than duplicate readiness logic.

The complete owner-visible detail surface contains:

- current compact health classification and explanatory summary;
- boot state;
- topology summary;
- unavailable/unbound/degraded counts;
- provider counts;
- all A–P readiness rows with exact runtime detail strings;
- readiness fingerprint under technical disclosure.

No health screen action is allowed to change runtime state in F3.

### Drill-down reuse

Use one `RuntimeHealthContent` projection/render contract for both:

- chat modal bottom-sheet drill-down; and
- System destination health surface.

This avoids two health interpretations drifting apart.

## Planned files

### New

`app/src/main/java/app/lifeos/next/ui/components/LifeOsRuntimeStatus.kt`

- `RuntimeHealthLevel`: `STARTING`, `VERIFYING`, `READY`, `DEGRADED`, `FAILED`.
- `RuntimeTopologyUiEvidence`.
- `RuntimeHealthUiModel`.
- pure `buildRuntimeHealthUiModel(...)` mapping.
- compact `LifeOsRuntimeStatus(...)` composable.
- user-visible labels and accessibility text are derived from the same model.

`app/src/main/java/app/lifeos/next/ui/system/RuntimeHealthSheet.kt`

- shared `RuntimeHealthContent(...)`.
- modal-sheet wrapper for chat drill-down.
- System-destination wrapper/full surface.
- A–P rows reuse the existing readiness projection rather than reimplementing runtime validation.

`app/src/test/java/app/lifeos/next/ui/components/LifeOsRuntimeStatusTest.kt`

- deterministic status precedence tests.
- no false READY with missing evidence.
- FAILED overrides otherwise healthy evidence.
- BLOCKED/DEGRADED readiness cannot map READY.
- unavailable/unbound/degraded topology cannot map READY.
- fully healthy boot + readiness + topology maps READY.
- user-facing summary never claims Gold/release/CI.

### Modify

`app/src/main/java/app/lifeos/next/LifeOsChatViewModel.kt`

- retain current readiness source.
- derive explicit `RuntimeTopologyUiEvidence?` from the real topology snapshot.
- retain current count fields temporarily if still referenced elsewhere; remove only after repository-wide verification.
- do not change F2 send/persistence semantics.

`app/src/main/java/app/lifeos/next/ui/chat/LifeOsChatScreen.kt`

- remove raw topology count line.
- remove inline full `LifeOsReadinessCard`.
- replace separate boot status with one compact `LifeOsRuntimeStatus`.
- own only presentation state for opening/closing the detail sheet.
- keep event rendering and `ChatComposer` behavior unchanged.

`app/src/main/java/app/lifeos/next/ui/PlaceholderScreens.kt`

- split System out of generic migration placeholder or remove `SystemMigrationScreen` after replacement.
- Memory and Goals placeholders remain untouched.

`app/src/main/java/app/lifeos/next/ui/LifeOsRoot.kt`

- route SYSTEM to the new runtime-health System surface using the same `LifeOsChatViewModel` instance.
- no kernel/ViewModel recreation.

`app/src/main/java/app/lifeos/next/LifeOsReadinessCard.kt`

- keep `buildReadinessUiModel` as the compatibility/readiness projection.
- either make the composable reusable inside System detail or retire only the old chat placement.
- preserve the explicit rule that it makes no CI/Gold claim.

`app/src/test/java/app/lifeos/next/LifeOsReadinessCardTest.kt`

- preserve A–P completeness and no-Gold assertions.
- adjust only if the rendering function moves package/location.

Potential CI path update only if existing globs do not already cover the new files. `app/src/main/java/app/lifeos/next/ui/**` and app tests are already covered by the relevant frontend/debug paths; no gratuitous workflow change.

## Pure mapping contract

The mapper takes only already-authorized runtime evidence:

```text
buildRuntimeHealthUiModel(
    bootStatus,
    readiness,
    topologyEvidence,
)
```

It returns a stable projection:

```text
RuntimeHealthUiModel
- level
- compactLabel
- summary
- bootLabel
- readinessSummary
- topologySummary
- detailsAvailable
```

Rendering must not contain independent health logic beyond model-to-style mapping.

## Proposed user-visible compact labels

- STARTING: `LIFEOS startet`
- VERIFYING: `Runtime wird geprüft`
- READY: `Runtime bereit`
- DEGRADED: `Runtime eingeschränkt`
- FAILED: `Runtimefehler`

The text is always present; color/icon may support it but never be the sole state signal.

## Detailed System health content

Order:

1. health headline + truthful summary;
2. boot state;
3. topology summary;
4. provider summary;
5. A–P readiness summary;
6. expandable A–P block rows;
7. technical evidence: readiness fingerprint and topology observation state.

No Product-Gold badge is shown inside the APK because Product Gold is external CI evidence.

## Test matrix

### JVM/pure tests

1. boot `FAILED` + complete readiness + healthy topology => `FAILED`.
2. boot `LOADING` => `STARTING` regardless of stale evidence.
3. boot `READY` + readiness null => `VERIFYING`.
4. boot `READY` + readiness complete + topology missing => `VERIFYING`.
5. boot `READY` + BLOCKED readiness => `DEGRADED`.
6. boot `READY` + DEGRADED readiness => `DEGRADED`.
7. boot `READY` + complete readiness + unavailable topology => `DEGRADED`.
8. boot `READY` + complete readiness + unbound topology => `DEGRADED`.
9. boot `READY` + complete readiness + degraded topology => `DEGRADED`.
10. boot `READY` + complete readiness + fully ACTIVE/connected topology => `READY`.
11. compact and detail models share the same level/summary.
12. labels/summaries contain no `Gold`, `release` or synthetic CI claim.

### Android/UI contract

F3 does not need to invent a new state-changing device test. The productive verification requirement is:

- app compiles/lints;
- unified shell remains stable;
- chat and composer remain reachable;
- System route renders from the same ViewModel/runtime evidence;
- existing Recovery suite remains green after the UI/state projection changes.

If a targeted Compose/device test is added, it should assert navigation + health disclosure semantics, not mock runtime health as proof of production readiness.

## Recovery/CI implications

The existing Android Emulator Recovery workflow already watches:

- `app/src/main/java/app/lifeos/next/LifeOsChatViewModel.kt`;
- `app/src/main/java/app/lifeos/next/ui/**`.

Therefore F3 production changes should trigger Recovery without another workflow-path edit.

Required exact-head gates before merge:

- Core Fast;
- Android Debug CI (unit tests + lint + debug APK);
- Android Emulator Recovery;
- LIFEOS Product Gold.

After merge, repeat all four on the exact merged `main` SHA and seal Product Gold evidence with `candidate_sha == source_head_sha == checkout_sha`.

## Definition of Done

F3 is complete only when:

1. Chat contains one compact runtime status surface and no diagnostic wall.
2. Status precedence is deterministic and fail-closed.
3. Missing readiness/topology evidence never renders READY.
4. Boot FAILED never renders READY/DEGRADED.
5. Full A–P readiness remains visible under health detail and in System.
6. System route is backed by the same ViewModel/runtime evidence and does not recreate the kernel.
7. F2 chat composer/send/persistence behavior is unchanged.
8. No runtime/policy/persistence authority is added to UI.
9. JVM mapping tests cover every level and negative READY cases.
10. Final PR-head Core/Debug/Recovery/Product Gold are green on one source SHA.
11. Exact merged main repeats 4/4 green with sealed SHA evidence.

## Explicitly out of scope

- voice/image chat surfaces (F4);
- Memory workspace (F5);
- Goals workspace (F6);
- DecisionTrace/Why UI (F7);
- Tool Center (F8);
- design-system-wide restyling (F9);
- release/signing/AAB work.
