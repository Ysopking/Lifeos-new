# B440 — Iterative Draft Revision — exact implementation plan

Base: B439 exact head `66e817a3646c5c8a5889d58415afbcf04e9b102b`
Branch: `b440-iterative-draft-revision-v1`

## Purpose

B440 closes the document loop from draft → critique → revision → revalidation → critique until convergence.

It does not introduce a second free-form prose generator. A revision producer may propose a changed surface, but B440 controls whether that revision is accepted into the iterative chain.

## Exact evidence

Every revision candidate must carry:

- the same B431 `DocumentGoal` fingerprint
- a distinct draft fingerprint
- fresh B438 `FactualDraftValidationReport` evidence
- fresh B439 `DocumentCritiqueReport` evidence
- exact changed-surface fingerprints
- exact reason codes

## Acceptance

A candidate is accepted only when:

- B438 factual validation still passes
- B439 is not BLOCKED
- the DocumentGoal is unchanged
- the draft fingerprint changed
- critique quality strictly improves lexicographically:
  1. fewer ERROR findings
  2. then fewer WARNING findings
  3. then fewer total findings

The loop ends as:

- `CONVERGED` when critique is clean
- `BLOCKED` when factual closure or blocking critique fails
- `STALLED` when a candidate does not improve or no next candidate exists
- `MAX_ROUNDS_REACHED` when the bounded revision budget is exhausted

## Authority boundary

B440 has no factual, owner-style, finalization or automatic rewrite authority.

`revision candidate != accepted revision != factual truth != owner style != final artifact`

## B441 handoff

B441 may supply owner-style learning signals to later revision producers, but B440 continues to require fresh B438 + B439 evidence after every changed draft.
