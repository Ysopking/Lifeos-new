# F4 — Voice + Images directly in Chat

Base: `main@ecb95a968e2c2421e122a409929306af231bc492`

F3 exact-main seal:
- Core Fast #697 PASS
- Android Debug #1055 PASS
- Android Emulator Recovery #403 PASS
- Product Gold #262 PASS
- `candidate_sha == source_head_sha == checkout_sha == ecb95a968e2c2421e122a409929306af231bc492`
- debug APK SHA256 `10bde4cbcceba2522acf7d65664c50ce289afebd5222e663ac0c2f3f2f0d6f25`

Status: line-/symbol-level plan complete; static review A and B complete. Productive implementation begins only after this contract is committed.

## Goal

Make the unified launcher chat the productive multimodal LIFEOS surface by moving the already-existing local microphone path into the F2 composer and rendering generated/transformed encrypted image Photons directly in the chat timeline.

F4 reuses existing productive components. It does not create a second voice runtime, a second image stack, a second kernel, or a second chat send state machine.

## Non-negotiable invariants

1. F2 send serialization remains authoritative: one committed chat turn at a time.
2. Draft editing remains available while LIFEOS is processing and while a voice result is pending.
3. A delayed voice result must never overwrite text typed after recording started.
4. Voice Observation/Recognition Photons remain durable provenance for any accepted speech-derived chat message.
5. The final user chat Photon always carries normal chat/conversation/turn tags so `ConversationProjector` remains deterministic.
6. Raw microphone PCM is not persisted and is cleared/released by the existing `AndroidVoiceCaptureEngine`.
7. Image bytes are read only through `LifeOsKernel.loadImageAsset(...)` / encrypted `BinaryAssetStore`.
8. Image references must decode through `ImageAssetDescriptor`; malformed/missing assets fail closed.
9. Core chat models remain Android-free. Bitmap/image rendering is app-layer projection only.
10. Old `MainActivity` is not deleted in F4; Memory/ToolWorkshop and other legacy surfaces still depend on it.
11. No Internet permission, network dependency, release/signing/AAB work, or Product-Gold claims are added to the APK.

---

# Current-code line map

The ranges below describe the exact pre-F4 base. Symbol anchors are authoritative if later line numbers move during implementation.

## `ChatMainActivity.kt` — current ~lines 1–30

Current responsibilities:
- `RequestMultiplePermissions` launcher for initial-data bootstrap;
- local `LifeOsChatViewModel` creation in `onCreate`;
- `LifeOsRoot(model)` composition;
- initial-data permission bootstrap.

F4 insertion points:
- import `android.Manifest`;
- promote chat ViewModel to an Activity property so the microphone permission callback can address the same instance;
- add a dedicated `RequestPermission()` launcher for `RECORD_AUDIO` beside `initialDataPermissions`;
- pass one `onRequestMicrophonePermission` callback into `LifeOsRoot`;
- preserve the existing initial-data permission launcher exactly.

## `LifeOsChatViewModel.kt` — current ~lines 1–220

Current responsibilities:
- `LifeOsChatUiState.events`, `draft`, F2 `turnProcessing`, F3 health state;
- `editDraft`;
- one serialized `sendMessage` implementation;
- `observeKernel` -> `ConversationProjector.project(boot.photons)`.

F4 insertion/replacement blocks:
- imports: voice/perception/image types + IO/atomic/coroutine utilities;
- state: add `timeline`, `voice`, accepted/staged voice-recognition lineage;
- constructor fields: reuse `owner.multimodalPerception`, instantiate existing `AndroidVoiceCaptureEngine`, add one stop flag and chat image preview loader;
- add voice actions before `sendMessage`;
- amend `sendMessage` Photon construction only; do not fork the F2 persistence/assistant-response state machine;
- replace `events = ConversationProjector.project(...)` with shared `ChatTimelineProjector.project(...)`, while retaining `events` temporarily only if tests/compatibility require it;
- keep F3 topology/readiness mapping untouched;
- add `onCleared` only for voice stop + preview cache cleanup.

## `ChatComposer.kt` — current ~lines 1–80

Current layout:
- editable `OutlinedTextField`;
- one Send button;
- F2 status row.

