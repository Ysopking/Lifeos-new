# F5A — Owner Asset Review / Human-in-the-loop approval

Base SHA: `b289ac0719e2943a0686de0d3374bd3af7fe69ea`

## Goal

Make the private owner (`private-owner`) the explicit feedback and confirmation authority for generated assets from LIFEOS modules. Generated output may be computed and durably staged, but it must not become ordinary module/cognition input until the exact generated revision is approved by the owner.

The product surface is a top-level **Assets** destination where the owner can inspect pending/generated assets from all supported producers, then **Bestätigen**, **Änderungen anfordern** with feedback, or **Ablehnen**.

## Non-negotiable safety/semantic boundaries

1. `PENDING_OWNER_REVIEW` is not publication. Pending collaborative-asset/image Photons must not enter the canonical Photon vault or bootstrap/cognition graph before approval.
2. Pending review state must survive process death in a dedicated encrypted AtomicFile/AES-GCM review vault.
3. The encrypted binary asset may exist before approval, but only its bounded staged review metadata can reference it; ordinary module discovery stays blocked.
4. Approval is bound to one exact immutable candidate/revision. A changed/re-generated asset has a new candidate identity and requires a new decision.
5. Approval does not imply generated-tool/provider activation. Generated code remains non-activating and must still pass the existing trial/evolution/owner gates.
6. `CHANGES_REQUESTED` and `REJECTED` never publish the staged candidate.
7. Owner feedback is durable evidence. The decision Photon is persisted and exact-read-back verified before an APPROVED candidate is released.
8. Repeated identical approval is idempotent; conflicting decisions for the same candidate fail closed.
9. No module may auto-approve its own asset.
10. Existing Artifact validation, source provenance, participating modules, input lineage, encrypted AssetRef integrity, ToolWorkshop safety gates and owner policy remain intact.

## Review A — current production source/dependency review

### Current collaborative artifact path

`ArtifactCoordinator.finalize()` validates and deterministically constructs one immutable artifact revision Photon, then immediately calls its supplied `ArtifactPhotonIngress`.

`ArtifactGenerationCoordinator.finalize()` immediately follows that with a deterministic generation-provenance Photon and also calls the supplied ingress.

Android production currently binds that ingress to `CanonicalArtifactPhotonIngress`, which submits both as `PhotonIngressMode.DERIVED` through `LifeOsKernel.persistAndIngest()`.

**Finding:** a confirmation UI alone is insufficient; the production ingress boundary must be replaced by a pending-owner-review staging boundary.

### Current image generation path

`LifeOsKernel.generateImage()` currently:
1. publishes the scene Photon,
2. writes PNG bytes to encrypted `BinaryAssetStore`,
3. creates the image-reference Photon,
4. immediately `persistAndIngest()`s the image Photon,
5. then `ImageArtifactLifecycleRuntime` finalizes the collaborative IMAGE artifact.

**Finding:** gating only the artifact revision is too late. The generated image-reference Photon itself must be staged until owner approval. Scene evidence may remain internal/published because it is not the generated user asset.

### Current image-transform path

`LocalImageTransformActionExecutor` writes the transformed PNG and immediately publishes the transformed image Photon. It currently has no collaborative artifact lifecycle bridge.

**Finding:** transform output must join the same review pipeline and gain IMAGE artifact metadata/lineage before publication.

### Current generated-tool path

Generated tool code is already stored in an encrypted `GeneratedToolArtifactRepository`, has bounded canonical program/source/build hashes, and `activationAllowed=false`. Separate private-owner activation already exists.

**Finding:** expose generated-tool code as a CODE review subject and require an APPROVED asset-content decision before the existing activation path can proceed. Do not collapse asset approval into provider activation.

### Persistence finding

Pending asset Photons must **not** simply be saved into `EncryptedPhotonStore`: boot/rehydration loads that vault as authoritative Photon state. Pending candidates therefore require a separate encrypted review repository. The repository may use `PhotonCodec` to store exact staged Photons without publishing them.

## Review B — lifecycle/recovery/UX review

### State machine

```text
GENERATED + VALIDATED
        |
        v
PENDING_OWNER_REVIEW
   |        |        |
   |        |        +--> REJECTED --------X publish
   |        +-----------> CHANGES_REQUESTED X publish
   +--------------------> APPROVED --------> exact publish/re-entry
```

A subsequent generated revision always returns to `PENDING_OWNER_REVIEW`.

### Crash ordering for APPROVED

1. load exact candidate from encrypted review vault;
2. ensure no conflicting prior decision;
3. build deterministic owner-decision Photon bound to candidate id + exact revision key;
4. persist/ingest decision as owner-origin evidence;
5. exact-read-back the decision Photon;
6. publish staged Photons in deterministic dependency order through existing canonical DERIVED ingress;
7. persist candidate publication receipt/status in review vault;
8. on retry, resume idempotently from exact decision + candidate state.

A crash before step 4 leaves the candidate pending. A crash after the decision but before all staged Photons publish is recoverable because each exact Photon identity is immutable/idempotent and the approved candidate remains in the review vault.

### Feedback semantics

