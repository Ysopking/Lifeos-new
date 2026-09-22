# B439 — Document Critic — exact implementation plan

Base: B438 exact head `d7562d84d687beb584fce84f7e06aa7f24f112a1`
Branch: `b439-document-critic-v1`

## Purpose

B439 adds a deterministic critique layer after B438 factual validation. It evaluates quality signals without rewriting the draft.

The critic covers the roadmap dimensions: completeness, redundancy, style and overall document quality while keeping factual integrity and coherence explicit.

## Exact inputs

- B431 `DocumentGoal`
- B432 `DocumentStructurePlan`
- B433 `DocumentArgumentPlan`
- B435 `ParagraphComposition`
- B436 `CoherentDocumentDraft`
- B437 `CitationBindingPlan`
- B438 `FactualDraftValidationReport`

## Rules

- failed B438 factual closure is a blocking critique finding
- required-claim coverage must remain exact across structure, argument, paragraphs, citations and validation
- duplicate normalized paragraph source text becomes a bounded redundancy warning
- style evaluation is deterministic and only interprets explicit machine-readable B439 constraints:
  - `style:max-paragraph-chars=N`
  - `style:max-document-chars=N`
  - `style:require-transition-variety`
- arbitrary natural-language constraints are not silently reinterpreted as owner style
- B439 never rewrites text, creates facts, learns owner style or finalizes an artifact

## B440 handoff

B440 may consume the exact B439 critique report as revision evidence. Every revision must remain bounded to the exact draft lineage and must pass B438 again before convergence/finalization.

Authority invariant:

`critique != rewrite != factual authority != owner style != finalization`
