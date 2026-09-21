# B392 — WebAcquisitionRuntime — exact implementation plan

Base head: `96698ffca21adcfd17b00eed312b3f379ff2b8d6`
Branch: `b392-web-acquisition-runtime-v1`
New module: `:core:runtime-web`

## Reuse / non-duplication contract

B392 does not create another browser, research planner, or Owner Policy system.

Authoritative existing pieces remain:
- B391 `WebResourceIdentity` / `WebOriginIdentity` for canonical HTTPS identity.
- `OwnerPolicyEffectGate` for live owner authorization immediately before real network exposure.
- app `BoundedHttpsDocumentTransport` for the existing concrete HTTPS/DNS/private-address safety checks until it is migrated behind the shared runtime.
- `DeepSearchPlannerV2` / `DeepSearchSource` for research orchestration.
- B392 = bounded read-only acquisition orchestration and immutable acquisition receipt only.

The new module depends only on `:core:runtime-contracts`. It cannot grant permission, trust content, decide truth, mutate world state, or perform owner-policy evaluation itself.

## Module wiring

NEW `core/runtime-web/build.gradle.kts`
- Kotlin/JVM, JDK 17.
- `implementation(project(":core:runtime-contracts"))`.
- `testImplementation(kotlin("test"))`.
- `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")`.
- JUnit platform.

MODIFY `settings.gradle.kts`
- current L14–19: insert `":core:runtime-web",` after `":core:runtime-contracts",`.

MODIFY `.github/architecture-budget.json`
- current L42–48: add `"core/runtime-web/build.gradle.kts": [":core:runtime-contracts"]`.
- no change to the 505-file `core/runtime` monolith budget.

MODIFY `.github/scripts/ci-core-fast.sh`
- current L64–68: after runtime-contracts tests insert:
  - `step "gate 03a2: runtime-web unit tests"`
  - `./gradlew :core:runtime-web:test --stacktrace`.

## Prepared production code

NEW `core/runtime-web/src/main/kotlin/app/lifeos/core/runtime/web/WebAcquisitionRuntime.kt`

- L8–16: content-derived `WebAcquisitionRequestId`.
- L19–23: acquisition outcomes: ACQUIRED / NOT_MODIFIED / HTTP_ERROR.
- L25–96: immutable `WebAcquisitionRequest`; exact B391 resource + byte budget + redirect budget + canonical accepted-media set; explicit `executionAuthority=false` and `permissionAuthority=false`.
- L98–108: transport request carrying exact acquisition id/resource/budgets.
- L110–125: one-hop transport response model: status, content-type, redirect location, validators, bytes.
- L127–129: suspend `WebAcquisitionTransport` host boundary.
- L131–150: immutable redirect lineage with exact B391 from/to identities and response fingerprint.
- L152–178: defensive immutable `WebAcquisitionPayload` with copied bytes and SHA-256 identity.
- L180–237: immutable `WebAcquisitionReceipt`; exact request/resource/redirect/status/content metadata with explicit no trust/truth/mutation/execution authority.
- L239–256: `WebAcquisitionResult` invariants tying payload to receipt.
- L258–265: architectural invariant: B392 is bounded read-only orchestration; host transport remains responsible for network admission and existing Owner Policy exposure.
- L266–411: `WebAcquisitionRuntime.acquire`:
  - sends exact B391 identity to transport;
  - enforces max bytes even if transport violates the request;
  - canonicalizes every redirect through B391;
  - rejects HTTPS downgrade automatically via B391;
  - rejects redirect loops and redirect-budget overruns;
  - normalizes/validates Content-Type;
  - exposes payload only for 2xx ACQUIRED results;
  - keeps 304 payload-free;
  - records ordinary non-redirect HTTP errors without promoting their body into acquired content;
  - emits deterministic immutable receipt.
- L413–451: accepted media canonicalization and wildcard matching.
- L453–463: deterministic transport-response fingerprint.
- L465–494: deterministic receipt fingerprint.
- L496–516: dependency-free length-delimited SHA-256 helper.
- L518–522: raw payload SHA-256.
- L524–526: hard acquisition byte/redirect bounds and redirect status set.

## Prepared tests

NEW `core/runtime-web/src/test/kotlin/app/lifeos/core/runtime/web/WebAcquisitionRuntimeTest.kt`

- L13–49: direct acquisition binds exact B391 resource, body digest and immutable receipt while granting no authority.
- L52–81: relative redirect canonicalized through B391, fragment removed, chain order preserved.
- L84–101: redirect downgrade to HTTP fails before second network fetch.
- L104–125: redirect loop fails closed.
- L128–151: redirect budget enforced exactly.
- L154–171: oversized body rejected even when host transport ignored requested byte limit.
- L174–202: unaccepted successful media type rejected; explicit wildcard may admit it.
- L205–223: 304 represented without payload while preserving ETag/Last-Modified.
- L226–244: 404 remains observable HTTP_ERROR and its body is not promoted to acquired payload.
- L247–271: request identity includes resource, budgets and canonical media set.
- L274–293: identical exact transport evidence produces deterministic receipt/payload identity.
- L296–310: recording fake transport.

## Pre-implementation verification

Before writing production code:
1. no existing `WebAcquisitionRuntime`, `WebAcquisitionRequest`, or shared acquisition-receipt collision exists;
2. B391 shared Web identity exists only in `:core:runtime-contracts`;
3. existing concrete Web acquisition is app-local and DeepSearch-specific;
4. existing Owner Policy JIT gate remains in `:core:runtime`/app and will not be duplicated;
5. existing DNS/private-address safety remains in the concrete HTTPS host transport; B392 does not weaken or bypass it;
6. `:core:runtime-contracts` is already dependency-free and safe as the only project dependency of `:core:runtime-web`;
7. the new module introduces no dependency cycle and adds zero files to the bounded `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-web:test --tests 'app.lifeos.core.runtime.web.WebAcquisitionRuntimeTest'`
2. `./gradlew :core:runtime-web:test`
3. Core Fast.
4. After B380/B391 promotion, clean restack on accepted main and rerun Debug / Recovery / Product Gold before merge.
