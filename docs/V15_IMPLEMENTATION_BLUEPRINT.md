# V15 — Explainability and Decision Trace implementation blueprint

Status: productive implementation active on `v15/explainability-trace`, stacked on merged V14 (`734862ea2bcc370772109d764611df2b1feb98ad`).

## Design invariants

- Explanations are reconstructed from durable evidence and IDs, never post-hoc invented prose.
- One stable DecisionTrace root follows a goal/effect across plan, convergence, routing, policy, resources, execution and outcome.
- Existing IDs remain authoritative. V15 links to them; it does not replace them.
- Trace projection is read-only and must have no owner-policy, provider, resource-settlement or host-effect mutation authority.
- Facts, policy constraints, resource constraints, hypotheses, uncertainty, blocked alternatives and outcomes are typed distinctly.
- Restart reconstruction must be deterministic and revision-bound.
- DecisionTrace identity is exact and case-sensitive. It uses its own length-prefixed SHA-256 fingerprint and must not reuse semantic field-ID canonicalization (`trim().lowercase()`).

## Implemented runtime package
`core/runtime/src/main/kotlin/app/lifeos/core/runtime/trace/`

- `DecisionTraceModels.kt`
- `DecisionTraceLedger.kt`
- deterministic exact `DecisionTraceId`
- revision-bound `DecisionTraceNodeId`
- immutable typed graph/link validation
- CAS-backed append and deterministic restart reconstruction
- read-only `DecisionTraceProjector`

## Planned encrypted data package
`core/data/src/main/kotlin/app/lifeos/core/data/trace/`

- `EncryptedDecisionTraceRepository.kt`
- versioned bounded codec and load report

## Integration order

1. Goal/plan identities and V5 convergence decisions.
2. Capability routing/provider selection.
3. V14 OwnerPolicyDecisionId + typed reason codes.
4. V16 budget account/reservation/allocation identities.
5. Authoritative execution outcome.
6. DeepSearch mission/checkpoint/source decisions.
7. ToolWorkshop stages, evolution and hot-swap/revert.
8. Collaborative artifact revisions/contributions/reentry.
9. Health/self-healing evidence and recovery decisions.

## Trace node types

- OBSERVED_FACT
- POLICY_CONSTRAINT
- RESOURCE_CONSTRAINT
- INFERRED_HYPOTHESIS
- UNRESOLVED_UNCERTAINTY
- CANDIDATE_ALTERNATIVE
- REJECTION
- SELECTION
- EXECUTION_OUTCOME
- RECOVERY_OUTCOME

Each node stores only immutable IDs/revisions, reason codes and bounded display metadata required for projection.

## Tests

- exact case-sensitive DecisionTrace identity
- structural tuple separation (`ab,c` != `a,bc`)
- exact whitespace preservation in identity
- same durable source chain reconstructs identical graph after restart
- changed source revision produces a distinct bound node
- blocked alternatives and exact reasons remain visible
- unresolved convergence cannot be projected as certainty
- policy simulation/live correlation does not imply execution success
- external delivery/provider activation only appears successful after authoritative evidence
- projector cannot mutate owner policy, providers, resources or host effects
- corrupted trace storage fails closed for explanation without granting or changing productive authority

## Local build evidence — 2026-09-11

Executed in the current chat build environment with `kotlinc-jvm 1.9.0` on JRE 21:

- DecisionTrace models compile: PASS
- case-sensitive identity (`Goal` != `goal`): PASS
- length-prefixed structural separation: PASS
- exact whitespace identity: PASS
- deterministic node identity: PASS
- DecisionTraceLedger compile: PASS
- append/restart reconstruction: PASS
- idempotent node merge: PASS
- unreadable-store fail-closed behavior: PASS

This is real targeted local Kotlin execution. It is not a substitute for the full repository Gradle, Android lint, APK assembly and emulator acceptance gates because the complete private repository checkout is not mounted in this container.

## Full acceptance command when a complete checkout is available here

`gradle test :app:lintDebug :app:assembleDebug --stacktrace`

V15 follows #165. Acceptance evidence must be recorded in #174. CI blocker #173 remains relevant for GitHub-hosted execution.

Related planning: #175.
