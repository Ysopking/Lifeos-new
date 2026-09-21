# B402 — WebSessionVault — exact implementation plan

Base head: 5988eba178e592442c093f4dc503009c395dc044
Branch: b402-web-session-vault-v1
Modules: :core:runtime-web + :core:data

## Pre-implementation audit

Verified before writing:
1. B391 already provides canonical HTTPS WebOriginIdentity/WebOriginId; B402 reuses it and does not invent a second origin model.
2. :core:runtime-web is a pure JVM module depending only on :core:runtime-contracts; session contracts/codecs remain there.
3. :core:data already owns the Android unified vault: UnifiedVault, VersionedPathBoundVaultSupport and VaultAssociatedData.
4. VersionedPathBoundVaultSupport requires non-empty AES-GCM associated data and is the existing compatibility adapter for path-bound encrypted persistence.
5. Existing encrypted repositories bind logical id/revision to the physical file path and verify after write; B402 follows that pattern instead of creating a new crypto stack.
6. Direct path-collision checks found no existing WebSessionVault.kt, WebSessionVaultTest.kt or EncryptedWebSessionRepository.kt on the B401 head.
7. No network, cookie application, Authorization-header construction, browser-storage mutation, login flow, permission grant or execution authority is introduced in B402.

## Production code

NEW core/runtime-web/src/main/kotlin/app/lifeos/core/runtime/web/WebSessionVault.kt

- WebSessionSlot: bounded canonical slot name for multiple sessions on one origin.
- WebSessionId: stable origin+slot identity; never derived from secret contents.
- WebSessionRevisionId: stable session+revision identity; never derived from secret contents.
- WebSessionSecret: defensive-copy opaque secret wrapper with redacted toString.
- WebSessionEntryKind: COOKIE / AUTHORIZATION only.
- WebSessionEntry: bounded name/path/expiry/HTTPS-only metadata plus opaque secret; no authority.
- WebSessionSnapshot: immutable contiguous revision with exact predecessor and canonical entry order.
- WebSessionWriteResult / WebSessionLoadReport / WebSessionRepository: storage contract only.
- WebSessionCodec: bounded deterministic binary codec. Secret bytes exist only in the plaintext payload that is handed to the encrypted vault.

NEW core/data/src/main/kotlin/app/lifeos/core/data/web/EncryptedWebSessionRepository.kt

- Reuses VersionedPathBoundVaultSupport; no second keystore or cipher implementation.
- Physical path: web-session-vault/sessions/<sha256(session-id)>/revision-<20-digit>--<sha256(revision-id)>.wsession
- AES-GCM AAD domain lifeos.web.session.v1 binds ciphertext to the exact physical path.
- save is persistence-before-return, idempotent for exact duplicates, and fails closed on same-revision content collision.
- revision/predecessor continuity is revalidated from persisted history.
- loadLatest reads the exact session directory only.
- loadReport surfaces unreadable/corrupt entries without silently dropping them.
- decoded session id and revision id must match the physical path.

MOD core/data/build.gradle.kts
- add implementation(project(":core:runtime-web")).

MOD .github/architecture-budget.json
- register the new :core:data -> :core:runtime-web dependency edge.

## Tests

NEW core/runtime-web/src/test/kotlin/app/lifeos/core/runtime/web/WebSessionVaultTest.kt

- secret input/output is defensively copied and toString is redacted.
- session/revision ids are origin/slot/revision bound and do not change merely because secret bytes differ.
- revision predecessor is contiguous and fail-closed.
- entry ordering is canonical and duplicate entry identities are rejected.
- codec round trip preserves opaque material and all authority flags stay false.
- trailing payload bytes and cross-origin predecessor substitution fail closed.

Existing core:data security tests continue to cover versioned AES-GCM wire layout, path-bound AAD relocation rejection and codec-version mismatch.

## Gate

1. ./gradlew :core:runtime-web:test --tests 'app.lifeos.core.runtime.web.WebSessionVaultTest'
2. ./gradlew :core:runtime-web:test
3. ./gradlew :core:data:testDebugUnitTest
4. Core Fast
5. Android Debug
6. Android Emulator Recovery
7. LIFEOS Product Gold

## Non-goals

B402 does not attach session material to WebAcquisitionTransport, does not navigate or submit forms, does not upload/download files, does not perform login, and does not grant network/permission/execution authority. Those remain later browser-action integration blocks.
