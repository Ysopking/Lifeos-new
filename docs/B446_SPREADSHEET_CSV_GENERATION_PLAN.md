# B446 — Spreadsheet / CSV Generation — exact implementation plan

Base: B445 exact head \`c5d7c396bc1fa9334d4c078137229a0d29744bb9\`
Branch: \`b446-spreadsheet-csv-generation-v1\`

## Purpose

B446 generates real deterministic CSV and XLSX artifacts from explicit evidence-bound tabular models.

It does not infer tables from rendered prose. It consumes a dedicated \`TabularArtifactPlan\` that is bound to one exact \`SemanticArtifactPlan\`.

## Cell model

Supported cell kinds:

- \`HEADER\` — presentation-only label, no factual authority
- \`TEXT\` — must equal one exact resolved claim's canonical content
- \`DECIMAL\` — must equal one exact resolved claim's canonical decimal content
- \`FORMULA\` — deterministic same-sheet arithmetic over numeric/formula cells
- \`BLANK\`

Every literal factual cell is bound to:

- exact claim ID
- exact claim evidence stable keys
- exact semantic-plan fingerprint
- exact source world revision

## Formula safety

The first formula profile intentionally permits only bounded local arithmetic.

Allowed:

- same-sheet cell references
- numbers
- \`+\`, \`-\`, \`*\`, \`/\`
- parentheses

Rejected:

- functions
- ranges
- external workbooks
- cross-sheet references
- URLs
- DDE / dynamic-data patterns
- quoted expressions
- self-reference
- missing references
- dependency cycles

Formula cells carry no truth or execution authority. XLSX clients may calculate them; LIFEOS does not treat calculated results as verified evidence without a later observation/validation step.

## CSV safety

CSV is a non-executable interchange format in B446:

- exactly one sheet
- no formula cells
- formula-injection-shaped text/header values beginning with \`=\`, \`+\`, \`-\` or \`@\` are rejected
- RFC4180-style quoting is deterministic

## XLSX package

B446 writes a real OOXML workbook with:

- workbook.xml
- worksheet parts
- workbook relationships
- styles.xml
- core/app metadata
- inline strings
- numeric values
- local formula elements
- automatic recalculation requested on open
- deterministic ZIP ordering/timestamps

## Hard boundaries

- spreadsheet cell != truth
- formula != verified result
- workbook recalculation != observation
- header != claim
- CSV/XLSX != finalization
- CSV/XLSX != publication
- rendered value != new evidence
- semantic claim evidence != permission to execute external spreadsheet features

Authority invariant:

\`exact claims + exact evidence + bounded transformations -> tabular artifact\`

not

\`tabular artifact / formula output -> truth / action authority\`

## B447 handoff

B447 may create real presentation artifacts from exact evidence-bound content and already validated charts/tables. It must not treat B446 formulas or rendered workbook values as verified claims unless their outcomes were separately observed and validated.
