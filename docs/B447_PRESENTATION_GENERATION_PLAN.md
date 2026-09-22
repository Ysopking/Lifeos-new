# B447 — Presentation Generation — exact implementation plan

Base: B446 exact head `36da615de27519efe2b202fc65390dab5db182c4`
Branch: `b447-presentation-generation-v1`

## Purpose

B447 turns an explicit evidence-bound presentation plan into a real deterministic PPTX package.

## Evidence boundary

Every factual slide bullet is bound to exactly one resolved `SemanticArtifactClaim` and must retain:

- exact claim id
- exact canonical claim content
- exact evidence stable keys
- exact semantic-plan fingerprint
- exact source-world revision

Slide titles are presentation metadata and do not become factual claims.

## Output

The renderer emits a standards-shaped OOXML presentation package with:

- `[Content_Types].xml`
- package relationships
- presentation manifest
- slide master
- slide layout
- theme
- one XML slide part and relationship part per slide
- core and application properties

ZIP entry timestamps and document timestamps are fixed so identical input produces identical bytes.

## Hard boundaries

- presentation title != factual claim
- slide rendering != claim creation
- slide rendering != evidence selection
- PPTX generation != artifact finalization
- PPTX generation != publication
- presentation output != Owner Policy authority

Authority invariant:

`semantic evidence -> bounded presentation plan -> deterministic PPTX != new truth != finalization != publication`

## B448 handoff

B448 may version and refresh exact B443-B447 artifact outputs when their upstream evidence or semantic plan changes, but must preserve immutable prior revisions and exact provenance.
