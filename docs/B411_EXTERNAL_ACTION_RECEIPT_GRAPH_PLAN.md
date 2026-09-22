# B411 — External Action Receipt Graph — exact implementation plan

Base prepared head: `10402782ecb534b9dc23d6e16649a4530cbc6ceb`
Branch: `b411-external-action-receipt-graph-v1`\nEarly stacked validation: compile and gate the prepared B406-B411 chain before sequential GOLD promotion

## Pre-implementation audit

1. Canonical immediate external-effect authority already exists as `EffectReceipt` + `ExternalEffectReceiptRepository`; B411 references it and does not create another immediate receipt type.
2. `EncryptedExternalEffectReceiptRepository` already owns action/idempotency final-state persistence. B411 never mutates or supersedes it.
3. B405–B410 produce immutable request/plan/policy/result identities. B411 records provenance links only and owns no execution authority.
4. Existing vault infrastructure provides versioned path-bound AES-GCM + AtomicFile. B411 reuses it for an append-only graph ledger.
5. Observation/outcome linkage must remain epistemically weaker than causation: `receipt != observation != success != causation`.

## Domain

NEW `core/runtime/.../agency/ExternalActionReceiptGraph.kt`

Typed immutable nodes:
- request
- capability plan
- Owner Policy decision
- canonical EffectReceipt reference
- later observation
- outcome classification

Typed edges:
- REQUEST_PLANNED_AS
- PLAN_AUTHORIZED_BY
- PLAN_EXPOSED_AS
- RECEIPT_OBSERVED_BY
- OBSERVATION_CLASSIFIED_AS_OUTCOME

Every revision binds its predecessor fingerprint. Exact duplicate append is idempotent; conflicting revision/predecessor is rejected.

## Canonical EffectReceipt binding

The graph references the exact immutable tuple:
`actionId + idempotencyKey + state + recordedAt + externalReference + observationFingerprint`.

It never stores raw secret/session payload bytes.

## Reconciliation

`ExternalActionObservationReconciler` accepts:
- exact action graph id,
- compatible external resource identity,
- observation at/after exposure,
- bounded horizon,
- expected/observed field fingerprints.

States:
- CONFIRMED
- CONTRADICTED
- PARTIAL
- UNKNOWN

Ambiguous or insufficient evidence remains UNKNOWN.

## Persistence

NEW `core/data/.../agency/EncryptedExternalActionReceiptGraphRepository.kt`

- one encrypted segment per graph revision,
- exact graph-id/revision/revision-id physical path binding,
- AAD binds ciphertext to physical path,
- atomic append,
- contiguous revisions,
- predecessor binding,
- duplicate exact append idempotent,
- missing middle revision / wrong path / corrupt ciphertext fail closed.

## Tests

- exact EffectReceipt tuple binding.
- graph substitution rejection.
- idempotent append.
- conflicting append rejection.
- missing middle revision rejection.
- wrong physical revision/path rejection.
- wrong resource observation rejected.
- ambiguous observation remains UNKNOWN.
- no observation remains UNKNOWN.
- confirmed/contradicted/partial stay explicit.
- restart loads exact graph history.

## Gates

1. targeted graph unit tests
2. `:core:runtime:test`
3. `:core:data:testDebugUnitTest`
4. Core Fast
5. Android Debug
6. Android Emulator Recovery
7. LIFEOS Product Gold

## Authority invariant

`receipt != observation != success != causation`

Validation head is restacked on B410 GOLD main; all four required exact-head gates must pass before merge.
