# LIFEOS Frontend Execution Plan

Baseline: `main@65481124cb07dcc2c69cd4cad97eb27b221b1c43`

This plan is intentionally implementation-oriented. Existing files are bound by baseline physical line ranges plus symbol anchors; new files are assigned exact planned line-ownership ranges. If formatting shifts a physical line later, the named symbol remains authoritative.

Global UI invariants:
1. `ChatMainActivity` remains the exported launcher until a separately verified migration says otherwise.
2. No frontend block may bypass owner policy, resource budgets, encrypted persistence, provenance, or runtime readiness gates.
3. Existing productive chat, voice, image, reminder, sharing, ToolWorkshop and diagnostic paths must remain reachable until their replacement is proven.
4. No block may claim readiness from UI state alone; UI only projects runtime evidence.
5. Every block receives two static reviews before implementation, then JVM/Android/Gold gates after implementation.
6. No release/signing work is part of this frontend program.

## F1 — Unified App Shell and stable navigation

Goal: one launcher-owned Compose shell with stable destinations `CHAT`, `MEMORY`, `GOALS`, `SYSTEM`, while preserving current chat behavior and keeping legacy `MainActivity` intact during migration.

Existing line ownership:
- `app/src/main/java/app/lifeos/next/ChatMainActivity.kt:1-122` / `ChatMainActivity`, `LifeOsChatScreen`, `ChatMessage`, `statusText`.
  - Lines 1-45: keep Activity lifecycle + initial-data permission request; replace only `setContent { LifeOsChatScreen(model) }` with root shell entry.
  - Lines 47-122: move screen/rendering logic unchanged into `ui/chat/LifeOsChatScreen.kt`.
- `app/src/main/AndroidManifest.xml:22-31` / launcher declaration: no behavioral change.

New line ownership:
- `app/src/main/java/app/lifeos/next/ui/LifeOsDestination.kt:1-48`
  - 1-8 package/imports.
  - 9-38 enum entries, stable keys, labels, glyphs.
  - 39-48 ordered destination contract.
- `app/src/main/java/app/lifeos/next/ui/LifeOsRoot.kt:1-120`
  - 1-20 imports.
  - 21-55 `LifeOsRoot` state + MaterialTheme + Scaffold.
  - 56-88 destination content dispatch.
  - 89-120 bottom `NavigationBar`.
- `app/src/main/java/app/lifeos/next/ui/chat/LifeOsChatScreen.kt:1-120`
  - 1-22 imports.
  - 23-83 current chat screen.
  - 84-108 message card.
  - 109-120 boot-status text mapping.
- `app/src/main/java/app/lifeos/next/ui/PlaceholderScreens.kt:1-72`
  - 1-15 imports.
  - 16-34 Memory migration placeholder.
  - 35-52 Goals migration placeholder.
  - 53-72 System migration placeholder.
- `app/src/test/java/app/lifeos/next/ui/LifeOsDestinationTest.kt:1-64`
  - ordering, unique keys, exactly one default destination.

Acceptance:
- launcher still `ChatMainActivity`.
- default route CHAT.
- switching tabs does not recreate kernel/ViewModel.
- current chat send/readiness/error semantics unchanged.
- legacy MainActivity preserved.

## F2 — Modern asynchronous chat composer

Goal: composer stays editable while LIFEOS is processing; explicit send/stop states; stable local optimistic turn projection without weakening durable persistence.

Existing line ownership:
- `LifeOsChatViewModel.kt:25-36` / `LifeOsChatUiState`: replace single `sending` boolean with typed composer/turn processing state while retaining compatibility projection until migration is complete.
- `LifeOsChatViewModel.kt:50-54` / `editDraft`: remove global processing lock; only hard-block when composer itself is unavailable.
- `LifeOsChatViewModel.kt:60-129` / `sendMessage`: split validation, durable user persistence, assistant processing and final state transition.
- `ui/chat/LifeOsChatScreen.kt` F1 lines 23-83: replace trailing Row with dedicated composer.

New line ownership:
- `ui/chat/ChatComposer.kt:1-150`
  - 1-25 model/imports.
  - 26-90 text composer and send action.
  - 91-120 processing/cancel affordance.
  - 121-150 accessibility semantics and helper mapping.
- `ui/chat/ChatProcessingState.kt:1-70`
  - typed IDLE/SUBMITTING/THINKING/PERSISTING/FAILED state and user-facing labels.
- tests `ChatProcessingStateTest.kt:1-110`.

Acceptance:
- draft remains editable while prior turn processes.
- duplicate send of same in-flight turn cannot occur.
- failed processing preserves durable user turn and exposes recovery action.