F4 replacement block:
- keep text field enabled;
- add local mic/stop action adjacent to Send;
- render RECORDING/PROCESSING/status text;
- render staged transcript with explicit `Übernehmen` / `Verwerfen` actions;
- disable Send while voice is RECORDING or PROCESSING;
- disable mic start while a chat turn is in flight;
- never disable draft editing because of voice processing.

## `LifeOsChatScreen.kt` — current ~lines 1–105

Current timeline:
- `LazyColumn` directly iterates `state.events` and calls private `ChatMessage`.

F4 replacement block:
- consume `state.timeline`;
- render text timeline items with existing role alignment;
- render image timeline items through `ChatImageContent`;
- pass voice state/actions into `ChatComposer`;
- pass permission-request callback down from `LifeOsRoot`;
- preserve F3 runtime health modal unchanged.

## `AndroidVoiceCapture.kt`

No productive modification planned.

Existing guarantees reused unchanged:
- foreground-only `AudioRecord`;
- `RECORD_AUDIO` runtime check;
- 16 kHz mono PCM;
- 20-second hard capture limit;
- raw PCM exists only during active capture;
- recorder release in `finally`;
- accumulator buffer zeroed by `clear()`;
- local deterministic acoustic/language field processing.

## Legacy `LifeOsViewModel.kt`

Reference source only in F4. Do not refactor it unless compilation proves unavoidable.

Relevant current blocks:
- state + `VoiceCapturePhase` + image preview types near top;
- voice permission/start/stop around current ~lines 240–310;
- voice-derived save lineage around current ~lines 320–380;
- encrypted image preview loader around current ~lines 450–500;
- `applyVoiceCaptureResult` / `applyVoiceResult` around current ~lines 520–620.

These blocks prove the existing productive semantics but are not the new launcher architecture.

---

# Static review A — Voice ownership, concurrency, provenance

## Existing productive voice path

The legacy path already uses:
- `AndroidVoiceCaptureEngine`;
- `LifeOsApplication.multimodalPerception`;
- `observeSpeech(...)` to persist Observation/Recognition Photon lineage;
- recognition parents on edited multimodal utterances.

F4 must move that capability, not reimplement it.

## Amendment A1 — do not call the legacy `LifeOsViewModel`

The launcher must not instantiate the old ViewModel just to get voice/image functions. Doing so would create two UI state machines over the same process kernel.

F4 uses the existing process singleton through `LifeOsChatViewModel.owner`:

```text
LifeOsChatViewModel
  -> owner.kernel
  -> owner.multimodalPerception
  -> AndroidVoiceCaptureEngine(applicationContext)
```

## Amendment A2 — final speech input still becomes a normal chat-turn Photon

Legacy exact recognition can route a Recognition Photon directly. That is not suitable for the unified chat because `ConversationProjector` expects normal chat role/conversation/turn tags.

F4 therefore always commits one final user chat Photon through the existing F2 `sendMessage` path.

If accepted voice recognitions exist, the final user Photon additionally gets:
- `Provenance.parentIds = recognition Photon IDs`;
- `DERIVED_FROM` relations to those IDs;
- source `lifeos-chat:speech-derived`;
- `perception-derived-utterance`;
- `input:speech` when the accepted transcript remains exact;
- `input:speech-edited` when typed content was already present or the accepted transcript was edited.

It still gets the normal tags:
- `chat`;
- `chat:user`;
- `conversation:default`;
- `turn:<uuid>`.

The call remains `kernel.persistUserUtterance(finalUserPhoton)` and then the existing assistant-response persistence continues unchanged.

## Amendment A3 — no parallel chat commit during voice capture

Voice and F2 turn state are orthogonal but gated:

```text
canStartVoice =
  boot in READY|DEGRADED
  && !turnProcessing.inFlight
  && voice.phase == IDLE

canSend =
  current F2 canSend
  && voice.phase == IDLE
  && voice.stagedTranscript == null
```

A staged transcript must be explicitly accepted or discarded before Send becomes available. This prevents a hidden transcript/lineage race.

## Amendment A4 — delayed voice result cannot clobber a newer draft

At recording start, capture:

```text
draftAtCaptureStart = current.draft
```

