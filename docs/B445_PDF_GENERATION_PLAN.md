# B445 — PDF Generation — exact implementation plan

Base: B444 exact head \`e857f8a6940d6e95b415dadd630701932d8d5cdc\`
Branch: \`b445-pdf-generation-v1\`

## Purpose

B445 turns the exact converged B431–B440 document lineage into a deterministic real PDF 1.7 file.

The first productive PDF renderer provides:

- A4 page layout
- section headings derived from B432 structure
- exact B436 coherent prose
- exact B437 citation numbers and reference keys
- deterministic pagination
- fixed metadata timestamps
- PDF Info metadata
- deterministic object ordering, xref table and byte fingerprint

## Text encoding boundary

The first renderer uses the PDF standard Helvetica / Helvetica-Bold fonts with WinAnsi encoding.

This deliberately supports the Latin/Western-European text needed by the current English/German path without introducing a third-party PDF dependency.

If text contains a glyph that cannot be represented exactly by WinAnsi, B445 fails closed. It does not replace the glyph with "?" or silently alter the document.

A later renderer may add evidence-preserving embedded Unicode fonts as a separate reviewed capability.

## Exact closure requirements

The renderer requires:

- \`DocumentGoal.outputFormat == PDF\`
- exact B432 structure lineage
- exact B435 paragraph lineage
- exact B436 coherent draft
- exact B437 citation coverage
- passing B438 factual closure
- B440 convergence
- clean final B439 critique

## Determinism

Identical input produces identical bytes through:

- fixed page dimensions and margins
- deterministic line wrapping
- deterministic page/object numbering
- deterministic xref offsets
- fixed creation/modification timestamps
- standard built-in fonts
- SHA-256 over final PDF bytes

Tests validate xref offsets against the actual serialized object positions.

## Hard boundaries

- PDF layout != factual authority
- PDF layout != claim creation
- PDF layout != citation selection
- PDF layout != artifact finalization
- PDF layout != publication
- pagination != semantic mutation
- unsupported glyph != lossy substitution
- failed B438 or non-converged B440 draft != renderable PDF

Authority invariant:

\`validated draft + exact citations -> deterministic PDF presentation\`

not

\`PDF presentation -> truth / permission / publication\`

## B446 handoff

B446 adds spreadsheet / CSV generation from explicit structured data models. It should reuse exact evidence and artifact provenance rather than extracting tabular truth from rendered DOCX/PDF bytes.