## F3 — Compact runtime status and health drill-down

Goal: remove subsystem/readiness wall from primary chat and replace with one compact truthful runtime indicator.

Existing line ownership:
- current `LifeOsReadinessCard.kt:1-58`: keep `buildReadinessUiModel`, move full card to System UI.
- `LifeOsChatUiState` topology/readiness fields remain source evidence.
- `ui/chat/LifeOsChatScreen.kt` header: replace raw subsystem counts/full readiness card.

New line ownership:
- `ui/components/LifeOsRuntimeStatus.kt:1-120`
  - 1-35 status model.
  - 36-72 compact indicator.
  - 73-120 drill-down trigger/content contract.
- `ui/system/RuntimeHealthSheet.kt:1-150` full A-P readiness and topology summary.
- tests `LifeOsRuntimeStatusTest.kt:1-120` mapping READY/DEGRADED/FAILED and unavailable counts.

Acceptance:
- chat shows one status line only.
- no green UI state when runtime is failed.
- complete readiness remains owner-visible in System.

## F4 — Multimodal chat: voice and image surfaces

Goal: productive voice and generated-image paths become native chat content instead of living only in legacy thoughts UI.

Existing line ownership:
- `MainActivity.kt:257-300` / voice controls: migrate behavior, do not duplicate policy.
- `MainActivity.kt:409-472` / `PhotonCard`, `GeneratedImageContent`: extract reusable rendering.
- `AndroidVoiceCapture.kt`: unchanged producer contract.

New line ownership:
- `ui/chat/ChatAttachmentAction.kt:1-80` typed attachment/voice actions.
- `ui/chat/ChatVoiceControl.kt:1-140` RECORDING/PROCESSING/IDLE projections.
- `ui/chat/ChatArtifactContent.kt:1-180` image/reference/render failure states.
- `ui/components/GeneratedImageContent.kt:1-120` extracted reusable image renderer.
- Android tests `ChatMultimodalDeviceTest.kt:1-220`.

Acceptance:
- microphone permission requested only on explicit owner action.
- voice processing is local and visible.
- encrypted image reference is decoded through existing productive repository path.

## F5 — Memory workspace

Goal: migrate productive Photon browsing/search from legacy MainActivity into the unified shell.

Existing line ownership:
- `MainActivity.kt:54-102` state/query/filter/date formatting.
- `MainActivity.kt:133-163` search + list.
- `MainActivity.kt:409-472` Photon/reminder/image rendering.
- `LifeOsViewModel.kt` remains the initial memory data source until a focused MemoryViewModel extraction is proven.

New line ownership:
- `ui/memory/MemoryScreen.kt:1-180` search, loading/empty/error and list.
- `ui/memory/MemoryFilter.kt:1-120` deterministic text/tag/type filters.
- `ui/memory/PhotonCard.kt:1-150` common content card.
- `ui/memory/PhotonDetails.kt:1-180` provenance, relations, revision and tags.
- tests `MemoryFilterTest.kt:1-180`.

Acceptance:
- search parity with legacy screen.
- corrupted/unreadable state remains visible and non-destructive.
- provenance never hidden from detail view.

## F6 — Goals and next-actions workspace

Goal: make durable goals/plans and next actions visible without inventing a second planning source of truth.

Existing ownership:
- use existing durable goal-plan runtime/ledger and goal convergence provider; no UI-owned goal state.

New line ownership:
- `ui/goals/GoalsScreen.kt:1-180` active/blocked/completed grouping.
- `ui/goals/GoalCard.kt:1-150` state, progress, current/next step.
- `ui/goals/GoalDetails.kt:1-220` plan steps, blocker, convergence evidence.
- `ui/goals/GoalUiModel.kt:1-160` pure projection from runtime models.
- tests `GoalUiModelTest.kt:1-220`.

Acceptance:
- next action comes from productive planner only.
- unresolved convergence is rendered as unresolved, never guessed.
- no UI mutation bypasses durable goal ledger.

## F7 — Explainability / Why surface

Goal: expose DecisionTrace as user-readable rationale with technical evidence behind progressive disclosure.

Existing ownership:
- `core/runtime/.../trace/*` read-only source.
- no frontend write authority to trace ledger.

New line ownership:
- `ui/explain/WhyUiModel.kt:1-180` fact/constraint/alternative/selection/recovery summary.
- `ui/explain/WhySheet.kt:1-220` readable summary and expandable technical evidence.
- `ui/explain/DecisionTraceTechnicalDetails.kt:1-180` IDs/revisions/reason codes.
- tests `WhyUiModelTest.kt:1-220`.