On successful local recognition:

### Case 1: draft unchanged

`current.draft == draftAtCaptureStart`

- append/adopt transcript deterministically;
- attach Recognition Photon to pending voice lineage;
- return voice phase to IDLE.

### Case 2: draft changed while recording/processing

`current.draft != draftAtCaptureStart`

- do not mutate draft;
- store transcript + Recognition Photon as staged voice result;
- expose `Übernehmen` / `Verwerfen`;
- return voice phase to IDLE.

### Accept staged

- merge staged transcript with the *current* draft;
- add staged Recognition Photon to pending lineage;
- mark resulting voice contribution as edited/multimodal;
- clear staging.

### Discard staged

- clear staging only;
- do not delete the already persisted speech observation/recognition Photon;
- do not attach it as a parent of the later chat message.

This is provenance-correct: the microphone observation happened even if the user chooses not to use it in the chat turn.

## Amendment A5 — editing after accepted speech

Keep a voice-derived draft baseline in state.

- if the user edits away from the baseline, set `voiceInputEdited=true`;
- if the draft is cleared, clear pending chat-lineage recognitions and voice baseline;
- otherwise retain Recognition parents because edits remain causally derived from the speech input.

No fuzzy text similarity heuristic is introduced.

## Permission ownership

`ChatMainActivity` owns Android permission launching.

ViewModel API:

```text
beginVoiceCapture(): ChatVoiceStartResult
  STARTED
  PERMISSION_REQUIRED
  BLOCKED

onMicrophonePermissionResult(granted: Boolean)
stopVoiceCapture()
acceptStagedVoiceTranscript()
discardStagedVoiceTranscript()
```

Flow:
1. mic tap -> `model.beginVoiceCapture()`;
2. if `PERMISSION_REQUIRED`, Compose invokes Activity callback;
3. Activity launches `RECORD_AUDIO` permission;
4. callback calls `model.onMicrophonePermissionResult(granted)`;
5. granted result immediately retries bounded local capture;
6. denied result returns IDLE with deterministic status.

No permission dialog is launched from ViewModel.

---

# Static review B — Image lineage, timeline and encrypted loading

## Existing generated image lineage

`ImagePhotonFactory` requires non-empty source parents and generated images are persisted with parents:
- source user Photon;
- goal Photon;
- scene Photon.

Generated image Photons intentionally do not carry `chat:user`/`chat:assistant` tags.

## Existing transformed image lineage

`TransformedImagePhotonFactory` creates a new immutable image Photon with parents:
- source image Photon;
- transformation goal Photon.

The source image is never overwritten.

## Amendment B1 — do not mutate core chat models

`ChatEvent` already has a source `photonId` and `ConversationProjector` owns deterministic textual role/turn projection. It should remain Android/image agnostic.

Do **not** add Bitmap, image descriptors or app UI concepts to `core:runtime/chat`.

## Amendment B2 — app-level multimodal timeline

Add a pure app-layer projector:

```text
sealed interface ChatTimelineItem {
  id
  createdAt

  Message(event: ChatEvent)
  Image(photon: Photon)
}
```

`ChatTimelineProjector.project(photons)`:
1. obtains text events through existing `ConversationProjector.project(photons)`;
2. indexes Photons by ID;
3. selects only `ImagePhotonFactory.IMAGE_REFERENCE_MIME` Photons;
4. includes an image only if bounded causal ancestry reaches a Photon tagged:
   - `chat`
   - `chat:user`
   - `conversation:default`;
5. ancestry uses immutable `Provenance.parentIds`, not content parsing;
6. traversal has a visited set and fixed max depth to remain bounded/fail-closed;
7. merges message/image items;
8. sorts by `createdAt`, then deterministic kind order, then stable ID.

This works for:
- generated image: direct user source parent;
- transformed image: transform -> goal -> triggering user chat ancestry;
- repeated transformations without inventing new chat tags.

Unrelated image Photons never appear in the default chat merely because they are images.

## Amendment B3 — image preview remains vault-backed

Add `ui/chat/ChatImagePreviewLoader.kt` instead of refactoring legacy `LifeOsViewModel` during F4.

