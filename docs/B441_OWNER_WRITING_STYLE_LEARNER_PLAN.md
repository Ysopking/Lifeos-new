# B441 — Owner Writing Style Learner — exact implementation plan

Base: B440 exact head `c90e33805a43b0538d3672308685faf959bcaa3a`
Branch: `b441-owner-writing-style-learner-v1`

## Purpose

B441 learns a descriptive owner writing-style profile from explicit owner-authored or owner-approved writing evidence.

## Evidence boundary

Every sample requires:

- exact source artifact fingerprint
- exact source revision fingerprint
- exact owner-confirmation fingerprint
- origin = `OWNER_AUTHORED` or `OWNER_APPROVED`
- exact language tag

Raw sample text is reduced to a text fingerprint plus deterministic structural metrics; the learned profile does not retain the raw prose.

## Learned metrics

The first bounded profile learns:

- median words per sentence
- median characters per paragraph
- exact supporting sample fingerprints
- exact supporting source artifact fingerprints
- exact owner confirmation fingerprints

At least two distinct source artifacts are required. Multiple revisions of one artifact do not count as independent style evidence.

## Hard boundaries

- observed writing style != owner command
- style profile != preference execution
- style profile != factual authority
- style profile != B440 revision authority
- style profile != finalization authority
- languages are learned separately

## B442 handoff

B442 may turn exact owner edit diffs into additional bounded writing-style evidence, but only with explicit owner provenance and without treating one edit as a global style rule.

Authority invariant:

`owner writing evidence != preference command != rewrite authority != final artifact`
