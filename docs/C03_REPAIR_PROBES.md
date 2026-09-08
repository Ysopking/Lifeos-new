# C03 — Repair probes

C03 adds read-only repair verification to the existing Health/Recovery foundation.

## Invariants

- A probe observes; it never mutates, repairs, releases quarantine, or activates generated code.
- Store, worker, field, and capability/tool observations remain explicitly typed.
- Probe facts are bounded and canonically ordered before entering composite evidence.
- Probe exceptions become `UNAVAILABLE` evidence, never false health.
- Cancellation propagates.
- `RecoveryCoordinator` requires at least one verification probe.
- A `RecoveryActionResult.Success` is provisional until all verification probes are `HEALTHY`.
- Quarantine is released only after verified healthy evidence.
- Failed verification can fall through to a later recovery action.
- Exhausted recovery retains the last composite evidence when available.

This block does not add autonomous mutation, hot-swap, build activation, or destructive repair.