- `Bestätigen`: optional short note, publishes exact revision.
- `Änderungen anfordern`: nonblank feedback required; no publication. Feedback Photon can be consumed by future producer-specific revision loops.
- `Ablehnen`: optional reason; no publication.
- no decision is inherited by another revision.

### UI

New stable root destination:
- key: `assets`
- label: `Assets`
- screen title: `Asset-Freigaben`

Tabs:
- `Ausstehend`
- `Bestätigt`
- `Feedback`

Each card/detail exposes only real evidence:
- kind: DOCUMENT / IMAGE / CODE / REPORT / OTHER
- title and producer/participating modules
- exact candidate/revision key
- created time
- validation status
- MIME, byte count, SHA-256 when materialized
- input Photon lineage/source count
- preview when safely supported
- decision/feedback history

Preview rules:
- IMAGE: existing fail-closed encrypted preview path.
- bounded text/code/document/report MIME: integrity-check AssetRef first, then bounded UTF-8 preview.
- unknown/binary: metadata only; never unsafe-decode.
- generated-tool CODE: bounded canonical program preview from encrypted tool-artifact source.

## Implementation blocks

### F5A.1 — immutable review contracts + encrypted vault

New core runtime files:
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/artifact/OwnerAssetReviewModels.kt`
- `core/runtime/src/main/kotlin/app/lifeos/core/runtime/artifact/OwnerAssetReviewCodec.kt`
- corresponding JVM tests.

New data file:
- `core/data/src/main/kotlin/app/lifeos/core/data/artifact/EncryptedOwnerAssetReviewRepository.kt`
- Android/device corruption + recovery coverage.

Contract includes:
- stable candidate id
- subject type (`COLLABORATIVE_ARTIFACT`, `GENERATED_TOOL`)
- exact revision key
- asset kind/title/MIME
- participating modules/input lineage
- optional materialized `AssetRef`
- exact staged Photons for publishable collaborative assets
- bounded preview metadata/text when needed
- decision state + publication receipt state

### F5A.2 — private review runtime / publication boundary

New app-kernel files:
- `OwnerAssetReviewRuntime.kt`
- `OwnerAssetReviewRuntimeRegistry.kt` only if late binding remains necessary
- `OwnerReviewedArtifactPhotonIngress.kt`

Responsibilities:
- stage instead of canonical publish
- submit exact private-owner decisions
- exact-read-back before release
- deterministic/idempotent publish through `CanonicalArtifactPhotonIngress`
- read-only snapshot for UI

`CanonicalArtifactPhotonIngress` remains the final post-approval DERIVED publication boundary, not the pending store.

### F5A.3 — producer integrations

Image generation:
- stop publishing generated image-reference Photon before approval;
- stage image ref + artifact revision + generation Photon as one review candidate;
- response truth becomes “erzeugt, wartet auf Freigabe”;
- after approval the existing F4 chat timeline sees the newly published image.

Image transform:
- stage transformed output rather than immediate publish;
- attach IMAGE collaborative-artifact metadata with source image + goal lineage;
- require owner review before F4/memory publication.

Generated tools:
- mirror encrypted `GeneratedToolArtifact` into review projection as CODE subject;
- content approval remains separate from tool activation;
- `reviewAndActivateGeneratedTool()` must fail closed unless the exact tool build/source hash has APPROVED asset review evidence.

Other producers using `ArtifactGenerationCoordinator` inherit the production owner-review ingress automatically.

### F5A.4 — Assets UI

New:
- `LifeOsAssetReviewViewModel.kt`
- `ui/assets/AssetReviewUiModels.kt`
- `ui/assets/AssetReviewProjector.kt`
- `ui/assets/LifeOsAssetReviewScreen.kt`
- `ui/assets/AssetReviewDetails.kt`
- generic bounded preview loader if required.

Update:
- `LifeOsDestination.kt`
- `LifeOsRoot.kt`
- `ChatMainActivity.kt`

The ViewModel serializes review actions; the buttons are disabled during one exact decision commit. UI state never acts as approval source of truth.

### F5A.5 — real Android/Gold proof

Update/replace the current immediate-image publication assumptions in `OfflineImageArtifactDeviceTest` and recovery script.

Required proof:
1. generate image;
2. encrypted bytes + pending review candidate exist;
3. generated image/artifact/generation Photons are absent from live/canonical published set before approval;
4. pending candidate survives cold restart;
5. owner approves exact revision;
6. decision exact-readback succeeds;
7. staged Photons publish in deterministic order and F4 timeline now contains the image;
8. APPROVED state survives restart;
9. `CHANGES_REQUESTED` candidate never publishes;
10. approval for revision A cannot authorize revision B;
11. generated-tool activation is blocked until exact CODE asset review is approved.

## Definition of Done

One immutable F5A PR source SHA must have:
- Core Fast PASS
- Android Debug PASS
- Android Emulator Recovery PASS
- Product Gold PASS

Merge only with exact `expected_head_sha`, then repeat 4/4 on exact merged `main` and verify Gold `candidate_sha = source_head_sha = checkout_sha = merged-main-sha` plus APK SHA256.
