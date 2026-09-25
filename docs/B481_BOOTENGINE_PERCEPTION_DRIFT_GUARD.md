# B481 — BootEngine Perception Drift Guard

B481 closes the time-of-check/time-of-use gap around the productive perception binding established by B479/B480.

For bound productive cycles, BootEngine validates the live perception boundary before cycle creation, before WorldFormula evaluation, before productive world publication, and during recovery. The app-level validator compares the frozen sensor-registry fingerprint and durable Owner Observation Policy revision with the current process state. A mismatch fail-closes the still-uncommitted cycle.

The PersonalContext snapshot lineage remains frozen with the cycle's ThoughtGraph working set. B481 does not reinterpret that frozen lineage as live world state and does not manufacture new observation evidence.

If a ProductiveWorldHead was already durably published before a process death, recovery finalizes that exact committed cycle before considering later perception drift. Published history is never rewritten as if it had not committed.

Legacy unbound cycles and runtimes without a validator retain prior behavior.

Hard invariants:

- frozen productive perception context cannot silently drift across an active cycle
- stale sensor-registry or Owner Observation Policy state cannot reach productive WorldFormula publication
- perception drift is fail-closed before evaluation, before commit, and for uncommitted recovery
- already-published productive world state is recovered exactly
- perception binding grants no observation authority
- perception binding grants no effect authority
- no new core/runtime production Kotlin file is introduced
