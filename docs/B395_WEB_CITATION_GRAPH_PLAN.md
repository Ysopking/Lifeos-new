# B395 — WebCitationGraph — exact implementation plan

Base head: `47c5b27fdb7cb4717c2bc399874c7d5fd6287318`
Branch: `b395-web-citation-graph-v1`
Module: `:core:runtime-web`

## Reuse / non-duplication contract

B395 does not create a second evidence graph:
- B391 remains canonical Web resource identity.
- B392 remains acquisition + immutable receipt authority.
- B393 remains typed ingest/document identity.
- B394 remains raw source-span candidate extraction.
- existing `DeepSearchClaimGraphProjector` remains the graph for already-produced DeepSearch evidence/hypotheses.
- existing `FieldEvidence` remains the evidence model.

B395 provides source addressability only: it binds a B394 candidate to the exact B391 resource, exact B393 document, exact source-text fingerprint and exact character range. It does not decide truth, source reliability, evidentiary weight, semantic equivalence, citation sufficiency or execution permission.

## Prepared production code

NEW `core/runtime-web/src/main/kotlin/app/lifeos/core/runtime/web/WebCitationGraph.kt`

- L6–8: `WebCitationEdgeKind.DOCUMENT_CONTAINS_CLAIM`.
- L10–46: `WebCitationAnchor`; exact canonical URL/resource/document/text fingerprints + source range + quote digest; explicit no truth/evidence authority.
- L48–74: `WebCitationDocumentNode`; exact B391/B393 acquisition/document binding.
- L76–103: `WebCitationClaimNode`; candidate/anchor exact lineage and quote fingerprint validation.
- L105–123: `WebCitationEdge`; deterministic document → claim containment edge.
- L125–182: immutable `WebCitationGraph`; exact resource/document/extraction lineage, one edge per claim, deterministic ordering/identity, `provenanceBound=true`, and no truth/evidence/trust/execution authority.
- L184–191: architecture invariant separating provenance/addressability from epistemic authority.
- L192–344: `WebCitationGraphBuilder.build`:
  - exact B391 resource must match B393 document;
  - exact B394 extraction must match B393 document;
  - recompute B394 source-text fingerprint from the B393 raw/visible source;
  - reject textless documents carrying claims;
  - independently re-check every B394 candidate range against the exact B393 source substring;
  - build exact canonical URL anchors;
  - build one DOCUMENT_CONTAINS_CLAIM edge per candidate;
  - emit deterministic graph identity.
- L346–349: canonical claim-node ordering.
- L351–354: canonical edge ordering.
- L356–372: deterministic graph fingerprint.
- L374–377: quote/source SHA-256 helper.
- L379–399: dependency-free length-delimited citation fingerprint helper.

## Prepared tests

NEW `core/runtime-web/src/test/kotlin/app/lifeos/core/runtime/web/WebCitationGraphBuilderTest.kt`

- L11–32: exact B391 → B393 → B394 lineage creates deterministic graph with no epistemic/execution authority.
- L35–56: every anchor resolves back to the exact B393 source substring and canonical URL.
- L59–70: same-host resource substitution fails closed.
- L73–85: extraction/document substitution on the same resource fails closed.
- L88–99: tampered claim/source-span representation fails closed before graph construction.
- L102–118: opaque PDF still has a document citation node but no claim nodes/edges.
- L121–130: same exact input yields stable graph identity.
- L133–154: changed payload changes document node, claim node, edge and graph identity.
- L157–184: fixture flows through real B391 → B392 → B393 → B394 contracts.
- L186–191: exact fixture carrier.

## Pre-implementation verification

Before writing:
1. verify no existing `WebCitationGraph`, `WebCitationAnchor`, `WebCitationDocumentNode`, or `WebCitationClaimNode` collision;
2. verify existing `DeepSearchClaimGraphProjector` consumes already-established DeepSearch evidence/hypotheses and therefore is not a raw Web citation graph;
3. verify `FieldEvidence` remains outside `:core:runtime-web` and B395 introduces no dependency on `:core:field` or `:core:runtime`;
4. verify B393 carries exact acquisition receipt fingerprint, payload hash, resource id and deterministic document fingerprint;
5. verify B394 carries exact source-text fingerprint, document fingerprint and character ranges;
6. verify B395 re-checks the source substring independently rather than trusting B394 offsets blindly;
7. verify PDF/unsupported/textless B393 artifacts cannot fabricate claim edges;
8. verify no file is added to the bounded 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-web:test --tests 'app.lifeos.core.runtime.web.WebCitationGraphBuilderTest'`
2. `./gradlew :core:runtime-web:test`
3. Core Fast.
4. Clean restack after B394/B393/B392/B391/B380 promotion, then Debug / Recovery / Product Gold before merge.
