# B471 — World-Grounded Reference Resolution

B471 adds an explicit grounding check after the existing CoreferenceResolverV4.

The existing resolver may rank a reference from discourse/context, including a legacy id-only
compatibility path. B471 does not replace that resolver. It validates whether the selected reference
is bound to the **exact Photon revision that participated in the current LanguageContext**.

Grounding states:

- EXACT_REVISION
- AMBIGUOUS
- STALE_REVISION
- OUTSIDE_CONTEXT
- LEGACY_ID_ONLY
- MISSING

A close runner-up remains AMBIGUOUS even when the selected revision exists in context. A legacy
Photon id without revision never becomes exact grounding.

This gives later StateSufficiency/PerceptionGap logic a deterministic signal for whether words such
as "das", "sie", "der Termin danach" or "die letzte Datei" are actually grounded rather than merely
ranked.

Hard invariants:

- reference ranking != exact world grounding
- Photon id != Photon revision
- stale revision stays explicit
- ambiguity is not forced into a winner
- grounding cannot mutate Personal World
- grounding grants no execution authority
