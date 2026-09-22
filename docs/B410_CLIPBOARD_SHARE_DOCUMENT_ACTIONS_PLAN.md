# B410 — Clipboard / Share / Document Actions — exact implementation plan

Base prepared head: `472c1be3b568186a150c8d1c8e3a7508a40ad923`
Branch: `b410-clipboard-share-document-actions-v1`

## Pre-implementation audit

1. `LocalShareIntentFactory` already owns the narrow private-cache/FileProvider path for text and encrypted LIFEOS images.
2. Cache image materialization already uses owner-gated FILE_WRITE and read-only FileProvider grants. B410 reuses that pattern and does not create a second share provider.
3. B408 owns generic external target resolution/launch. B410 does not create another generic Intent executor.
4. B406 owns persistent shared-file writes and exact `AndroidFileRef` / revision binding. Persistent document export composes B406 rather than bypassing it.
5. B402 secret/session types remain outside the public B410 payload API by construction.
6. B411 owns durable cross-action provenance receipts.

## Capability surface

- `clipboard.read`: bounded text-only foreground read.
- `clipboard.write`: bounded text-only write; JIT `EXTERNAL_APP_HANDOFF`.
- `share.prepare`: typed text/content-URI preparation with no execution authority.
- `share.launch`: exact prepared handoff; JIT `EXTERNAL_APP_HANDOFF`.
- `document.open`: exact read-only content URI + MIME handoff; JIT `EXTERNAL_APP_HANDOFF`.
- `document.export`: persistent bytes are delegated to B406 FILE_WRITE; B410 only models/export-composes the request.

## Core runtime

### NEW — `core/runtime-android/.../ClipboardShareDocumentRuntime.kt`

- `ClipboardText`
- `ShareText`
- `PublicContentUri`
- `SharePayload`
- `PreparedShare`
- `PreparedDocumentHandoff`
- clipboard/share/document host contracts
- exact B405 plan validation
- JIT OwnerPolicyEffectGate for clipboard write/share/document open
- explicit B406 export composition contract

No Android `Intent`, `ClipData`, `Uri` or secret/session object crosses this core boundary.

## Android host

### NEW — `app/.../AndroidClipboardShareDocumentHost.kt`

- ClipboardManager text-only read/write.
- content URI validation; no file://.
- ACTION_SEND / ACTION_VIEW are explicit and use read-only URI permission only.
- exact package binding when supplied.
- no write URI grants.
- no arbitrary Parcelable extras.

### Existing LocalShareIntentFactory

Kept intact. Its private encrypted-image share path remains authoritative for current LIFEOS image sharing; B410 adds a compatible general public-payload boundary around new actions instead of rewriting the proven path.

## Persistent document export

`DocumentExportRequest` contains exact public bytes + a B406 `FileWriteRequest`. The runtime delegates to an injected B406 export adapter. OwnerPolicy FILE_WRITE remains B406's authority boundary; B410 does not repeat or weaken it.

## Tests

- clipboard text bounds and defensive public value.
- prepared share/document has no execution authority.
- file:// URI rejected; content URI only.
- missing/revoked Owner Policy blocks clipboard write/share/document handoff.
- exact package/URI/MIME binding.
- no write URI grant represented by core contracts.
- persistent export delegates to B406 and does not expose a second file-write authority.
- existing LocalShareIntentFactory remains untouched.

## Gates

1. `:core:runtime-android:test`
2. `:app:testDebugUnitTest`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. Product Gold

## Authority invariant

`payload preparation != data exposure != owner grant != target launch != recipient consumption`
