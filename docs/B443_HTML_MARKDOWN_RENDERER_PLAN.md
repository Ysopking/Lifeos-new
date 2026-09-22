# B443 — HTML / Markdown Renderer — exact implementation plan

Base: B442 exact stack
Branch: `b443-html-markdown-renderer-v1`

## Purpose

B443 turns the already validated and converged B431–B440 document chain into real deterministic UTF-8 Markdown or HTML payloads.

It reuses the existing document model and B437 citation bindings. It does not introduce a second prose generator and does not render an unresolved or non-converged draft.

## Exact input closure

Rendering requires one exact lineage:

- B431 `DocumentGoal` with output format MARKDOWN or HTML
- B432 `DocumentStructurePlan`
- B435 `ParagraphComposition`
- B436 `CoherentDocumentDraft`
- B437 `CitationBindingPlan`
- passing B438 `FactualDraftValidationReport`
- converged B440 `IterativeDraftRevisionReport` whose final evidence points to the exact rendered draft

## Output

`StructuredTextArtifact` contains:

- exact goal / structure / draft / citation / factual-validation / revision fingerprints
- deterministic media type
- exact UTF-8 content and content fingerprint
- exact rendered claim IDs
- exact evidence stable keys in first-rendered order
- content-addressed artifact fingerprint

Markdown uses evidence-key footnotes. HTML uses escaped prose plus deterministic citation anchors and a citation list.

## Hard boundaries

- render != rewrite
- render != new claim
- render != new evidence
- render != factual truth
- render != artifact finalization
- render != publication

B444 may package the exact B443 validated document surface into DOCX without changing claims, citations or factual closure.

Authority invariant:

`validated draft != rendered bytes != finalized artifact != published artifact`
