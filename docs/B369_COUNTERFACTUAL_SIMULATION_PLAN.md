# B369 — CounterfactualSimulation — exact implementation plan

Base: B368 head `aa36cd41712f2e868b8a1df89b69d631db83c032`
Branch: `b369-counterfactual-simulation-v1`

## Reuse audit

B369 must reuse and preserve:
- `CounterfactualWorldFormulaRunner` as the existing isolated World Formula evaluator;
- `WorldModelNamespaceGate.counterfactual` as namespace authority;
- `WorldFormulaRequest` and `CounterfactualWorldFormulaInput` as simulation inputs;
- B368 complete reasoning states as the alternative set.

No productive world mutation, no hypothesis truth promotion, no action execution.

## Production file

NEW `core/runtime-reasoning/src/main/kotlin/app/lifeos/core/runtime/reasoning/ReasoningCounterfactualSimulation.kt`

Implement:
1. one explicit WorldFormulaRequest per complete B368 state;
2. reject incomplete states, duplicate state inputs, omitted complete states, or unknown state fingerprints;
3. derive deterministic intervention fingerprint from search seed/state/hypothesis ids/request id;
4. create existing `WorldModelScenario` through `WorldModelNamespaceGate.counterfactual`;
5. execute through an adapter over existing `CounterfactualWorldFormulaRunner`;
6. verify returned namespace/base/intervention identity exactly;
7. return canonical simulations for every complete state;
8. keep `productiveCommitAllowed == false` for scenario and snapshot.

## Tests

Seal exact state coverage, canonical ordering, incomplete/duplicate/missing input failure, and namespace/base/intervention verification.

## Gate

B369 remains stacked/draft behind B368.

## Module placement

B369 starts `:core:runtime-reasoning`, depending on `:core:reasoning` and existing `:core:runtime`; this keeps simulation orchestration out of the 505-file runtime monolith while reusing the existing counterfactual World Formula runtime.

## Promotion run

B369 is cleanly restacked on merged B368/main. This exact head is the promotion candidate for main-targeted CI, including the runtime-reasoning module gate.
