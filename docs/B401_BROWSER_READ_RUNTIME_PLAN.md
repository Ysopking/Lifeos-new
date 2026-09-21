# B401 — Browser Read Runtime — exact implementation plan

Base head: `67fe85c8ac1cf9f7bfb949766a894ca69a8e82d7`
Branch: `b401-browser-read-runtime-v1`
Module: `:core:runtime-web`

## Reuse / non-duplication contract

B401 does not create a browser action engine:
- B391 remains canonical Web resource identity.
- B392 remains bounded read-only acquisition/redirect/receipt orchestration.
- B393 remains typed multi-format ingest.
- B394 remains bounded claim-candidate extraction.
- B395 remains exact citation/provenance graph construction.
- B404+ remains navigation/form/action authority.
- existing Owner Policy remains the real network permission boundary around the host transport.

B401 is only the read composition boundary: B392 → B393 → B394 → B395.

## Prepared production code

NEW `core/runtime-web/src/main/kotlin/app/lifeos/core/runtime/web/BrowserReadRuntime.kt`

- L7–16: content-derived `BrowserReadRequestId`.
- L18–22: `BrowserReadOutcome`: READ / NOT_MODIFIED / ACQUISITION_FAILED.
- L24–71: immutable `BrowserReadRequest`; exact B392 acquisition request + B393 ingest policy + B394 claim policy. Explicit no navigation/form/download/upload/permission/execution authority.
- L73–129: immutable `BrowserReadResult`; exact final B391 resource, B392 receipt, optional B393/B394/B395 products, outcome-specific invariants and no truth/evidence/navigation/form/download/upload/permission/execution authority.
- L131–137: architecture invariant: B401 composes read-only stages only; browser actions stay B404+.
- L138–213: `BrowserReadRuntime.read`:
  - run exact B392 acquisition;
  - ACQUIRED → B393 ingest → B394 claims → B395 citations;
  - NOT_MODIFIED stays explicit and creates no new document/claims/citations;
  - HTTP_ERROR becomes ACQUISITION_FAILED and error body is never promoted;
  - exact redirect-final B391 identity is propagated into B393/B395;
  - no navigation/form submission/download/upload/session mutation.
- L215–235: deterministic result fingerprint across exact request/final-resource/acquisition/document/claim/citation lineage.
- L237–256: dependency-free length-delimited SHA-256 helper.

No new module/dependency is required.

## Prepared tests

NEW `core/runtime-web/src/test/kotlin/app/lifeos/core/runtime/web/BrowserReadRuntimeTest.kt`

- L13–48: HTML read composes B392→B393→B394→B395 and grants no action/epistemic authority.
- L51–73: redirect final resource becomes exact document/citation identity.
- L76–94: 304 remains explicit NOT_MODIFIED and cannot invent a document.
- L97–116: 404 remains ACQUISITION_FAILED and its body is not promoted.
- L119–138: PDF stays B393 opaque binary and creates no fabricated claims/citation edges.
- L141–162: request identity changes with ingest/claim policy.
- L165–182: malformed text fails closed in B393.
- L185–202: identical exact read evidence yields deterministic result identity.
- L205–217: bounded BrowserReadRequest fixture.
- L220–232: sequenced fake B392 transport.

## Pre-implementation verification

Before writing:
1. verify no existing `BrowserReadRuntime`, `BrowserReadRequest`, or `BrowserReadResult` collision;
2. verify B392 exposes ACQUIRED / NOT_MODIFIED / HTTP_ERROR and exact final B391 resource identity;
3. verify B393 only accepts ACQUIRED payloads;
4. verify B394 and B395 accept B393 document/extraction lineage and remain non-authoritative;
5. verify existing browser/Web code does not already provide a shared core read-composition boundary;
6. verify B401 never introduces navigation, form submission, upload, download, browser storage, session-vault, or action contracts;
7. verify real network authorization remains outside `:core:runtime-web` behind existing Owner Policy host boundaries;
8. verify no file is added to the bounded 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-web:test --tests 'app.lifeos.core.runtime.web.BrowserReadRuntimeTest'`
2. `./gradlew :core:runtime-web:test`
3. Core Fast.
4. Clean restack after B400/B399/B398/B397/B396/B395/B394/B393/B392/B391/B380 promotion, then Debug / Recovery / Product Gold before merge.
