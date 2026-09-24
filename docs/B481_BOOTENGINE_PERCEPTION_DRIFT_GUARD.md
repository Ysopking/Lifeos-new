# B481 — BootEngine Perception Drift Guard

B481 closes the time-of-check/time-of-use gap around the B479/B480 perception binding.

For bound productive cycles, the BootEngine checks the live personal-context binding at cycle start,
before WorldFormula evaluation, before productive publication, and during recovery. Drift fails the
uncommitted cycle closed. If the ProductiveWorldHead was already committed before a process death,
recovery finalizes that exact committed cycle instead of rewriting history.

Legacy unbound cycles and runtimes without a live binding source retain prior behavior.

Hard invariants:

- frozen perception context cannot drift inside an active productive cycle
- stale observation-policy state cannot reach productive WorldFormula publication
- revocation or sensor/context change is fail-closed before commit
- already-committed world state is recovered exactly
- perception binding still grants no observation or effect authority
