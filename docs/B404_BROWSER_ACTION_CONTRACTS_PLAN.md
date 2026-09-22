# B404 — BrowserActionContracts — exact implementation plan

Base head: be17efee10fb57e65145e8779c4dec14ed68dfd2
Branch: b404-browser-action-contracts-v1
Promotion gate restack: exact main be17efee10fb57e65145e8779c4dec14ed68dfd2
Module: :core:runtime-web

## Pre-implementation audit

Verified before writing:
1. No existing B404 branch, pull request, BrowserActionContracts.kt or BrowserActionContractsTest.kt collision exists.
2. B401 remains read-only composition and explicitly reserves browser actions for B404+.
3. B402 stores encrypted origin-bound session revisions but does not attach credentials to network requests.
4. B403 owns bounded download/upload transport contracts and grants no execution authority.
5. Existing OwnerPolicyEffectGate remains the only productive effect authorization boundary for NETWORK_ACCESS / FILE_WRITE.
6. No generic JavaScript/eval action surface exists or is introduced.

## Production code

NEW core/runtime-web/src/main/kotlin/app/lifeos/core/runtime/web/BrowserActionContracts.kt

- BrowserActionOperationKey: explicit caller operation identity.
- BrowserActionPlanId: safe external plan identity.
- BrowserElementSelector: bounded selector text; no CR/LF/NUL.
- BrowserSensitiveText: bounded UTF-8 secret input with defensive storage, SHA-256 integrity and redacted toString.
- BrowserActionKind: NAVIGATE / CLICK / INPUT_TEXT / SUBMIT / DOWNLOAD / UPLOAD.
- BrowserActionStep:
  - strict shape validation per action kind,
  - secret-safe identityFingerprint excluding sensitive text digest,
  - exact internal fingerprint binding sensitive text and B403 transfer fingerprints,
  - no execution/network/permission authority.
- BrowserActionPlan:
  - max 64 steps,
  - contiguous canonical ordinals,
  - same-origin enforcement for navigation/download/upload,
  - optional exact B402 session revision reference only,
  - external plan identity does not hash sensitive form text,
  - internal fingerprint binds exact step fingerprints,
  - no network/form/file-write/execution/permission authority.
- BrowserActionContracts factory helpers for the six typed actions.

## Tests

NEW core/runtime-web/src/test/kotlin/app/lifeos/core/runtime/web/BrowserActionContractsTest.kt

- plan id remains stable when sensitive text changes, while exact fingerprint changes.
- sensitive text rendering is redacted.
- step ordinals must be contiguous/canonical.
- cross-origin navigation/download/upload fail closed.
- valid same-origin plan can reference exact B402 session revision and B403 transfer requests without acquiring authority.
- malformed action shapes fail closed.

## Gate

1. ./gradlew :core:runtime-web:test --tests 'app.lifeos.core.runtime.web.BrowserActionContractsTest'
2. ./gradlew :core:runtime-web:test
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold

## Non-goals

B404 does not execute browser actions, attach cookies/Authorization headers, run JavaScript, grant Owner Policy access, follow upload redirects, persist downloads, or create a browser automation engine. It is a typed declarative contract layer only.
