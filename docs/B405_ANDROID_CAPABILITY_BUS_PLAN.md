# B405 — AndroidCapabilityBus — exact implementation plan

Base: exact merged B404/main `d129cb719fea6653711218fbac6883df7f4d4fb7`
Branch: \`b405-android-capability-bus-v1\`

## Pre-implementation audit

1. Existing generic capability identity/provider contracts already live in \`:core:runtime-contracts\`; B405 reuses them.
2. Existing \`CapabilityRegistry\` remains the authoritative mutable provider registry; B405 consumes only the read-only \`CapabilityProviderCatalog\` view and creates no second registry.
3. Existing \`OwnerPolicyEffectGate\` remains the only productive JIT authorization boundary. B405 records the required owner effect/scope but does not evaluate grants or execute effects.
4. Existing app \`PermissionProfile\` remains the Android-host permission acquisition/evaluation layer. B405 models permission requirements generically and never requests permissions itself.
5. No \`:core:runtime-android\` module, B405 branch or B405 PR existed at scan time.
6. B406-B410 will supply concrete file/calendar/contact/intent/notification/share handlers; B411 owns the cross-action receipt graph. B405 therefore remains a deterministic routing/contract layer.

## Files

NEW \`core/runtime-android/build.gradle.kts\`
- JVM 17 module.
- depends on \`:core:runtime-contracts\` and \`:core:runtime\`.
- test dependency on Kotlin test + coroutines-test.

NEW \`core/runtime-android/src/main/kotlin/app/lifeos/core/runtime/android/AndroidCapabilityBus.kt\`
- risk, reversibility, permission and recovery metadata.
- immutable \`AndroidCapabilityBinding\` over existing \`CapabilityDescriptor\`.
- explicit \`AndroidCapabilityRequest\` and permission snapshot.
- deterministic resolution against existing ranked \`CapabilityProviderCatalog\`.
- exact-provider requests never silently fall back.
- explicit unavailable / contract mismatch / permission-missing outcomes.
- ready dispatch plans grant no execution, permission or Owner Policy authority.

NEW \`core/runtime-android/src/test/kotlin/app/lifeos/core/runtime/android/AndroidCapabilityBusTest.kt\`
- ranked provider selection.
- missing-permission fail-closed behavior.
- contract mismatch.
- exact-provider no-fallback.
- metadata-bound identity.
- stale binding cannot resurrect an unavailable provider.

MOD \`settings.gradle.kts\`
- register \`:core:runtime-android\`.

MOD \`.github/architecture-budget.json\`
- register the exact B405 module dependency edge.

MOD \`.github/scripts/ci-core-fast.sh\`
- add \`:core:runtime-android:test\`.

MOD \`.github/scripts/ci-runtime-module-boundary-contract.sh\`
- require the module, settings registration and Core Fast test gate.

## Security / authority boundary

B405 does **not**:
- call Android APIs,
- request or grant permissions,
- evaluate Owner Policy,
- execute a capability,
- launch an app,
- read/write a file,
- change calendar/contact state,
- consume notifications,
- persist external-action receipts.

The B405 output is only a typed dispatch plan. Productive execution begins in B406+ and must be wrapped at the exact host effect boundary by the existing JIT OwnerPolicyEffectGate.

## Gates

1. \`./gradlew :core:runtime-android:test\`
2. Core Fast
3. Android Debug
4. Android Emulator Recovery
5. LIFEOS Product Gold
