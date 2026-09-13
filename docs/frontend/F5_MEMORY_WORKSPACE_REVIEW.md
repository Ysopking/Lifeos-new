# F5 — Memory Workspace review and execution contract

Base SHA: `fb8a7f453aeb81d1f023aefc8f4ea299a899e62f`

## Goal

Replace the placeholder `Gedächtnis` tab with a read-only workspace backed by LIFEOS' existing durable life-memory projection. The screen exposes the real memory stages, semantic atoms/crystals and episodes without adding a second memory policy or hidden write authority.

## Non-negotiable boundaries

1. `DurableLifeMemoryRuntime` remains the authority for long-term memory semantics.
2. The UI never calls `rebuild()` or `recordAccess()` merely because the user opens, searches or browses memory.
3. Source Photons remain authoritative. Missing source evidence fails closed in details.
4. The UI does not activate tools, alter goals, mutate owner policy, rewrite Photons or trigger autonomous execution.
5. New Photons absent from the current productive memory snapshot are shown only as explicit `NEW` evidence; they never receive an inferred HOT/WARM/COLD/CRYSTALLIZED stage.
6. Image previews reuse the same encrypted-vault + descriptor/hash/dimension verification path proven by F4.
7. Existing chat, voice and F4 image behavior remains source-compatible.
8. Old `MainActivity` remains until later migration parity is proven.

## Product model

- **Jetzt**: productive HOT/WARM sources plus explicit new/unprojected evidence.
- **Themen**: productive `MemoryAtomKind` groups plus crystallized semantic cores.
- **Timeline**: productive `MemoryEpisode`s with stage, time, semantic keys and source lineage.
- **Search**: pure filtering across source content/tags/provenance and atom/crystal/episode semantics.
- **Details**: exact source content, stage if projected, confidence, time, provenance, lineage counts, tags and verified local image preview.

## Read-only freshness model

`DurableLifeMemoryRuntime.current()` is not a Flow. F5 therefore combines:
1. the latest existing `DurableLifeMemorySnapshot`, and
2. the current kernel Photon list.

A Photon absent from the snapshot decisions is explicit `NEW` evidence. If `current()` is null, F5 remains a read-only new-evidence browser and says the long-term projection is not yet available.

Productive initial-data bootstrap owns its own `memory.rebuild(...)`. F5 does not add polling, hidden rebuilds or a new memory writer.

## Implemented structure

```text
app/src/main/java/app/lifeos/next/
  LifeOsMemoryViewModel.kt
  ChatMainActivity.kt

app/src/main/java/app/lifeos/next/ui/
  LifeOsRoot.kt
  PlaceholderScreens.kt

app/src/main/java/app/lifeos/next/ui/components/
  PhotonImagePreviewLoader.kt

app/src/main/java/app/lifeos/next/ui/chat/
  ChatImagePreviewLoader.kt

app/src/main/java/app/lifeos/next/ui/memory/
  LifeOsMemoryScreen.kt
  MemoryWorkspaceLists.kt
  MemoryUiModels.kt
  MemoryWorkspaceProjector.kt
  MemoryDetails.kt

app/src/test/java/app/lifeos/next/ui/memory/
  MemoryWorkspaceProjectorTest.kt

app/src/androidTest/java/app/lifeos/next/
  MemoryWorkspaceDeviceTest.kt

.github/scripts/
  android-emulator-recovery.sh
```

## Review A — source/dependency/contract review

Findings:

- Material3 + lifecycle-runtime-compose already provide the required screen primitives; no navigation or UI dependency is added.
- `DurableLifeMemorySnapshot.memory` already contains decisions, atoms, crystals, episodes and `evaluatedAt`; F5 does not introduce a parallel classification engine.
- `MemoryWorkspaceProjector` is pure and deterministic: latest Photon revision per ID, locale-stable search, deterministic sorting and exact stage copy from productive decisions.
- Low-level memory-management, causal-ledger, source-gap, goal/scene/tool/perception-management records are excluded from ordinary source browsing.
- `PhotonImagePreviewLoader` is the single shared image-preview implementation. F4 keeps a thin source-compatible adapter.
- `LifeOsMemoryViewModel` is Activity-scoped through `ViewModelProvider` and observes the existing kernel bootstrap state; it performs no memory writes.
- The old Memory migration placeholder is removed once the real destination is wired.

Amendments after Review A:

- Keep image decode on demand in source details rather than decoding every image in the scrolling memory list.
- Limit visible lineage buttons per compact card; exact source IDs remain available through the model and detail drill-down.
- Use a stable saved tab key (`MemoryWorkspaceTab.name`) so recreation does not depend on ordinal serialization.

## Review B — lifecycle/security/recovery/UX review

Findings:

- Opening, searching and switching tabs only changes UI state.
- Missing source lineage is counted and shown; no substitute content is synthesized.
- New post-snapshot Photons remain `NEW`/stage-null even if they look semantically important.
- Source detail image loading goes only through `LifeOsKernel.loadImageAsset(...)`, then verifies descriptor byte count, SHA-256 and decoded dimensions before admitting the bitmap.
- Cancellation is rethrown from image loading; ordinary failures produce a fail-closed unavailable state.
- The Memory ViewModel clears its bounded preview cache in `onCleared()`.
- Back behavior remains owned by `LifeOsRoot`: leaving Memory returns to Chat as established by F1.
- F5 adds a real Android proof that projects the productive snapshot and then asserts both the runtime snapshot fingerprint and the complete Photon store are unchanged.

Amendments after Review B:

- No UI-triggered `rebuild()` button is introduced.
- No memory-access event is recorded merely for viewing/searching; relevance policy stays a runtime concern.
- The productive device proof is added to the existing cold-restart recovery script so Recovery and Product Gold execute it.

## Verification contract

Unit contracts cover:
1. HOT/WARM exact stages in Jetzt.
2. NEW evidence remains stage-null.
3. COLD/CRYSTALLIZED does not leak into default Jetzt.
4. deterministic atom grouping.
5. crystal semantic core + source lineage.
6. deterministic episode ordering + lineage.
7. search over semantic/source data.
8. management Photons hidden from raw browsing.
9. unresolved source metadata without fabricated content.
10. repeated projection equality.

Android contract:
- `MemoryWorkspaceDeviceTest#productiveSnapshotProjectsWithoutMemoryMutation`
  - waits for real kernel boot,
  - uses `lifeMemoryRuntime.current()` without rebuilding,
  - projects current kernel Photons,
  - verifies exact productive HOT/WARM semantics for projected Jetzt entries,
  - verifies NEW entries remain stage-null,
  - verifies runtime fingerprint unchanged,
  - verifies Photon store unchanged.
- Existing F4 `OfflineImageArtifactDeviceTest` remains unchanged through the shared loader refactor.

## CI / Gold seal

Final F5 source head must have, on one immutable SHA:
- Core Fast PASS
- Android Debug PASS
- Emulator Recovery PASS
- Product Gold PASS

Only after that exact head is 4/4 may PR #219 leave draft and merge using `expected_head_sha`. Then repeat the same four gates on exact merged `main`, confirm Product Gold `candidate_sha = source_head_sha = checkout_sha = <merged-main-sha>`, and capture the debug APK SHA256.
