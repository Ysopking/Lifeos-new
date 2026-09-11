# V16 — Resource Intelligence Completion implementation blueprint

Status: preparation only. Productive V16 implementation begins after V15 trace identities are implemented and accepted.

## Existing foundation to extend

Do not create a second resource system. Extend:

- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/resource/`
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/world/`
- `core/data/src/main/kotlin/app/lifeos/core/data/resource/`
- `core/data/src/main/kotlin/app/lifeos/core/data/world/`
- `app/src/main/java/app/lifeos/next/kernel/HardwareResourceIntelligenceRuntime.kt`

Existing goal execution, ToolWorkshop and hot-swap/revert resource gates remain the foundation.

## Required new bindings

- `ResourceExecutionBinding`: V15 DecisionTraceId + domain + operation ID + account + reservation + authoritative outcome/checkpoint ID.
- `ExecutionMeasurement`: elapsed time, work units, memory estimate/peak, I/O, network, thermal/battery context, outcome and observable utility.
- `SoftCostEstimate`: learned future estimate only.
- conservative unknown-operation estimate.
- `ResourceFairnessState`: bounded starvation/fairness history.
- restart `ResourceReservationReconciler`.

## Domain completion order

1. Goal Execution
2. Cognition/workers
3. DeepSearch
4. ToolWorkshop
5. Evolution
6. Hot-Swap/Revert
7. Self-Healing
8. Collaborative Artifacts
9. Background Learning

## Per-domain contract

- Allocate/reserve before meaningful resource-consuming work.
- Bind reservation to durable operation identity and V15 trace.
- Persist authoritative checkpoint/outcome before final settlement.
- Release reservations when blocked before authoritative work completes.
- On restart reconcile against source-of-truth execution state.
- Never infer completion from spend alone.
- Never let learned values increase owner/system hard ceilings.

## Arbitration inputs

Soft allocation may consider:

- relevance
- urgency/deadline
- expected utility
- confidence
- health
- starvation/fairness history
- measured historical cost

Hard quotas, global ceilings, domain ceilings and thermal emergency suspension remain non-negotiable.

## Learning rules

- Measurements affect only future soft estimates/allocation.
- Unknown operations/providers/strategies start conservative.
- Provider reliability may affect expected utility, not capacity creation.
- Learned estimates cannot mutate hard quotas.
- Thermal/battery pressure may reduce or suspend work but cannot corrupt durable state.

## Tests

- all significant domains enumerated by a coverage contract
- no reservation => no resource-consuming stage
- crash after reserve/before work => recover/release once
- crash after durable outcome/before settle => settle once after restart
- committed reservation never double-charged
- released reservation never resurrected
- corrupted resource ledger fails closed
- learned model cannot raise hard limits
- unknown operation gets conservative estimate
- fairness prevents indefinite starvation while preserving critical priority
- every allocation/exhaustion decision links into V15 trace with exact reason

## Acceptance

V16 follows #166 and blueprint #176. Acceptance evidence belongs in #174. Runner blocker #173 must be cleared before acceptance.