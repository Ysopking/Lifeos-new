# B448 — Living Artifact Runtime — exact implementation plan

Base: B447 exact head `bd6ce7c2f7bf0a56a2c01a0875f19f3ffb787856`
Branch: `b448-living-artifact-runtime-v1`

## Purpose

B448 makes already materialized artifacts revision-aware when their supporting knowledge changes.

It does not overwrite files automatically. It compares the exact current artifact revision, exact current closed semantic plan, a newer closed semantic plan and the next materialized asset. Only a relevant semantic delta on a strictly newer world revision may become a child-revision refresh candidate.

## Exact lineage

Every refresh evaluation binds:

- current `ArtifactRevisionRef`
- exact current `ArtifactRevisionManifest`
- exact current `SemanticArtifactPlan`
- exact next `SemanticArtifactPlan`
- exact current materialized asset SHA-256 and MIME type
- exact next materialized asset SHA-256 and MIME type
- added, removed and changed semantic claim ids

## States

- `STABLE`: the exact artifact is unchanged, or the world advanced without a relevant semantic delta and bytes remain identical
- `UPDATE_CANDIDATE`: a strictly newer world revision changed exact claim content/evidence/coverage
- `BLOCKED`: lineage mismatch, unresolved plan, world regression, same-revision mutation, media-type change, or render drift without semantic change

## Hard boundaries

- knowledge change != automatic rewrite
- refresh candidate != finalized revision
- refresh candidate != overwrite
- refresh candidate != publication
- newer world revision != factual truth
- renderer drift != knowledge change

Productive materialization still uses the existing B443–B447 renderers and the existing `ArtifactCoordinator` with the candidate's exact parent revision.

Authority invariant:

`knowledge delta != refresh candidate != artifact revision != publication`

## B449 handoff

B449 may reuse exact semantic claims, evidence and structure across artifacts, but must preserve source artifact/revision identity and may not copy unsupported generated text as evidence.
