# B403 — bounded Web download/upload runtime — exact implementation plan

Base head: 74696409f13e12f7e3a527675ed96ae6fed3fc49
Branch: b403-web-transfer-runtime-v1
Promotion gate restack: exact main 74696409f13e12f7e3a527675ed96ae6fed3fc49
Module: :core:runtime-web

## Pre-implementation audit

Verified before writing:
1. No existing B403 branch or pull request exists.
2. B392 WebAcquisitionRuntime already owns bounded GET-like acquisition, redirect canonicalization, byte/media limits and immutable receipts.
3. EncryptedBinaryAssetStore already owns productive binary FILE_WRITE persistence behind OwnerPolicyEffectGate; B403 must not create a second asset store or bypass that gate.
4. OwnerPolicyEffectGate already treats NETWORK_ACCESS and FILE_WRITE as productive effects; B403 therefore grants neither.
5. B402 WebSessionVault stores origin-bound secret material but deliberately does not attach it to requests; B403 keeps upload transport credential/session-free.
6. B401 BrowserReadRuntime explicitly reserves upload/download/browser actions for later blocks, so B403 adds transfer-specific contracts without changing B401 read semantics.
7. Upload redirects are not followed. Replaying POST/PUT payloads after redirects is deliberately deferred to a later explicit browser-action policy block.

## Production code

NEW core/runtime-web/src/main/kotlin/app/lifeos/core/runtime/web/WebTransferRuntime.kt

Download:
- WebDownloadRequestId / WebDownloadRequest: exact B392 acquisition identity wrapper.
- WebDownloadOutcome: DOWNLOADED / NOT_MODIFIED / ACQUISITION_FAILED.
- WebDownloadRuntime: delegates exactly once to B392 and returns bounded bytes + the exact acquisition receipt.
- No file persistence, FILE_WRITE authority, network permission or execution authority.

Upload:
- WebUploadOperationKey: caller-scoped operation identity.
- WebUploadRequestId: derived from operation shape, target, method, media type, payload size and response budget; it is not a stable hash of private payload bytes.
- WebUploadPayload: defensive-copy bounded opaque payload with redacted toString and internal SHA-256 integrity.
- WebUploadMethod: POST / PUT only.
- WebUploadRequest: exact HTTPS target, content type, bounded payload, bounded response budget.
- WebUploadTransport: separate host-effect interface; does not modify B392 WebAcquisitionTransport.
- WebUploadRuntime: one exact-target send, no redirects, no session/cookie/auth injection.
- WebUploadReceipt: immutable request/payload/response integrity and HTTP outcome evidence.
- No truth, permission or execution authority.

## Tests

NEW core/runtime-web/src/test/kotlin/app/lifeos/core/runtime/web/WebTransferRuntimeTest.kt

- download delegates to B392 and grants no file-write authority.
- 304 and HTTP error remain explicit without payload.
- upload payload is defensively copied and redacted.
- public request identity does not hash private payload content, while request fingerprint binds exact payload digest.
- successful upload emits exact receipt and bounded response.
- non-2xx remains explicit HTTP_ERROR.
- oversized response fails closed.
- 3xx is not followed and no session material is attached.

## Gate

1. ./gradlew :core:runtime-web:test --tests 'app.lifeos.core.runtime.web.WebTransferRuntimeTest'
2. ./gradlew :core:runtime-web:test
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold

## Non-goals

B403 does not persist downloads, attach B402 credentials/cookies, follow upload redirects, submit browser forms, navigate a browser, grant Owner Policy permissions, or mutate external state without the host transport's separate JIT policy gate.
