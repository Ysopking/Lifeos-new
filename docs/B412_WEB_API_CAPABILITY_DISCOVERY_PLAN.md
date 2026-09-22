# B412 — Web/API Capability Discovery — exact implementation plan

Preparation base: B390 full-stack validation head `e41b49a7bbaa5a2db0a58515fb650659993070d7`
Branch: `b412-web-api-capability-discovery-v1`

## Existing architecture reused

- B391 `WebResourceIdentity` owns canonical HTTPS identity.
- B392 `WebAcquisitionRuntime` owns bounded acquisition and immutable receipts.
- B399/B400 own recursive research and information-gain policy.
- `CapabilityGapPhoton` already owns durable non-activating capability-gap evidence.
- `GenesisCoordinator` already chooses the smallest safe solution and never activates one.
- ToolWorkshop and BuildStudio already own generation/build/test/security/verification.
- Generated candidates and novel canaries are explicitly non-activating until existing promotion authority permits activation.

B412 therefore adds **discovery evidence only**. It does not add a network stack, connector executor, trust score, ToolCandidate translator, or activation path.

## New runtime-web contract

`WebApiCapabilityDiscoveryEngine` consumes:
- an exact `CapabilityRequirement`;
- bounded public Web text already acquired through an existing web authority path;
- exact B392 acquisition-receipt and payload fingerprints;
- exact B391 source identity.

It discovers bounded candidates classified as:
- OPENAPI_DOCUMENT
- SWAGGER_DOCUMENT
- GRAPHQL_REFERENCE
- API_DOCUMENTATION
- ENDPOINT_REFERENCE

Every discovered resource is canonicalized through B391 HTTPS identity.

## Hard boundaries

- no network access;
- no session/auth material;
- no endpoint execution;
- no provider registration;
- no generated tool;
- no trust or truth authority;
- no activation authority;
- no Owner Policy bypass.

Documentation is evidence of a possible capability source, not proof that an endpoint is safe, current, authenticated, semantically correct, or usable.

## B413 handoff

B413 may accept an exact B412 OPENAPI/SWAGGER discovery candidate plus the exact acquired specification bytes and translate it into a **non-activating** ToolCandidate. B412 itself performs no OpenAPI operation/schema translation.

## Tests

- OpenAPI document discovery remains non-authoritative.
- HTTPS links canonicalize and deduplicate.
- unrelated text produces no candidate.
- exact duplicate evidence and input order are deterministic.
- insecure HTTP links are not discovered.
- all reports remain network/permission/execution/activation false.

## Gates

1. `:core:runtime-web:test`
2. Core Fast
3. Android Debug
4. Android Emulator Recovery
5. LIFEOS Product Gold

## Authority invariant

`web documentation != trusted API != ToolCandidate != provider != permission != activation != execution`


Validation note: Early CI validation trigger; this stacked PR remains DO NOT MERGE until sequential promotion reaches B412.


Validation note: Isolated exact-main CI trigger; DO NOT MERGE before B390 sequential promotion.
