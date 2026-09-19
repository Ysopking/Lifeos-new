# LIFEOS remaining stack: M215b → M216e

This stack is intentionally linear. Every block must be implemented on its named branch and must keep the previous block as its exact ancestor. `main` is not modified by this stack.

Verified predecessor:
- M214 exact-head gold base: `161f862b33fb2b5a0e27d261b935c067a5743437`
- M215a shared segmented-ledger core: `805e722f0e3ddf39e9081e4d5cc0136cc3e6c087`

## M215b — migrate durable ledgers to the shared segmented core
Branch: `optimization/m215b-ledger-adapters`

Scope:
- Move ToolWorkshop job, Self-Healing, DecisionTrace and other compatible segmented append-only stores onto `EncryptedSegmentedLedger` / `EncryptedCasHeadStore`.
- Preserve each public repository interface, exact event/state codec and on-disk legacy migration contract.
- Preserve global-vs-per-key revision semantics explicitly through `SegmentRevisionPolicy`.
- Bind new ciphertext to exact physical path via AAD; legacy unbound ciphertext may be upgraded only after payload/path validation.
- Keep unreadable/corrupt history fail-closed and never bless wrong-path ciphertext during migration.

Definition of done:
- No duplicated revision-contiguity, path-hash, AtomicFile head-recovery, or segment-AAD logic remains in migrated repositories.
- Existing path-binding device tests remain valid.
- Add JVM policy tests for global and per-key revision chains and migration collision behavior.
- Core Fast + Android Debug exact-head green.

## M215c — split runtime registries from durable ownership
Branch: `optimization/m215c-runtime-registry-split`

Scope:
- Process registries become non-owning indirection/view ports only.
- Durable ledgers/repositories remain composition-owned and injected into coordinators.
- Remove registry-side durable state duplication and static lifecycle authority.
- Add explicit install/read/clear semantics where tests need process reset.
- Fail closed when an authoritative runtime is absent; do not synthesize durable state from registry memory.

Targets include current runtime bridges for LifeMemory, CognitiveSnapshot, SelfObservation authority, WorldEquation post-activation safety, escalation, ToolWorkshop boot, DeepSearch and app-local runtime registries.

Definition of done:
- A registry cannot become a second persistence source of truth.
- Process restart reconstructs runtime from durable repositories and composition, not registry residue.
- Unit tests prove clear/reinstall/restart parity and absence-of-authority behavior.
- Core Fast + Android Debug exact-head green.

## M215d — recovery, migration and ownership hardening
Branch: `optimization/m215d-recovery-hardening`

Scope:
- Cross-ledger restart/recovery tests for interrupted AtomicFile writes, stale heads, missing heads, wrong paths, corrupt ciphertext, duplicate/gapped revisions and legacy migration.
- Verify ledger/runtime split under cold process recreation.
- Verify no append occurs after unreadable history.
- Verify CAS heads cannot advance past durable tails.
- Add exact-head branch gate coverage for all M215 stack branches.

Definition of done:
- M215 has one durable authority per state domain and one non-owning runtime access path.
- Core Fast + Android Debug exact-head green on final M215 head.
- No Product Gold is run yet.

## M216a — deterministic scale corpus and fixtures
Branch: `optimization/m216a-scale-fixtures`

Scope:
- Add deterministic local-only scale fixture generators for Photon revisions, source metadata, relationship graph, life graph, decision traces, ToolWorkshop/Self-Healing ledgers and UI paging.
- Fixtures must be reproducible from seed/config, bounded on CI, and have larger opt-in tiers for manual profiling.
- No network dependency and no production-data dependency.

Required bounded CI tiers:
- 10k latest Photons with revision history.
- 25k source relationships / graph edges.
- 10k decision-trace nodes across many traces.
- 10k append-only ledger events across multiple keys.
- UI paging datasets beyond the 20k presentation cap.

Definition of done:
- Generated fixtures have deterministic fingerprints/counts.
- Test setup/cleanup does not leak files between runs.
- Core Fast + Android Debug exact-head green.

## M216b — scale/runtime budgets
Branch: `optimization/m216b-scale-runtime`

Scope:
- Exercise incremental boot manifests/index deltas, bounded post-boot retention, exact cold lookup, paged UI, relationship/source indexes and migrated ledgers at scale.
- Record machine-readable evidence for counts, retained working-set counts, page sizes, query bounds and boot fallback mode.
- Assert architectural bounds rather than unstable wall-clock microbenchmarks.
- Add explicit regression guards for accidental full-list retention after boot.

Definition of done:
- Durable count may scale while retained bootstrap/UI working sets remain bounded.
- Query/page hard limits remain enforced.
- Incremental boot uses delta path when generation chain is valid and falls back safely after compaction/mismatch.
- Core Fast + Android Debug exact-head green.

## M216c — scale recovery on emulator
Branch: `optimization/m216c-scale-recovery`

Scope:
- Extend emulator recovery with bounded scale fixtures.
- Cold restart after seeded large local state.
- Validate exact heads, index recovery, ledger tails, no duplicate external effects, source/life graph continuity, field cutover state and UI retrieval of cold items.
- Seal scale-recovery evidence separately from Product Gold.

Definition of done:
- Exact-head Core Fast + Android Debug green.
- Android Emulator Recovery green on exact M216c head.
- No Product Gold before this block is green.

## M216d — Product Gold exact-head scale gate
Branch: `optimization/m216d-product-gold`

Scope:
- Harden `product-gold.yml` to check out and bind to the exact source/candidate head consistently with M214.
- Add scale evidence to the Product Gold pre-emulator matrix without weakening existing security, coverage, language, Level7, self-observation, recovery or artifact gates.
- Preserve pinned external actions and Gradle wrapper integrity.
- Seal APK SHA-256, exact checkout SHA, source head SHA, scale evidence and emulator evidence in one immutable artifact set.

Definition of done:
- Product Gold is run for the first time in this remaining stack.
- Full Product Gold including cold-restart emulator succeeds on the exact M216d head.
- Debug APK and evidence artifacts are uploaded.

## M216e — final gold seal
Branch: `optimization/m216e-final-gold-seal`

Scope:
- Final repository/branch ancestry audit from M205c through M216.
- Verify zero drift from the intended stack, all required exact-head gates and artifact hashes.
- Remove temporary diagnostic-only CI code if any exists, without weakening gates.
- Produce one final machine-readable `lifeos-gold-manifest.txt` containing final SHA, parent milestone SHA, gate names, artifact SHA-256 and scale fixture fingerprints.

Definition of done:
- Final exact head is a strict descendant of M214 and every prepared M215/M216 block.
- Core Fast, Android Debug, Emulator Recovery and Product Gold are green for the final candidate as applicable.
- `main` remains untouched until an explicit later merge decision.
