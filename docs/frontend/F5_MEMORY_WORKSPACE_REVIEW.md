# F5 — Memory Workspace review and execution contract

Base SHA: `fb8a7f453aeb81d1f023aefc8f4ea299a899e62f`

## Goal

Replace the placeholder `Gedächtnis` tab with a read-only workspace backed by LIFEOS' existing durable life-memory projection. The screen must expose the system's real memory stages, semantic atoms/crystals and episodes without introducing a second memory policy or hidden write authority.

## Non-negotiable boundaries

1. `DurableLifeMemoryRuntime` remains the authority for long-term memory semantics.
2. The UI must not call `rebuild()` or `recordAccess()` merely because the user opens or refreshes the screen.
3. Source Photons remain authoritative. Missing source evidence fails closed in details.
4. The UI must not activate tools, alter goals, mutate owner policy, rewrite Photons or trigger autonomous execution.
5. New Photons that arrived after the last productive memory snapshot may be shown as explicit `NEW` evidence, but must not be falsely assigned a HOT/WARM/COLD/CRYSTALLIZED stage.
6. Image previews must use the same encrypted-vault + descriptor/hash/dimension verification path already proven by F4. No duplicate unsafe decoder path.
7. Existing chat, voice and F4 image behavior must remain unchanged.
8. Old `MainActivity` remains until Memory + Goals migration parity is proven.

## Product model

### Workspace tabs

- **Jetzt**
  - HOT and WARM projected source memories
  - plus explicit `Neu` evidence not present in the current memory projection
  - newest/relevant first, never silently inventing a memory stage

- **Themen**
  - semantic `MemoryAtomKind` groups from the productive projection
  - PERSON, RELATIONSHIP, EVENT, STATEMENT, OBLIGATION, AMOUNT, DEADLINE, PLACE, DECISION, OUTCOME, GOAL, FACT
  - crystals shown as compact semantic cores with source counts

- **Timeline**
  - productive `MemoryEpisode`s ordered chronologically
  - stage, time window, semantic keys and source-count summary
  - drill-down resolves only existing source Photons

### Search

Search is pure/read-only and matches:
- atom/crystal content
- episode semantic keys
- source Photon content
- source Photon tags
- source/provenance actor

No query is persisted as memory merely by searching.

### Details

For a selected source-backed item show only data that exists:
- content/semantic core
- memory stage when projected
- confidence
- created/observed time
- source/actor
- source Photon IDs
- relations/parent lineage count
- relevant tags
- image preview when source is a verified image-reference Photon

If a source ID cannot be resolved, show an unavailable-source state; never synthesize text.

## Read-only freshness model

`DurableLifeMemoryRuntime.current()` is not a Flow and the UI must not invoke productive rebuilds.

The screen therefore combines:
1. the latest existing `DurableLifeMemorySnapshot`, and
2. the latest kernel Photon list.

A Photon absent from the snapshot's memory decisions is treated as **new/unprojected evidence**. It may appear in `Jetzt` with an explicit label but is not assigned a lifecycle stage.

If `current()` is null, the screen remains usable as a `Neu` evidence browser and clearly states that no durable memory projection is currently available.

## Technical structure

```text
app/src/main/java/app/lifeos/next/
  LifeOsMemoryViewModel.kt

app/src/main/java/app/lifeos/next/ui/components/
  PhotonImagePreviewLoader.kt

app/src/main/java/app/lifeos/next/ui/memory/
  MemoryUiModels.kt
  MemoryWorkspaceProjector.kt
  MemoryScreen.kt
  MemoryDetails.kt

app/src/test/java/app/lifeos/next/ui/memory/
  MemoryWorkspaceProjectorTest.kt
```

F4 `ChatImagePreviewLoader` will be generalized to the shared `PhotonImagePreviewLoader`; chat keeps a thin type/use-site adaptation only if needed for source compatibility. The same loader contract remains: IMAGE_REFERENCE_MIME, descriptor decode, encrypted kernel load, byte-count check, SHA-256 check, PNG decode, dimension check, bounded LRU cache, fail closed.

## Projector contract

Inputs:
- `DurableLifeMemorySnapshot?`
- current `List<Photon>`
- query text

Outputs are immutable UI models and must be deterministic for the same inputs.

Rules:
- latest Photon revision per ID only
- hide life-memory management Photons from raw source browsing
- hide low-level goal/scene/tool/perception-management records from normal `Neu` cards unless they are the only authoritative evidence backing a projected item
- stage comes only from `LongTermMemoryProjection.stageOf()` / decisions
- atoms/crystals/episodes are copied from the existing projection, never reclassified
- all lists use deterministic tie-breaking by timestamp then stable ID
- query filtering is locale-stable (`lowercase(Locale.ROOT)`)

## Tests before PR

Unit:
1. HOT/WARM sources appear in Jetzt with exact projected stage.
2. new source absent from decisions appears as NEW/unprojected, never HOT/WARM by inference.
3. COLD/CRYSTALLIZED source is excluded from default Jetzt but remains reachable through topic/timeline/source detail.
4. atom kinds group deterministically.
5. crystals retain semantic core and source IDs.
6. episodes sort deterministically and retain source IDs/semantic keys.
7. search matches atom, crystal, episode key, source content and tags.
8. life-memory management Photons never appear as raw user memory cards.
9. missing source ID produces unresolved-source metadata without fabricated content.
10. repeated projection produces byte-for-byte-equivalent UI models.

Android/productive:
- retain F4 IMAGE E2E unchanged through shared loader refactor;
- add a bounded memory workspace device proof only if it can validate the existing productive snapshot without forcing a UI-originated rebuild.

## CI / Gold

Final F5 PR head must have on one immutable source SHA:
- Core Fast PASS
- Android Debug PASS
- Emulator Recovery PASS
- Product Gold PASS

Merge only with `expected_head_sha` equal to that exact source head, then repeat all four on exact merged `main` SHA and seal Product Gold candidate/source/checkout plus APK SHA256.
