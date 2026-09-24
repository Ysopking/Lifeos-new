# B456 — Unified World Gaps

Parent exact head: `df49dfa4362bccfeab5a65f130b38dfce418e64c`

B456 introduces one world-gap vocabulary without replacing the existing CapabilityRegistry or
CapabilityGap model.

Gap classes:

- Perception: required state dimensions are absent or stale.
- Capability: state is known but no compatible usable capability exists.
- Consistency: available evidence contradicts itself.
- Verification: an action exists but the expected realized outcome cannot yet be established.

Hard invariant: contradiction != missing information, and execution receipt != verified outcome.
