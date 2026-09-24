# B469 — Language Realization Contract

B469 introduces a WELTFORMEL-style realization boundary inside the existing language pipeline.

The key invariant is:

```text
observed utterance event != proposition != world fact != execution authority
```

The utterance itself is an ACTUAL observed language event. The semantic proposition expressed by it
is classified independently as BELIEF, POSSIBILITY or PROJECTED according to speech act and scope.

Examples:

- assertion -> BELIEF / SPEAKER_ASSERTED
- confirmation/correction -> BELIEF / SPEAKER_CONFIRMED
- question -> POSSIBILITY / UNRESOLVED
- command/request -> POSSIBILITY / UNRESOLVED
- hypothetical -> POSSIBILITY / HYPOTHETICAL
- quotation -> PROJECTED / QUOTED

Negation, quotation, hypothetical and conditional scope remain explicit modal statuses and blockers.

No realization state may mutate Personal World directly or grant execution authority. The existing
SemanticExecutionGate remains the action boundary. Later B470-B475 blocks can ground propositions
against Personal World, temporal episodes and StateSufficiency without weakening this separation.
