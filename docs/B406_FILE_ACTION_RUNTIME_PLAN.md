# B406 — File Action Runtime — exact implementation plan

Base prepared head: `e1a4594893eb49e59a7bd1256357ab2c138d3dc0`
Branch: `b406-file-action-runtime-v1`

Pre-promotion validation trigger: exact clean B405 head
Stacked validation trigger: exact B405 base.
Exact-main promotion trigger: 539ec5c50cb0b7c26d1995d4ab2360474a3701ec.

## Implementation status

Prepared plan has now been promoted to code on this branch:
- NEW `core/runtime-android/src/main/kotlin/app/lifeos/core/runtime/android/FileActionRuntime.kt`
- NEW `core/runtime-android/src/test/kotlin/app/lifeos/core/runtime/android/FileActionRuntimeTest.kt`
- NEW `app/src/main/java/app/lifeos/next/AndroidFileActionHost.kt`
- NEW `app/src/test/java/app/lifeos/next/AndroidFileActionHostTest.kt`
- MOD `app/src/main/java/app/lifeos/next/AndroidStorageInventoryStore.kt` for bounded indexed search only
- MOD `app/build.gradle.kts` for the existing `:core:runtime-android` module

The implementation keeps B411 receipt-graph ownership separate and does not add delete/trash authority.

## Pre-implementation audit

1. B405 introduces `:core:runtime-android` as the typed capability-routing layer. B406 must extend that module rather than create a second Android control plane.
2. Existing `AndroidStorageIntelligenceRuntime` already owns resumable shared-storage inventory/fingerprinting. B406 must reuse its indexed `volumeId + relativePath` identity instead of launching a second full-filesystem scanner.
3. Existing `SharedStorageRoots.discover(context)` is the authoritative host mapping from opaque volume identity to owner-readable shared-storage roots.
4. Existing `AndroidStorageLiveSourceConnector` already projects storage changes into live cognition. Productive B406 mutations must therefore update/refresh the existing storage journal/runtime rather than introducing a parallel observation channel.
5. Existing file parsers already provide bounded local reads for supported content. B406 must not replace them.
6. Existing `OwnerPolicyEffectGate` and `OwnerEffectType.FILE_WRITE` are the productive JIT authorization boundary for any write/copy/move/organize effect.
7. Existing broad-file permission profile remains the Android permission boundary. B406 never grants or bypasses Android storage permission.
8. B411 owns the global External Action Receipt Graph. B406 may return exact local operation results/pre-post revisions, but must not introduce a competing global receipt graph.

## Target capability surface

- `file.search`: query the existing storage inventory only; no rescan side effect.
- `file.read`: bounded exact-target byte/text read with revision binding.
- `file.write`: bounded atomic/replace-safe write under JIT `FILE_WRITE` policy.
- `file.copy`: exact source revision -> exact destination under JIT `FILE_WRITE`.
- `file.move`: exact source revision -> exact destination under JIT `FILE_WRITE`.
- `file.organize`: typed move using Storage Intelligence organization candidates; no implicit deletion.

Deletion/trash remains outside B406 unless an already-reviewed reversible cleanup path is explicitly reused. No generic recursive-delete API is added.

## Exact identity and path safety

Introduce `AndroidFileRef(volumeId, relativePath)`:
- nonblank opaque volume id,
- normalized forward-slash relative path,
- no absolute paths,
- no `.` or `..` segments,
- no NUL/control separators,
- canonical host resolution must remain inside the selected discovered root,
- symlink targets fail closed.

Introduce `AndroidFileRevision`:
- file ref,
- size,
- modified time,
- optional SHA-256 when already known,
- deterministic fingerprint.

Every mutating operation against an existing source/destination must bind the expected revision. A changed file is rejected rather than silently overwritten.

## Runtime structure

### NEW — `core/runtime-android/.../FileActionRuntime.kt`
Pure orchestration/contracts:
- `FileActionKind`
- `AndroidFileRef`
- `AndroidFileRevision`
- bounded `FileSearchQuery`
- `FileReadRequest`
- `FileWriteRequest`
- `FileCopyRequest`
- `FileMoveRequest`
- `FileActionHost` interface
- `FileActionResult`
- `FileActionRuntime`

The runtime:
- requires a B405 `AndroidCapabilityDispatchPlan`,
- requires capability ids to match the requested operation,
- refuses any productive mutation if B405 metadata does not require `OwnerEffectType.FILE_WRITE`,
- wraps the exact host mutation in `OwnerPolicyEffectGate.expose()`,
- rechecks policy immediately before host exposure,
- never derives permission from Owner Policy and never derives Owner Policy from Android permission.

### NEW/MOD — app host adapter
Add an Android host adapter in `:app` that:
- maps `volumeId` only through `SharedStorageRoots.discover(context)`,
- resolves canonical paths beneath the chosen root,
- rejects symlinks/path traversal,
- performs bounded reads,
- performs writes using sibling temporary files + fsync/close + replace/move where supported,
- copies to a temporary destination then publishes,
- verifies source revision immediately before copy/move,
- never overwrites a changed destination,
- triggers the existing Storage Intelligence refresh/journal path after successful mutation.

### MOD — storage inventory
Expose only the minimal indexed lookup/search operations required by B406:
- exact `volumeId + relativePath` lookup,
- bounded canonical search over current inventory,
- no second inventory database.

### MOD — app dependency
`:app` gains direct dependency on `:core:runtime-android` for the host adapter.

## Bounds

- search results: max 512
- read bytes: caller-bound, hard max 8 MiB
- write bytes: hard max 8 MiB in B406
- copy/move: exact single-file only; no recursive directory tree operation
- query string/path lengths bounded
- no unrestricted absolute-path API

Larger artifact/file streaming can be added in a later dedicated bounded streaming block rather than weakening B406.

## Tests

### core/runtime-android
- path normalization rejects traversal/absolute paths.
- exact provider/capability binding is required.
- read remains bounded.
- mutation without `FILE_WRITE` metadata fails closed.
- mutation without live Owner Policy grant never calls host.
- policy change after preparation fails closed.
- exact source revision mismatch fails closed.
- deterministic request/result identity.

### app
- root resolution cannot escape discovered root.
- symlink escape rejected.
- bounded read.
- write publishes only complete content.
- copy preserves source and binds revision.
- move removes source only after destination publish.
- destination revision conflict fails closed.
- successful action causes existing storage observation refresh.

## Gates

1. `:core:runtime-android:test`
2. `:app:testDebugUnitTest`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold

## Authority invariant

`file observation != file permission != owner authorization != file mutation != successful outcome`

B406 adds productive file action capability, but every write-like effect remains behind both Android access state and the existing JIT Owner Policy boundary.
