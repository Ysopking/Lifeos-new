# B472 — Language State Sufficiency

B472 connects the new language realization/grounding layers to the existing B455/B456
StateSufficiency + WorldGap infrastructure.

The coordinator separates two classes of unresolved language:

```text
missing/stale world information -> PERCEPTION need -> StateContract -> WorldGap.Perception
ambiguous wording/missing role  -> CLARIFICATION need
```

Examples:

- stale or id-only reference -> perception need for an exact state/revision
- unresolved condition -> perception need for condition state
- close reference candidates -> clarification, not a forced winner
- missing semantic role such as recipient -> clarification
- negation/quotation/hypothetical scope -> semantic modality, not missing state

The generated language state contract uses the weakest descriptive evidence threshold because this
gate decides whether an interpretation has enough context, **not** whether an external action is
authorized. Domain-specific action contracts and Owner Policy remain stricter downstream.

Hard invariants:

- ambiguity != missing sensor information
- semantic modality != state insufficiency
- sufficient language context != execution authority
- sufficient language context != world-state mutation authority
- existing StateSufficiencyDetector and WorldGap types are reused
