# B394 — WebClaimExtractor — exact implementation plan

Base head: `b44e6e6782d398cb54beeb5d989288b400520114`
Branch: `b394-web-claim-extractor-v1`
Module: `:core:runtime-web`

## Reuse / non-duplication contract

B394 does not create a second evidence or convergence system:
- B393 remains the typed ingest source.
- existing `DeepSearchClaimGraphProjector` remains a projection of already-produced DeepSearch hypotheses/evidence.
- existing `DeepSearchClaimCompatibility` remains the conservative contradiction comparator for DeepSearch.
- `FieldEvidence` remains the evidence model.
- B394 emits source-span claim candidates only. A candidate is not evidence, truth, citation, convergence, or action authority.

## Prepared production code

NEW `core/runtime-web/src/main/kotlin/app/lifeos/core/runtime/web/WebClaimExtractor.kt`

- L6–9: `WebClaimKind`: SENTENCE / STRUCTURED_RECORD.
- L11–28: bounded `WebClaimExtractionPolicy`; max count, minimum chars, maximum chars and deterministic fingerprint.
- L30–79: immutable `WebClaimCandidate`; exact B393 resource/document/text provenance, exact source span, bounded text and explicit no truth/evidence/citation/execution authority.
- L81–122: immutable `WebClaimExtractionResult`; canonical candidate ordering, exact source binding and no truth/evidence authority.
- L124–131: architecture invariant: candidate extraction does not promote spans to evidence/truth/citations.
- L132–252: `WebClaimExtractor.extract`:
  - uses B393 visible text for natural-text formats;
  - uses B393 raw text for JSON/CSV so structure is not silently rewritten;
  - returns empty result for PDF/unsupported/empty artifacts;
  - creates deterministic exact-offset candidate spans;
  - JSON/CSV rows become STRUCTURED_RECORD candidates rather than invented natural-language claims;
  - all other text uses deterministic sentence/newline segmentation;
  - policy filters short spans and bounds claim count/length;
  - every candidate is content-derived from B393 document/text identity.
- L254–259: internal exact candidate-span model.
- L261–273: structured-record line segmentation.
- L275–294: sentence/newline segmentation.
- L296–326: whitespace trimming, minimum-length filtering, surrogate-safe max-length truncation.
- L328–332: canonical candidate ordering.
- L334–353: deterministic candidate fingerprint.
- L355–370: deterministic result fingerprint.
- L372–375: exact source-text SHA-256.
- L377–398: length-delimited SHA-256 helper.
- L400: sentence terminator set.

## Prepared tests

NEW `core/runtime-web/src/test/kotlin/app/lifeos/core/runtime/web/WebClaimExtractorTest.kt`

- L11–38: HTML visible sentences become exact source-span candidates and carry no authority.
- L41–52: compact JSON remains one STRUCTURED_RECORD rather than invented semantics.
- L55–67: CSV lines become ordered STRUCTURED_RECORD candidates.
- L70–81: opaque PDF emits no candidate and no source-text fingerprint.
- L84–92: short fragments are filtered.
- L95–113: max candidate count is explicitly/deterministically truncated.
- L116–138: max claim length truncates exact source span while preserving B393 provenance.
- L141–152: identical exact B393 document produces stable result/candidate identities.
- L155–168: changed acquired payload changes B393 document and B394 claim identity on the same Web resource.
- L171–194: helper constructs source through real B391 → B392 → B393 contracts.

## Pre-implementation verification

Before writing:
1. verify no existing `WebClaimExtractor`, `WebClaimCandidate` or `WebClaimExtractionResult` collision;
2. verify `DeepSearchClaimGraphProjector` organizes existing DeepSearch evidence/hypotheses rather than extracting raw Web source spans;
3. verify `DeepSearchClaimCompatibility` is only a contradiction comparator and should not be duplicated;
4. verify B393 exposes exact resource/document fingerprint and raw/visible text;
5. verify B394 does not instantiate `FieldEvidence`, `DeepSearchEvidence`, or truth/convergence types;
6. verify PDF/unsupported/empty B393 artifacts produce no claims;
7. verify no file is added to the bounded 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-web:test --tests 'app.lifeos.core.runtime.web.WebClaimExtractorTest'`
2. `./gradlew :core:runtime-web:test`
3. Core Fast.
4. Clean restack after B393/B392/B391/B380 promotion, then Debug / Recovery / Product Gold before merge.