Acceptance:
- UI does not infer missing reasons.
- unresolved uncertainty stays explicit.
- source revision and reason code available in technical details.

## F8 — Tool Center

Goal: replace developer-heavy ToolWorkshop cards with a bounded owner approval/review surface.

Existing line ownership:
- `MainActivity.kt:176-252` capability gap/TRIAL activation cards.
- `MainActivity.kt:320-400` generated-tool diagnostics.

New line ownership:
- `ui/system/ToolCenterScreen.kt:1-220` gap, trial, active, quarantined groups.
- `ui/system/ToolApprovalCard.kt:1-180` requested capability, risk/exposure, explicit approval.
- `ui/system/ToolTrialCard.kt:1-180` canary/readiness/promotion state.
- `ui/system/ToolEvidenceDetails.kt:1-180` evidence IDs under disclosure.
- tests `ToolCenterUiModelTest.kt:1-200`.

Acceptance:
- generation and activation remain separate owner actions.
- no ACTIVE claim before productive promotion state says ACTIVE.
- quarantine/rejection cannot be visually presented as available.

## F9 — LIFEOS design system and motion

Goal: coherent private-assistant visual language without changing runtime semantics.

New line ownership:
- `ui/theme/LifeOsTheme.kt:1-180` light/dark schemes, typography, shapes.
- `ui/theme/LifeOsTokens.kt:1-140` spacing, elevations, motion durations.
- `ui/components/LifeOsCard.kt:1-100` shared surface.
- `ui/components/LifeOsStateIndicator.kt:1-120` accessible state indicator.

Acceptance:
- system dark mode retained.
- state is never communicated by color alone.
- animations disabled/reduced when system animator scale/reduced motion requires it.

## F10 — Adaptive layout

Goal: phone, landscape and larger-window layouts without duplicating business state.

New line ownership:
- `ui/layout/LifeOsWindowClass.kt:1-100` width classification without external navigation dependency.
- `ui/layout/AdaptiveLifeOsScaffold.kt:1-180` bottom bar vs rail/two-pane.
- screen-specific detail panes kept optional.
- tests `LifeOsWindowClassTest.kt:1-100`.

Acceptance:
- compact width remains one-pane.
- larger widths may show list+detail but share same ViewModel/runtime state.

## F11 — Accessibility and UI contract tests

Goal: semantic labels, touch targets, keyboard/IME behavior and screen-reader-safe state reporting.

Existing ownership:
- audit every `Button`, `TextButton`, `OutlinedTextField`, generated image and status indicator introduced by F1-F10.

New line ownership:
- `ui/accessibility/LifeOsSemantics.kt:1-120` shared semantics/content descriptions.
- Android tests `LifeOsFrontendAccessibilityDeviceTest.kt:1-260` launcher, navigation, composer, health and image semantics.

Acceptance:
- all icon/glyph-only controls have semantic label.
- minimum interactive target preserved by Material components.
- error/status changes have readable text equivalents.

## F12 — Large-history performance and state containment

Goal: keep frontend responsive with large Photon/chat histories and frequent runtime updates.

Existing ownership:
- `LifeOsChatViewModel.observeKernel` currently reprojects full boot Photon list on updates; profile and isolate before optimization.
- Memory filter currently recomputes over full Photon list for each query change.

New line ownership:
- `ui/perf/StableChatProjection.kt:1-160` revision-aware chat projection cache.
- `ui/memory/MemorySearchIndex.kt:1-180` bounded in-memory projection index over already-authorized local Photon metadata.
- tests `StableChatProjectionTest.kt:1-200`, `MemorySearchIndexTest.kt:1-220`.

Acceptance:
- no new persistence store merely for UI caching.
- cache invalidation keyed by stable Photon id/revision.
- cold-start truth remains productive encrypted repositories, never UI cache.

# Cross-block verification order

For each F-block:
1. Review A — source/line/dependency audit against exact branch head.
2. Review B — lifecycle, security, recovery, accessibility and regression audit.
3. Amend plan if either review finds a mismatch.
4. Implement only that block.
5. JVM tests + `:app:lintDebug` + `:app:assembleDebug`.
6. Existing Android Recovery and Product Gold where the block touches launcher/product behavior.
7. Merge only from a final immutable head; repeat main exact-SHA gates after merge.

F1-F3 are structural prerequisites. F4-F8 migrate/productize existing capabilities. F9-F11 harden presentation and accessibility. F12 is measured optimization and must not be used to hide architectural regressions.
