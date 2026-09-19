# LIFEOS Next

Private, offline-first Android runtime built around persistent **Photons**, typed **Force Fields** and durable cognitive state. The current APK includes encrypted Photon memory, ThoughtMatrix/ThoughtGraph processing, structured language/goal routing, long-horizon goal state, DeepSearch, generated-tool lifecycle, controlled evolution, self-healing, WorldFormula/WorldEquation evaluation and restart recovery.

The language/cognition path is local and deterministic. LIFEOS does not claim that an external or generative LLM is embedded in the APK, and capability gaps remain explicit instead of being silently simulated.

## Modules and user value

| Module | Responsibility | APK benefit |
| --- | --- | --- |
| `app` | Android process composition, Compose UI, startup DAG, local device adapters and product actions | Stable process startup, chat/action routing, local reminders/image flows and device recovery gates |
| `core:model` | Photon identities, revisions, provenance, relations and binary codecs | Durable information units with explicit lineage and revision safety |
| `core:data` | Encrypted repositories, Android Keystore, AES-GCM, AtomicFile and recovery reports | Private durable state with bounded reads, corruption detection and path-bound persistence for hardened vaults |
| `core:field` | Typed field/world graph primitives and deterministic field dynamics | Shared representation for evidence, attraction/repulsion, conflicts and convergence |
| `core:language` | Deterministic language understanding, semantic action graphs and reference resolution | Local intent/goal extraction without inventing unavailable capabilities |
| `core:runtime` | Cognition, goals, workers, DeepSearch, ToolWorkshop, evolution, self-healing, WorldFormula and resource intelligence | Durable execution, explicit authority boundaries, recovery and evidence-bound lifecycle transitions |
| `host:buildstudio` | Isolated candidate-build host contracts | Mutation candidates can be built and evidenced without granting productive activation |

Android may still terminate the process. LIFEOS therefore treats cold-start rehydration and durable recovery as product requirements rather than claiming an always-resident background process.

## Build and validation

Requires JDK 17, Android SDK 37, Gradle 9.3.1 and AGP 9.1.1. Target SDK remains 36; minimum SDK is 26. Dependencies are kept on the existing project versions.

```bash
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease
```

CI uploads `LIFEOS-Next-debug` and `LIFEOS-validation`. Release compilation verifies R8/resource shrinking, but the release APK is unsigned until a signing setup is provided. No credentials, signing keys or user content belong in this repository.

## Device checks still required

- Install/update the APK; save thoughts, rotate while entering/saving, then restart the app.
- Check search, text selection, keyboard visibility, large font and dark mode.
- Check Keystore access and recovery following an interrupted write on a device/emulator.
- Validate accessibility with TalkBack and measure startup/scrolling with a realistically large vault.

## Review coverage

All source, test, manifest, resource, Gradle, CI and repository documentation files were reviewed in this optimization pass. Files with no concrete required change were retained. New automated checks cover field failure isolation, cancellation/restart, stale revisions, numeric validation and malformed UTF-8, alongside existing codec compatibility tests. This is a functional optimization pass, not a measured battery/performance benchmark or complete security audit.