Loader contract:
1. reject non-image-reference MIME;
2. strict `ImageAssetDescriptor.decode`;
3. cache key = Photon ID + revision + asset SHA;
4. call only `kernel.loadImageAsset(photon)`;
5. decode locally with `BitmapFactory`;
6. verify decoded width/height match descriptor;
7. on mismatch recycle bitmap and fail closed;
8. bounded LRU preview cache;
9. `clear()` on ChatViewModel teardown.

No direct vault path, file path, `ContentResolver`, URL, HTTP, or web loader is allowed.

## Amendment B4 — preserve existing productive image E2E

Do not create a second image generator test that renders another image unnecessarily.

Extend the existing `OfflineImageArtifactDeviceTest` after its current encrypted reload assertions:
- project current kernel photons through `ChatTimelineProjector`;
- assert generated image Photon appears as an Image item;
- load same image through `ChatImagePreviewLoader`;
- assert Ready + exact width/height/renderer;
- retain all existing encrypted asset + Artifact lifecycle assertions.

Thus one real API-36 render proves:

```text
chat prompt
 -> goal/action
 -> generated image Photon
 -> encrypted PNG vault
 -> reload/decode
 -> IMAGE artifact lifecycle
 -> F4 chat timeline projection
 -> F4 preview loader
```

---

# Planned production changes

## New: `app/src/main/java/app/lifeos/next/ui/chat/ChatVoiceState.kt`

Pure/testable contracts only:
- `ChatVoicePhase { IDLE, RECORDING, PROCESSING }`;
- `ChatVoiceStartResult { STARTED, PERMISSION_REQUIRED, BLOCKED }`;
- `ChatVoiceUiState`;
- staged transcript metadata;
- pure draft conflict/merge reducer;
- policy helpers for `canStartVoice` / `canSendWithVoice`.

No Android microphone calls in this file.

## New: `app/src/main/java/app/lifeos/next/ui/chat/ChatTimeline.kt`

Pure/testable:
- `ChatTimelineItem.Message`;
- `ChatTimelineItem.Image`;
- `ChatTimelineProjector`;
- bounded ancestry resolver;
- deterministic ordering.

No Bitmap/Compose code.

## New: `app/src/main/java/app/lifeos/next/ui/chat/ChatImagePreviewLoader.kt`

Android app-layer only:
- preview model/state;
- bounded LRU cache;
- descriptor validation;
- `kernel.loadImageAsset` only;
- Bitmap decode and dimension verification;
- cleanup.

## New: `app/src/main/java/app/lifeos/next/ui/chat/ChatImageContent.kt`

Compose renderer:
- loading indicator;
- image with aspect ratio from validated preview;
- label `Offline erzeugt` / `Offline bearbeitet`;
- dimensions + renderer ID;
- fail-closed error surface without raw storage paths.

## Modify: `ChatMainActivity.kt`

Current ~lines 1–30.

Exact intent:
- keep initial-data permission launcher unchanged;
- add separate `RECORD_AUDIO` launcher;
- reuse one Activity-owned `LifeOsChatViewModel` instance;
- permission callback -> VM;
- `LifeOsRoot` receives mic permission launcher callback.

## Modify: `ui/LifeOsRoot.kt`

- add `onRequestMicrophonePermission: () -> Unit` parameter;
- pass it only to Chat destination;
- Memory/Goals/System routing unchanged.

No navigation-state change.

## Modify: `LifeOsChatViewModel.kt`

### State block
Add:
- `timeline: List<ChatTimelineItem>`;
- `voice: ChatVoiceUiState`;
- pending accepted Recognition Photon lineage;
- staged Recognition Photon reference kept privately or in bounded state as required.

### Fields
Add:
- `multimodalPerception = owner.multimodalPerception`;
- `voiceCapture = AndroidVoiceCaptureEngine(application.applicationContext)`;
- `voiceStopRequested = AtomicBoolean(false)`;
- `imagePreviewLoader`;
- private latest Photon snapshot for voice language context.

### `editDraft`
- keep always editable;
- update voice-edited marker deterministically;
- clearing draft clears accepted voice lineage/baseline;
- never clear a staged transcript implicitly.

