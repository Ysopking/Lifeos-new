# B474 — Temporal + Modal Reality Model

B474 makes time and modality first-class inputs to B469 proposition realization.

The new TemporalModalRealityEngine binds explicit temporal values to their clause and classifies each
proposition as PAST, CURRENT, FUTURE or UNSPECIFIED. It also carries modal state for:

- assertion
- question
- request
- possibility
- hypothesis
- counterfactual
- quotation
- condition
- negation
- remembered content
- planned content

Requests without an explicit date are future possibilities, not current realized state. Remembered
content uses HISTORY representation; counterfactual content remains POSSIBILITY.

The engine classifies the language claim only. It does not establish that a remembered event actually
occurred, that a plan will happen, or that a future request has been executed.

Hard invariants:

- future request != realized state
- remembered proposition != verified history
- counterfactual != fact
- temporal anchor != execution schedule unless a later scheduler contract accepts it
- modal/temporal classification grants no execution or world-state authority
