# B413 — OpenAPI → ToolCandidate — exact implementation plan

Preparation base: B412 head `aa37af1289a69ccd5e3b2338a67e0969bcdf2169`
Branch: `b413-openapi-tool-candidate-v1`

## Existing architecture reused

- B412 discovers exact Web/API resources but grants no trust/provider/activation authority.
- B391 owns canonical HTTPS resource identity.
- B392 owns exact acquisition receipt and payload identity.
- ToolWorkshop already owns specification/design/source/build/test/security/capability verification.
- Genesis already owns smallest-safe-solution routing.
- Existing generated-tool trial, Owner Policy and promotion paths remain the only path toward activation.

## Goal

Translate an exact, normalized OpenAPI/Swagger document discovered by B412 into a deterministic **non-activating** `OpenApiToolCandidate`.

B413 does not execute an endpoint and does not embed credentials.

## Parser boundary

Raw JSON/YAML syntax parsing is deliberately separated from capability translation. A parser must first produce `NormalizedOpenApiDocument` containing exact B412 candidate fingerprint, B391 resource identity, acquired payload SHA-256, OpenAPI version and bounded canonical operation descriptors.

## Translation

`OpenApiToolCandidateTranslator`:
- requires B412 OPENAPI_DOCUMENT or SWAGGER_DOCUMENT;
- binds exact requirement/candidate/resource/document lineage;
- selects operations structurally matching the requested capability contracts;
- canonicalizes operation ordering;
- requests `NETWORK_ACCESS` as metadata only;
- preserves unresolved authentication explicitly;
- requires later Owner Policy;
- grants no permission/auth/provider/trust/activation/execution authority.

## B414 handoff

B414 may use the exact B413 candidate to enrich ToolWorkshop design evidence. It must retain all existing ToolWorkshop build/security/test/trial gates and must never turn requested permission metadata into a grant.

## Tests

- exact B412 OpenAPI discovery translates deterministically;
- substituted resource fails closed;
- non-OpenAPI signal fails closed;
- unrelated operations yield no candidate;
- operation order/duplicates are deterministic and idempotent;
- authentication remains unresolved and non-authoritative.

## Gates

1. `:core:runtime-web:test`
2. Core Fast
3. Android Debug
4. Android Emulator Recovery
5. LIFEOS Product Gold

## Authority invariant

`OpenAPI document != trusted contract != credential != permission grant != active provider != execution`
