# B416 — Action Outcome Learning — exact implementation plan

Base: B415 exact head b61f9711005e8f4ac474bf5987c776cc374c35a7
Branch: b416-action-outcome-learning-v1

## Purpose

B416 learns only from the exact B411 request→plan→Owner Policy→EffectReceipt→observation→outcome provenance graph.

One unambiguous CONFIRMED outcome may create a positive next-cycle learning candidate. One unambiguous CONTRADICTED outcome may create a negative next-cycle candidate. PARTIAL, UNKNOWN, missing or ambiguous outcomes do not become learned rules.

## Hard invariants

- execution success is not inferred from request or EffectReceipt alone
- confirmed observation is outcome evidence, not causal proof
- correlation is not causal authority
- learning candidate is not truth, Owner Policy, promotion or execution authority
- current-cycle world state is never mutated by B416
- raw EffectReceipt detail is not copied into learning evidence

## B417 handoff

B417 will classify concrete Web/action failure families (401/403/schema drift/login/redirect/etc.) over these exact outcome/receipt chains without treating one failure as a globally promoted rule.