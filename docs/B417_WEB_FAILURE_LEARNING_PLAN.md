# B417 — Web Failure Learning

B417 turns exact Web failure evidence into bounded **next-cycle** learning candidates. It does not retry, authenticate, grant permissions or mutate the current action plan.

## Evidence classes

- HTTP 401 → authentication-required hypothesis
- HTTP 403 → authorization-denied hypothesis
- other 4xx/5xx → bounded client/server failure hypothesis
- redirect to login/sign-in/OAuth-shaped path → login-flow hypothesis
- changed expected final redirect target → redirect-topology hypothesis
- explicit expected-vs-observed response-schema fingerprint mismatch → schema-change hypothesis

## Existing authorities reused

B392 remains the source of canonical Web resource identity, redirect chain and acquisition receipt. Owner Policy remains the only permission authority. B416 remains the generic external-action outcome learning layer.

## Hard invariants

`HTTP status != root cause`

`redirect != permission`

`schema mismatch != server truth`

`failure learning != automatic retry != credential use != Owner Policy != execution`

All candidates are next-cycle only and have no truth, causal, credential, policy, promotion or execution authority.
