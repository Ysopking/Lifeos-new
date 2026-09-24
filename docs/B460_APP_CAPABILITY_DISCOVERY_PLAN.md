# B460 — App Capability Discovery

Parent exact head: `20268c96d5b42659dd1bcba7fffb4d0d2fe66369`

B460 is deliberately split at the platform boundary. The core classifier never enumerates installed
apps or requests access. It receives explicit, already-authorized AppSurfaceEvidence and applies
declarative semantic discovery rules.

```text
authorized platform observation
 -> AppSurfaceEvidence
 -> AppCapabilityDiscoveryClassifier
 -> UniversalAppCapabilityCandidate
 -> B461 validation
 -> canonical CapabilityRegistry
```

A match is still only a non-authoritative candidate. Surface existence does not mean Owner intent,
permission, execution authority or active capability membership.
