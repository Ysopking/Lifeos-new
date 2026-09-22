# B444 — DOCX Generation — exact implementation plan

Base: B443 exact head \`f1cb1ae4ba9e40db51268ce4f51e58c4b24c97b0\`
Branch: \`b444-docx-generation-v1\`

## Purpose

B444 turns the already converged B431–B440 document pipeline into a real deterministic OOXML Word document.

The output is a valid DOCX package with:

- section headings derived only from B432 structure
- exact B436 coherent prose
- inline numbered citations bound to exact B437 evidence keys
- a references section
- optional tables
- optional PNG/JPEG images with alt text
- deterministic package metadata and ZIP entry ordering
- exact lineage fingerprints back to B431/B432/B436/B437/B438/B440

## Evidence and authority boundary

DOCX packaging is presentation only.

The renderer requires:

- \`DocumentGoal.outputFormat == DOCX\`
- exact B432 structure lineage
- exact B435 paragraph lineage
- exact B436 coherent draft
- exact B437 citation coverage
- passing B438 factual closure
- converged B440 revision state with clean B439 critique

Optional table/image embeds require:

- exact section key
- exact source-artifact fingerprint
- claim IDs already present in that section
- evidence stable keys already present in the B437 citation plan

No embedded asset may widen the document claim/evidence set.

## Determinism

The generated OOXML package uses:

- fixed package entry ordering
- fixed ZIP timestamps
- fixed core-property timestamps
- deterministic relationship IDs
- deterministic image filenames
- SHA-256 over final package bytes

Identical inputs therefore produce identical DOCX bytes and identical artifact fingerprints.

## Hard invariants

- DOCX != factual authority
- DOCX != claim creation
- DOCX != citation selection
- DOCX != artifact finalization
- DOCX != publication
- table/image attachment != new evidence
- visual formatting != semantic mutation
- failed B438 or non-converged B440 draft != renderable DOCX

Authority invariant:

\`validated draft + exact citations + bounded embeds -> DOCX presentation\`

not

\`DOCX presentation -> truth / permission / publication\`

## B445 handoff

B445 may consume the same exact converged document lineage to produce PDF output. It must preserve the same factual/citation/finalization boundaries and must not treat DOCX rendering as new evidence.
