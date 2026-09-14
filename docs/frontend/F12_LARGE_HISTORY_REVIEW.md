# F12 Large-History Performance Review

Base reviewed: F11 candidate `1a10a99832922ed3064118dd7250e6dca299abf3`.

## Review A — source and dependency audit

The chat ViewModel currently rebuilds both `ConversationProjector` and `ChatTimelineProjector` for every `bootstrapState` emission even when the Photon id/revision set is unchanged. The memory ViewModel similarly rebuilds latest revisions and normalized source search text for every query edit.

F12 therefore adds only app-layer, in-memory acceleration:
- `StableChatProjection` retains one chat projection keyed by a canonical Photon id/revision vector.
- `MemorySearchIndex` retains latest-revision references and pre-normalizes at most 4096 source search texts.
- `LifeOsChatViewModel` and `LifeOsMemoryViewModel` consume those UI-only helpers.

No core runtime source of truth, encrypted repository, durable ledger, owner policy, readiness gate, or Photon persistence path is replaced.

## Review B — lifecycle, correctness, recovery and containment

Cache invalidation is keyed exclusively by stable Photon id/revision identity. Input ordering cannot create a false invalidation. A changed revision invalidates the chat projection and memory index.

The memory precomputation bound does not truncate search correctness: sources outside the 4096 precomputed window are normalized on demand. The index is process-memory only and is reconstructed from the current authoritative runtime snapshot after cold start.

`StableChatProjection` retains exactly one projection. `MemorySearchIndex` retains the current latest-revision references plus a bounded normalized-text map. Neither helper writes files, databases, preferences, Photons, or runtime state.

## Acceptance

- unchanged chat Photon revisions reuse the exact projection object;
- a changed revision recomputes chat output;
- large stable chat history does not repeatedly reproject on runtime-only updates;
- memory index reuse is revision-set deterministic;
- normalized memory precomputation remains bounded while overflow sources stay searchable;
- existing memory projection semantics and missing-evidence behavior remain unchanged;
- Core Fast, Android Debug, Android Emulator Recovery and LIFEOS Product Gold must pass on one final immutable F12 head before merge.
