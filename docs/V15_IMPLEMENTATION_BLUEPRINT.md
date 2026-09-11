# V15 — Explainability and Decision Trace implementation blueprint

Status: preparation only. Productive V15 implementation begins after V14/#164 is accepted with real execution evidence.

## Design invariants

- Explanations are reconstructed from durable evidence and IDs, never post-hoc invented prose.
- One stable DecisionTrace root follows a goal/effect across plan, convergence, routing, policy, resources, execution and outcome.
- Existing IDs remain authoritative. V15 links to them; it does not replace them.
- Trace projection is read-only and must have no owner-policy, provider, resource-settlement or host-effect mutation authority.
- Facts, policy constraints, resource constraints, hypotheses, uncertainty, blocked alternatives and outcomes are typed distinctly.
- Restart reconstruction must be deterministic and revision-bound.

## Planned files

### New runtime package
`core/runtime/src/main/kotlin/app/lifeos/core/runtime/trace/`

- `DecisionTraceId.kt`
- `DecisionTrace.kt`
- `DecisionTraceNode.kt`
- `DecisionTraceLink.kt`
- `DecisionTraceRepository.kt`
- `DecisionTraceLedger.kt`
- `DecisionTraceCoverage.kt`
- `DecisionTraceProjector.kt`

### New encrypted data package
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
- same durable source chain reconstructs identical graph after restart
- changed source revision produces a distinct bound node
- blocked alternatives and exact reasons remain visible
- unresolved convergence cannot be projected as certainty
- policy simulation/live correlation does not imply execution success
- external delivery/provider activation only appears successful after authoritative evidence
- projector cannot mutate owner policy, providers, resources or host effects
- corrupted trace storage fails closed for explanation without granting or changing productive authority

## Acceptance

V15 follows #165. Acceptance evidence must be recorded in #174. CI blocker #173 must be cleared before this branch can be considered accepted.

Related planning: #175.