# B462 — Semantic Accessibility Boundary

Parent exact head: `f983225310ce88c3e14114f40fd1c9504ecbcba3`

B462 models an authorized accessibility semantic tree as an untrusted PROJECTED observation surface.
The core boundary stores only semantic node identities and content fingerprints, not a coordinate
automation model.

Action candidates are bound to an exact semantic snapshot fingerprint and target node. They have:

- executionAuthority = false
- ownerPolicyAuthority = false
- coordinateFallbackAllowed = false

A productive Android accessibility host, if later installed by the private app, must separately
verify platform permission, Owner Effect Policy, exact precondition state and postcondition
observation. B462 itself performs no Android UI action.
