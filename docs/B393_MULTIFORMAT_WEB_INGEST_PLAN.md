# B393 — Multi-Format Web Ingest — exact implementation plan

Base head: `b3cee6f5906adcf1a40536ee75d1c0da1d510e2f`
Branch: `b393-multiformat-web-ingest-v1`
Module: `:core:runtime-web`

## Reuse / non-duplication contract

B393 does not create another network layer or document-truth layer:
- B391 remains canonical Web resource identity.
- B392 remains the only bounded acquisition/redirect/receipt runtime in the Web stack.
- existing app `FileContentParserRegistry`, `PlainTextFileContentParser`, `ZipXmlFileContentParser` remain local-file ingestion and are not imported upward into core.
- existing app `WebDocumentTextExtractor` remains legacy DeepSearch-local extraction until later migration.
- B393 only converts an already-acquired B392 payload into a typed, bounded ingest artifact.

No acquisition permission, evidence authority, truth authority, semantic authority, action authority, or productive mutation is introduced.

## Prepared production code

NEW `core/runtime-web/src/main/kotlin/app/lifeos/core/runtime/web/WebMultiFormatIngest.kt`

- L9–18: `WebIngestFormat`: HTML / PLAIN_TEXT / JSON / XML / RSS_ATOM / CSV / PDF / UNKNOWN.
- L20–25: `WebIngestState`: INGESTED_TEXT / BINARY_OPAQUE / EMPTY / UNSUPPORTED.
- L27–43: `WebIngestPolicy`; bounded output character budget and deterministic policy fingerprint.
- L45–116: immutable `WebIngestDocument`; exact B392 receipt/payload binding, typed format/state, bounded raw/visible text, deterministic identity, and explicit no truth/claim/semantic/execution authority.
- L118–124: architecture invariant: no network I/O, no evidence/truth promotion, no fake PDF extraction.
- L125–246: `WebMultiFormatIngestor.ingest`:
  - accepts only B392 `ACQUIRED` results;
  - re-verifies exact payload hash/size against receipt;
  - preserves empty acquisitions as EMPTY;
  - classifies supported media types;
  - detects RSS/Atom carried as generic XML;
  - retains PDF as typed BINARY_OPAQUE rather than inventing text;
  - decodes text strictly and bounds raw/visible outputs;
  - returns deterministic ingest identity.
- L248–263: surrogate-safe text truncation.
- L265–288: format classification with bounded XML feed sniffing.
- L290–311: MIME → format mapping.
- L313–320: media-type normalization.
- L322–348: strict UTF-8 / UTF-16 BOM decoding with malformed/unmappable input rejected.
- L350–357: deterministic HTML visible-text extraction; comments/script/style removed.
- L359–377: deterministic XML/RSS visible-text projection without enabling external entities.
- L379–404: named + numeric entity decoding.
- L406–410: visible whitespace normalization.
- L412–437: deterministic ingest-document fingerprint.
- L439–464: dependency-free length-delimited SHA-256 helper.
- L466–486: supported text-format set, BOM constants, extraction regexes.

PDF is intentionally opaque in B393. A future verified PDF text decoder may attach extracted text without changing the B392 payload identity; B393 will not claim extraction it cannot verify.

## Prepared tests

NEW `core/runtime-web/src/test/kotlin/app/lifeos/core/runtime/web/WebMultiFormatIngestorTest.kt`

- L12–36: HTML keeps raw source while exposing visible text without script/style content; no authority granted.
- L39–49: JSON structured text preserved exactly.
- L52–72: generic XML feed becomes RSS_ATOM; ordinary XML remains XML.
- L75–101: plain text, CSV and UTF-16LE BOM decoding.
- L104–118: PDF remains typed opaque binary with no invented text.
- L121–134: unsupported acquired media remains explicit UNSUPPORTED.
- L137–146: malformed textual bytes fail closed instead of replacement-character decoding.
- L149–162: deterministic text-budget truncation without dangling high surrogate.
- L165–174: empty acquired payload becomes EMPTY.
- L177–198: B392 HTTP_ERROR cannot enter B393 ingest.
- L201–213: exact acquisition receipt + payload participate in ingest identity.
- L216–237: helper acquires fixture bytes through the real B392 runtime contract.

## Pre-implementation verification

Before writing:
1. verify no existing `WebMultiFormatIngestor`, `WebIngestDocument`, `WebIngestFormat` or `WebIngestState` collision;
2. verify current file parsers are app-local and therefore cannot be reused by `:core:runtime-web` without reversing module boundaries;
3. verify current Web text extraction is app-local inside `AndroidWebDeepSearchSource.kt`;
4. verify B392 already enforces acquisition byte/media/redirect bounds and exposes exact payload SHA-256 + receipt identity;
5. verify no new external parsing dependency is required;
6. verify XML projection is text-only scanning and cannot resolve external entities;
7. verify PDF is represented honestly as opaque binary until a dedicated verified decoder exists;
8. verify no file is added to the bounded 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-web:test --tests 'app.lifeos.core.runtime.web.WebMultiFormatIngestorTest'`
2. `./gradlew :core:runtime-web:test`
3. Core Fast.
4. Clean restack after B392/B391/B380 promotion, then Debug / Recovery / Product Gold before merge.
