# B408 — App Intent / Deep-Link Runtime — exact implementation plan

Base prepared head: `ada72adac986cc6be5f0d6cda09d8f8b0fb08085`
Branch: `b408-app-intent-deeplink-runtime-v1`

## Pre-implementation audit

1. The app currently exposes only the launcher activity; there is no generic external deep-link receiver in the manifest.
2. Existing `LocalShareIntentFactory` already constructs a narrow ACTION_SEND handoff and uses FileProvider for image sharing. B408 must not replace or widen this share path; B410 will own generalized share/clipboard/document flows.
3. Existing Owner Policy already contains `OwnerEffectType.EXTERNAL_APP_HANDOFF`. B408 reuses that effect instead of adding another generic launch authority.
4. B405 is the capability-selection boundary. B408 requires a typed B405 dispatch plan for `app.intent.launch`, `app.deeplink.open`, or `app.intent.resolve`.
5. Android package resolution and final `startActivity` are host concerns in `:app`; `:core:runtime-android` remains platform-neutral.
6. No generic JavaScript, shell, arbitrary component reflection, raw Intent flags or unbounded extras surface is introduced.

## Capability surface

- `app.intent.resolve`
  - observation-only bounded target resolution.
  - resolution never grants launch authority.
- `app.intent.launch`
  - action from a bounded allowlist,
  - optional exact package,
  - bounded primitive extras only.
- `app.deeplink.open`
  - HTTPS plus explicitly registered bounded custom schemes,
  - canonical URI identity,
  - optional exact package binding,
  - no `file://`,
  - no implicit content-URI grants.

## Core runtime

### NEW — `core/runtime-android/.../AppIntentRuntime.kt`

- typed `AppIntentAction`
- bounded `AppIntentExtra`
- `AppIntentTarget`
- `AppDeepLink`
- `AppIntentRequest`
- `AppIntentResolution`
- `AppIntentLaunchReceipt`
- `AppIntentHost`
- `AppIntentRuntime`

The runtime:
- requires an exact B405 capability-plan match,
- canonicalizes URI/package identity,
- caps extras/count/string sizes,
- separates resolution from execution,
- snapshots the exact resolved target,
- re-resolves immediately before exposure,
- fails closed if the target changed,
- invokes the host launch only inside `OwnerPolicyEffectGate.expose()` under `EXTERNAL_APP_HANDOFF`.

## Android host

### NEW — `app/.../AndroidAppIntentHost.kt`

Uses `PackageManager` and `Intent` only.

Invariants:
- trusted runtime generates action/category/extras;
- no caller-supplied raw flags;
- no `FLAG_GRANT_WRITE_URI_PERMISSION`;
- no `file://` URI;
- content URI grants stay with B410;
- explicit package requests never fall back silently;
- only exported/enabled activities are considered;
- successful launch returns the exact component/package/action/URI receipt.

## Tests

- unsupported scheme rejected.
- `file://` rejected.
- oversized extras rejected.
- resolution has no execution authority.
- exact-package request cannot fall back.
- target change between prepare and exposure fails closed.
- absent/revoked Owner Policy prevents host launch.
- successful launch returns exact target identity.
- existing `LocalShareIntentFactory` remains untouched.

## Gates

1. `:core:runtime-android:test`
2. `:app:testDebugUnitTest`
3. Core Fast
4. Android Debug
5. Android Emulator Recovery
6. LIFEOS Product Gold

## Authority invariant

`intent syntax != resolvable target != owner authorization != Android launch != external-app outcome`
