# LIFEOS Next

Offline Android notebook built around persistent **Photons** and typed **Force Fields**.
The current APK captures, encrypts, restores and searches local thoughts. It does not yet provide an AI chat model, import/export, background learning or a complete replacement for the old LIFEOS app.

## Modules and user value

| Module | Responsibility | APK benefit |
| --- | --- | --- |
| `app` | Activity-owned ViewModel, lifecycle-aware Compose state, searchable thought list | Rotation keeps the session and draft; thoughts can be searched and copied; storage errors can be retried |
| `core:model` | Photon invariants and versioned binary codec | Finite numeric values; full graph metadata and long UTF-8 texts; version-1 compatibility |
| `core:data` | AES-GCM, Android Keystore, AtomicFile and serialized IO | Private storage; interrupted-write recovery; unreadable files reported and preserved |
| `core:runtime` | Event-driven worker and revision-aware thought matrix | No polling when idle; one failing field does not skip other fields; stale revisions cannot overwrite new state |

The ViewModel survives configuration changes and stops its worker when cleared. Saved thoughts survive process restarts; an unsaved draft currently survives rotation only. The app does not claim to run continuously after Android terminates its process.

The last 100 field influences are kept in memory for runtime diagnostics. Persistent influence history, conflict-aware storage revisions and large-collection paging remain future work. The matrix still copies its map when publishing a changed snapshot; incremental energy accounting removes the additional full energy scan.

## Build and validation

Requires JDK 17, Android SDK 37, Gradle 9.3.1 and AGP 9.1.1. Target SDK remains 36; minimum SDK is 26. Dependencies are kept on the existing project versions.

```bash
gradle test :app:lintDebug :app:assembleDebug :app:assembleRelease
```

CI uploads `LIFEOS-Next-debug` and `LIFEOS-validation`. Release compilation verifies R8/resource shrinking, but the release APK is unsigned until a signing setup is provided. No credentials, signing keys or user content belong in this repository.

## Device checks still required

- Install/update the APK; save thoughts, rotate while entering/saving, then restart the app.
- Check search, text selection, keyboard visibility, large font and dark mode.
- Check Keystore access and recovery following an interrupted write on a device/emulator.
- Validate accessibility with TalkBack and measure startup/scrolling with a realistically large vault.

## Review coverage

All source, test, manifest, resource, Gradle, CI and repository documentation files were reviewed in this optimization pass. Files with no concrete required change were retained. New automated checks cover field failure isolation, cancellation/restart, stale revisions, numeric validation and malformed UTF-8, alongside existing codec compatibility tests. This is a functional optimization pass, not a measured battery/performance benchmark or complete security audit.
