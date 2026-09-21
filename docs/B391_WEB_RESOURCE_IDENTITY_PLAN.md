# B391 — WebResourceIdentity — exact implementation plan

Base head: `09a95c321852cca790e4686bd0207fa7c71111f4`
Branch: `b391-web-resource-identity-v1`
Module: `:core:runtime-contracts`

## Reuse / non-duplication contract

B391 introduces shared Web resource identity only; it does not replace DeepSearch source-provider identity or app-level network security.

Existing infrastructure remains authoritative:
- `DeepSearchSourceDescriptor` = provider/source identity, permission state and reliability.
- `AndroidWebDeepSearchSource` / `BoundedHttpsDocumentTransport` = actual HTTPS acquisition, DNS/public-address checks, redirects, byte/content-type limits and Owner Policy.
- `DeepSearchSourceSnapshot` = exact Photon evidence snapshot.
- B391 = canonical identity of one Web resource/origin shared by later observation/research/action runtimes.

Placement in `:core:runtime-contracts` avoids coupling identity to DeepSearch while keeping it reusable from `:core:runtime-deepsearch`, `:core:runtime` and app code. The module stays dependency-free and therefore cannot create a module cycle.

## Prepared production code

NEW `core/runtime-contracts/src/main/kotlin/app/lifeos/core/runtime/web/WebResourceIdentity.kt`

- L9–22: `WebResourceId`; strict content-derived 64-hex identity.
- L25–38: `WebOriginId`; strict content-derived origin identity.
- L40–68: `WebOriginIdentity`; canonical HTTPS scheme/host/non-default-port identity; no network or permission authority.
- L70–112: `WebResourceIdentity`; canonical URL/origin/path/query identity; fragment excluded; no content/trust/network/permission authority.
- L114–121: architecture invariant: pure identity canonicalization, no DNS/network/permissions/trust/redirect following; query order intentionally preserved.
- L122–287: `WebResourceIdentityCanonicalizer`:
  - HTTPS-only absolute URI;
  - reject user-info;
  - canonical DNS host via IDN ASCII + lower-case + root-dot removal;
  - preserve bracketed IPv6 literals without DNS resolution;
  - remove default port 443, preserve non-default ports;
  - empty path → `/`;
  - UTF-8 URI → ASCII form;
  - normalize dot segments;
  - decode only RFC-unreserved percent encodings and uppercase remaining escape hex;
  - strip fragment from identity;
  - preserve query parameter ordering/duplicates;
  - deterministic IDs.
- L289–308: canonical origin/URL builders.
- L310–331: dependency-free length-delimited SHA-256 fingerprint helper so `:core:runtime-contracts` gains no dependency on `:core:field`.
- L333: HTTPS constant.

No existing Web transport, DeepSearch, Owner Policy, or app file is modified in B391.

## Prepared tests

NEW `core/runtime-contracts/src/test/kotlin/app/lifeos/core/runtime/web/WebResourceIdentityTest.kt`

- L9–28: scheme/host/default-port/fragment/dot-segment/unreserved encoding variants collapse to one identity.
- L31–41: Unicode host/path become deterministic ASCII/punycode identity.
- L44–50: empty path and DNS root-dot canonicalization.
- L53–64: query order and duplicate parameters are preserved and identity-significant.
- L67–75: non-default ports remain origin/resource-significant.
- L78–84: different resources on one origin share origin identity only.
- L87–93: fragments never participate in resource identity.
- L96–104: reserved percent encodings remain encoded; unreserved encodings normalize.
- L107–120: HTTP, user-info, missing host and invalid ports fail closed.
- L123–133: loopback/private-looking hosts may be represented as identities but gain no network/trust/permission authority; actual network admission remains with acquisition policy.

## Pre-implementation verification

Before writing:
1. confirm no existing `WebResourceIdentity`, `WebOriginIdentity`, `WebResourceId` or equivalent canonical shared resource type exists;
2. confirm existing URL canonicalization is app-local inside Web DeepSearch and does not provide a reusable identity contract;
3. confirm `:core:runtime-contracts` is dependency-free and is already depended on by `:core:runtime-deepsearch` and `:core:runtime`;
4. confirm B391 uses only JDK `URI`, `IDN`, UTF-8 and SHA-256, adding no module/library dependency;
5. confirm no DNS/private-network checks are moved out of `BoundedHttpsDocumentTransport`;
6. confirm no files are added to the bounded 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-contracts:test --tests 'app.lifeos.core.runtime.web.WebResourceIdentityTest'`
2. `./gradlew :core:runtime-contracts:test`
3. Core Fast after B391 is eventually restacked onto the accepted B380 main head.
