# F1 Unified App Shell — double review

Reviewed baseline: `main@65481124cb07dcc2c69cd4cad97eb27b221b1c43`
Plan commit: `1fb2415225ad62482c065608ad744e379121ae6d`

## Review A — source and dependency audit

Checked:
- `ChatMainActivity.kt` launcher lifecycle and permission request.
- `AndroidManifest.xml` launcher ownership.
- `app/build.gradle.kts` Compose/Material3 dependencies.
- `MainActivity.kt` legacy productive thought/voice/image/tool UI.
- `ProductGoldenChatDeviceTest.kt` product/cold-restart contract.

Findings:
1. No additional navigation dependency is required. Material3 already supplies `Scaffold`, `NavigationBar` and `NavigationBarItem`.
2. `ChatMainActivity` must remain the launcher; Product Gold cold-start explicitly starts `.ChatMainActivity`.
3. `MainActivity` must remain present during F1 because it still contains productive UI paths that are migrated only in F4/F5/F8.
4. F1 may safely move the current chat composables without changing chat persistence because Product Gold asserts kernel/Photon behavior rather than a fixed Compose hierarchy.

Decision: PASS with amendments below.

## Review B — lifecycle, state and regression audit

Risks found:
1. Saving an enum instance directly as navigation state unnecessarily couples state restoration to enum serialization.
2. Without explicit back handling, pressing Android Back from MEMORY/GOALS/SYSTEM would exit the Activity instead of returning to the primary CHAT surface.
3. Adding a root `Scaffold` while leaving `safeDrawingPadding()` in the moved chat could double-apply system insets.
4. Navigation glyphs must not be the sole readable destination indicator; each item must also render a text label.

Amended implementation contract:
- Store the selected destination as its stable String `key`, resolve through `LifeOsDestination.fromKey`, and fall back fail-safe to `CHAT`.
- Install `BackHandler(enabled = selected != CHAT)` that returns to `CHAT`.
- Root `Scaffold` owns system bars; moved chat owns only screen spacing and `imePadding()`.
- Every `NavigationBarItem` renders both glyph and visible label.
- Keep `LifeOsChatViewModel` Activity-scoped so tab switching cannot recreate the kernel or chat state.

Decision: PASS after amendments.

## F1 implementation gate

Implementation may start only with the amended contract above. Required checks:
- destination keys are unique;
- order is exactly CHAT, MEMORY, GOALS, SYSTEM;
- unknown restored key resolves to CHAT;
- launcher manifest unchanged;
- JVM tests, lint and debug APK build pass;
- Recovery/Product Gold remain green before merge because launcher/product behavior changes.
