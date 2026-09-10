# E03 Worker lifecycle invariants

This block establishes the only policy boundary that later Hot-Swap and Evolution code may use to manipulate worker lifecycles.

## Durable ownership

- `TaskRepository` / `TaskSnapshotRepository` remains authoritative for task ownership and lease expiry.
- `WorkerRegistry` contains runtime topology and load projections only.
- `WorkerLifecycleSupervisor` never clears, transfers, fabricates, or force-expires a durable lease.
- A physical worker stop is legal only after a complete durable lease inspection proves zero owned tasks.
- Any unreadable task-store entry blocks stop and promotion decisions that depend on ownership certainty.

## Health and heartbeat

- `HealthGraph` remains authoritative for worker health.
- Heartbeats are reconciled against durable lease ownership before registry load/state is updated.
- Reported load mismatches and capacity violations produce explicit lifecycle signals and health failures.
- Out-of-order heartbeats are ignored.
- Stale heartbeats produce a timeout signal; they do not mutate durable lease truth.

## Replacement and canary

- Same-ID implementation replacement requires the incumbent registry entry to be fully `STOPPED`.
- Canary deployments use a distinct worker ID and must cover the incumbent capabilities.
- Canary promotion requires the incumbent to be stopped and the canary to be runnable and `HEALTHY`.
- Promotion changes deployment roles only; it does not implicitly stop the incumbent or transfer leases.

## Cancellation and failure containment

- Coroutine cancellation propagates across the lifecycle boundary.
- Controller failures are converted into explicit lifecycle results, HealthGraph observations, and escalation signals where safe.
- No force-stop API exists in this boundary.