### Voice methods
Add:
- `beginVoiceCapture`;
- `onMicrophonePermissionResult`;
- `stopVoiceCapture`;
- `acceptStagedVoiceTranscript`;
- `discardStagedVoiceTranscript`;
- `applyVoiceCaptureResult`;
- `buildSpeechObservation`;
- `buildVoiceLanguageContext`.

Reuse semantic limits from legacy implementation:
- max context Photons 24;
- active context Photons 6;
- max terms per Photon 32.

### `sendMessage`
Keep current F2 sequence:
1. guard;
2. create turn UUID/tags;
3. clear draft + SUBMITTING;
4. `kernel.persistUserUtterance`;
5. PERSISTING_RESPONSE;
6. compose assistant;
7. persist assistant;
8. IDLE/FAILED.

Only user-Photon construction changes to include accepted voice Recognition lineage.

Do not add queueing or a parallel voice-specific persistence path.

### `observeKernel`
- cache latest boot photons privately;
- set `events = ConversationProjector.project(...)` temporarily if needed;
- set `timeline = ChatTimelineProjector.project(...)`;
- F3 health projection unchanged.

### image preview API
Expose one suspend function delegating to `ChatImagePreviewLoader`.

### teardown
- request microphone stop;
- clear preview cache;
- do not shut down process kernel.

## Modify: `ChatComposer.kt`

Current ~lines 1–80.

New parameter surface:
- `voice: ChatVoiceUiState`;
- `onVoiceAction`;
- `onAcceptStagedVoice`;
- `onDiscardStagedVoice`.

UI behavior:
- IDLE -> mic button `Sprache`;
- RECORDING -> stop button `Stopp`;
- PROCESSING -> progress indicator, no second capture;
- staged -> transcript preview + explicit accept/discard;
- text field remains editable in every voice phase;
- Send disabled during RECORDING/PROCESSING/staged transcript;
- existing F2 processing status remains visible.

No new Material icon dependency required in F4; text labels are acceptable and accessible. F9 may restyle/iconize later.

## Modify: `LifeOsChatScreen.kt`

Current ~lines 1–105.

- signature adds permission callback;
- LazyColumn consumes `state.timeline`;
- `Message` -> existing chat card behavior;
- `Image` -> `ChatImageContent`;
- empty state checks timeline, not only events;
- composer receives voice state/actions;
- F3 runtime modal unchanged.

## Modify: `OfflineImageArtifactDeviceTest.kt`

No second render test.

Extend existing test with:
- F4 timeline assertion;
- F4 loader assertion after encrypted reload;
- no weakening/removal of existing artifact lifecycle assertions.

---

# Planned tests

## New JVM: `ChatVoiceStateTest.kt`

Minimum cases:
1. READY + idle turn + IDLE voice => capture allowed.
2. DEGRADED boot remains capture-eligible like text send.
3. LOADING/FAILED boot => capture blocked.
4. in-flight F2 turn => capture blocked.
5. RECORDING/PROCESSING => Send blocked.
6. unchanged draft + transcript => auto merge.
7. changed draft + transcript => draft unchanged and transcript staged.
8. accept staged => deterministic merge.
9. discard staged => current draft unchanged.
10. blank-draft direct voice result can remain exact speech-derived.
11. pre-existing typed draft + voice result is marked edited/multimodal.
12. clearing draft clears accepted voice lineage contract.
13. same inputs produce same reducer result.

## New JVM: `ChatTimelineProjectorTest.kt`

Minimum cases:
1. plain chat photons produce only Message items.
2. unrelated image Photon is excluded.
3. generated image with direct chat-user parent is included.
4. generated image with wrong conversation is excluded.
5. transformed image through goal ancestry is included.
6. broken/missing parent chain fails closed and excludes image.
7. cyclic/malicious parent graph terminates due visited/depth bound.
8. ordering is deterministic by timestamp/kind/id.
9. text role/turn projection remains exactly `ConversationProjector` output.

## Update JVM: existing composer policy tests

Add voice gates without changing F2 expectations:
- typing remains allowed by UI while turn/voice processing occurs;
- send gating is additive only.

## Update Android device E2E: `OfflineImageArtifactDeviceTest`

Add F4 assertions to existing single render:
- image present in multimodal chat timeline;
- preview loader reads encrypted vault-backed image;
- width/height/renderer match descriptor.

