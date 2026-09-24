# B473 — WorldFormula Interpretation Convergence

B473 extends the existing SemanticInterpretationLattice with optional, explicit world evidence.

The language engine still produces its candidates from syntax, semantics, references and linguistic
fields. A caller may then provide world evidence bound to an intent/reference candidate:

```text
linguistic support
+ exact reference support
+ world support
- world contradiction
- insufficient-state penalty
        ↓
SemanticInterpretationLattice
```

World evidence is never invented inside the language module. The overload accepting
`LanguageWorldInterpretationEvidence` exists for a runtime two-pass flow: parse first, evaluate
StateSufficiency/Personal World externally, then refine.

If the leading candidate is backed by insufficient state or materially contradictory world evidence,
the lattice remains unresolved even when its numeric margin would otherwise converge. No forced
winner is produced.

Compatibility rule: with no world evidence, the original v1 scoring/fingerprint path is preserved.

Hard invariants:

- language evidence != world evidence
- world support cannot create effect authority
- contradiction remains explicit
- insufficient state cannot be hidden by a high language score
- no world evidence means the existing language-only behavior remains intact
