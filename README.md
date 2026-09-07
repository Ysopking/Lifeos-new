# LIFEOS Next

Clean-room Android rebuild of LIFEOS. The app is offline-first and models every information unit as a persistent **Photon**. Modules act as typed **Force Fields** and record their influence with provenance instead of mutating knowledge invisibly.

## Architecture

- `app`: Jetpack Compose shell and dependency composition
- `core:model`: immutable photon, relation, provenance and field contracts
- `core:runtime`: always-on delta processing and runtime health state

The startup path is intentionally small. Continuous ingestion, re-evaluation, learning, convergence and health monitoring belong to `CognitiveRuntime`, not to application boot.

## Build

Requires JDK 17, Android SDK 36, Gradle 9.3.1 and Android Gradle Plugin 9.1.1.

```bash
gradle test :app:assembleDebug
```

No credentials, signing keys or user content belong in this repository.
