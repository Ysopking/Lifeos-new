# B455 — State Contract + Sufficiency

Parent exact head: `0bb4ba65ced48fa19ee7cfa153fbd56ecb157ff2`

B455 makes "enough information" an explicit deterministic contract instead of a confidence
threshold.

A StateContract names required dimensions, minimum observation authority, freshness, evidence count
and conflict tolerance. StateSufficiencyDetector returns four mutually exclusive dimension classes:
satisfied, missing, stale and conflicted.

Hard invariant: missing or stale state cannot be silently completed by inference.
