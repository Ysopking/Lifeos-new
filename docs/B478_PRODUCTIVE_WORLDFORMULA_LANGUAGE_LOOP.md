# B478 — Productive WELTFORMEL Language Loop

B478 moves B469-B477 from internal language metadata into the productive conversation path.

For every non-fast conversation turn:

```text
bounded context retrieval
 -> first language interpretation
 -> B472 LanguageStateSufficiency plan
 -> if perception state is missing:
      B475 targeted context retrieval
      -> explicit world-state evidence for non-reference state dimensions
      -> second language interpretation with B473 world convergence
 -> final B477 SemanticExecutionGate
 -> capability/resource/Owner Policy authorities
```

Reference gaps are deliberately not converted into synthetic world support. B471 must resolve them
against exact Photon revisions in the targeted second-pass context, and B477 keeps referenced actions
closed if exact-revision grounding is absent.

Stale/outside-context selected revision references are explicitly requested from bounded revisioned
storage during the second pass. Ambiguity/clarification gaps do not trigger blind retrieval.

Personal-corpus shadow language remains available in both passes; world evidence is supplied to the
same baseline/shadow understanding engines and cannot mutate the productive lexicon.

The runtime publishes WorldFormulaLanguageRefinementTrace on LanguageSubmissionResult so tests and
diagnostics can distinguish:

- no second pass required
- targeted retrieval performed
- final StateSufficiency status
- remaining WorldGap ids
- remaining clarification needs
- B473 world-evidence fingerprint

Hard invariants:

- targeted retrieval is bounded and excludes the current source Photon
- reference uncertainty is not disguised as generic state evidence
- retrieved context does not grant effect authority
- StateSufficiency does not grant execution authority
- conditional/hypothetical/planned/etc. semantics remain subject to B477
- Owner Policy remains the external-effect authority