Do not simulate microphone hardware on hosted emulator. Voice behavior is proven through pure state/provenance contracts plus production compilation; the actual capture engine remains the already-existing bounded implementation.

---

# CI/recovery review

Existing Recovery PR paths already include:
- `ChatMainActivity.kt`;
- `LifeOsChatViewModel.kt`;
- `ui/**`;
- `app/src/androidTest/**`;
- `kernel/**`.

Therefore planned F4 production changes already trigger Android Emulator Recovery. No workflow path widening is required unless implementation unexpectedly touches a file outside these globs.

The existing Product Gold path already runs `OfflineImageArtifactDeviceTest`. Extending that exact test makes the current `offline_image_artifact_e2e=PASS` seal include the F4 chat image projection/preview proof without inventing a second expensive render.

Required final PR-head gates on one immutable source SHA:
- Core Fast;
- Android Debug CI (unit + lint + debug APK);
- Android Emulator Recovery API 36 / 2 cores;
- LIFEOS Product Gold.

After merge, repeat all four on exact merged `main`, then verify Product Gold:

```text
candidate_sha == source_head_sha == checkout_sha == merged_main_sha
product_gold=PASS
offline_image_artifact_e2e=PASS
```

Capture final debug APK SHA256 and attach evidence to the F4 PR.

---

# Definition of Done

F4 is complete only when all are true:

1. Launcher Chat composer exposes local voice capture.
2. `RECORD_AUDIO` permission is owned by `ChatMainActivity`, not Compose/ViewModel.
3. Existing `AndroidVoiceCaptureEngine` is reused unchanged unless a real defect is found.
4. Raw PCM is not persisted.
5. Voice results cannot overwrite a draft modified after capture start.
6. Staged transcript requires explicit accept/discard.
7. Final speech-derived user turn is a normal chat Photon with conversation + turn tags.
8. Accepted Recognition Photons are retained as immutable parent lineage.
9. F2 turn commits remain serialized.
10. Draft remains editable during turn and voice processing.
11. Generated and transformed chat-derived image Photons appear inline in the launcher timeline.
12. Unrelated image Photons do not leak into default chat.
13. Image bytes are loaded only through encrypted-vault kernel API.
14. Descriptor/decode/dimension failures render fail-closed UI.
15. Existing IMAGE artifact lifecycle device proof remains intact and now proves F4 projection/preview.
16. F3 runtime health UI remains unchanged.
17. Old `MainActivity` remains available for not-yet-migrated F5/F8 features.
18. Final PR source head passes Core/Debug/Recovery/Product Gold 4/4.
19. Exact merged main repeats 4/4.
20. Product Gold exact-SHA evidence and APK SHA256 are sealed in the PR.

---

# Explicitly out of scope

- importing arbitrary gallery/camera images;
- persistent audio files;
- background microphone capture;
- cloud speech recognition;
- network image generation;
- image editor controls beyond rendering existing generated/transformed outputs;
- Memory workspace (F5);
- Goals workspace (F6);
- DecisionTrace / Why UI (F7);
- Tool Center migration (F8);
- final theme/icons/motion polish (F9);
- release signing/AAB/store work.

## Implementation order

1. Add pure `ChatVoiceState` contract + JVM tests.
2. Add pure `ChatTimelineProjector` + JVM tests.
3. Add vault-backed `ChatImagePreviewLoader` + Compose image content.
4. Integrate voice into `LifeOsChatViewModel` without changing F2 turn sequence.
5. Integrate microphone permission launcher into `ChatMainActivity` / `LifeOsRoot`.
6. Integrate mic/staged transcript into `ChatComposer`.
7. Switch `LifeOsChatScreen` to multimodal timeline.
8. Extend existing Offline IMAGE device E2E with timeline + preview assertions.
9. Diff review A: provenance/concurrency/security.
10. Diff review B: scope/CI/legacy-regression.
11. Open draft PR.
12. Exact-head Core/Debug/Recovery/Product Gold.
13. Fix only evidenced failures; any code change invalidates prior head results.
14. Merge with expected head SHA only.
15. Exact-main 4/4 + Product Gold seal.