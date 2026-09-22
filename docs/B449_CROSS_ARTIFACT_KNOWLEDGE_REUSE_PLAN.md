# B449 — Cross-Artifact Knowledge Reuse — exact implementation plan

Base: B448 exact head `6a68d61ad48ed3da40b1520df9c782a8d9c90a2f`
Branch: `b449-cross-artifact-knowledge-reuse-v1`

## Purpose

B449 reuses exact semantic knowledge and exact non-prose structure lineage between artifacts without turning generated text into evidence.

## Exact reuse boundary

A claim may be reused only when:

- the source artifact revision is exact
- the source revision manifest matches that exact revision
- the source artifact manifest binds the exact source semantic plan
- source and target semantic plans are closed
- the source and target claim fingerprints are exactly equal
- therefore canonical content, confidence and exact Photon revision evidence are equal

Changed, missing or foreign claims are explicitly reported as unreusable.

## Structure reuse

An exact B432 structure may be emitted only as a non-authoritative structure hint when:

- it belongs to the exact source semantic plan
- its exact covered claim set equals the requested reuse set
- every covered claim is exactly reusable

The hint never becomes target structure authority and never carries prose.

## Hard boundaries

- generated prose != evidence
- source artifact != target artifact
- claim-id match != semantic equality
- structure hint != target structure
- reuse plan != automatic copy
- reuse plan != finalization

Authority invariant:

`exact source revision + exact semantic equality -> bounded reuse evidence != target truth != final artifact`

## B450 handoff

B450 may use B449 reuse evidence inside the complete language/artifact GOLD cycle, but every productive artifact still requires its own exact semantic plan, renderer validation, revision lineage and Product Gold evidence.
