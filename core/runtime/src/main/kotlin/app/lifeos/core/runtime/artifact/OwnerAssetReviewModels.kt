package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonCodec
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.StableCognitiveIds
import java.security.MessageDigest
import java.time.Instant

@JvmInline
value class OwnerAssetReviewCandidateId(val value: String) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "Owner asset review candidate id must be a lowercase SHA-256 fingerprint"
        }
    }
}

enum class OwnerAssetReviewSubjectType {
    COLLABORATIVE_ARTIFACT,
    GENERATED_TOOL,
}

enum class OwnerAssetReviewDecision {
    APPROVED,
    CHANGES_REQUESTED,
    REJECTED,
}

/** Exact generated revision waiting for the private owner's decision. */
data class OwnerAssetReviewCandidate(
    val id: OwnerAssetReviewCandidateId,
    val subjectType: OwnerAssetReviewSubjectType,
    val subjectId: String,
    val revisionKey: String,
    val kind: ArtifactKind,
    val title: String,
    val targetMimeType: String,
    val createdAt: Instant,
    val participatingModules: Set<String>,
    val inputPhotonIds: Set<PhotonId>,
    val materializedAsset: AssetRef? = null,
    /** Exact Photons to publish only after APPROVED. Empty for externally persisted subjects such as tools. */
    val stagedPhotons: List<Photon> = emptyList(),
    /** Optional bounded review text, e.g. generated source code. Never used as publication authority. */
    val previewText: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(subjectId.isNotBlank() && subjectId.length <= MAX_ID_CHARS)
        require(revisionKey.isNotBlank() && revisionKey.length <= MAX_ID_CHARS)
        require(title.isNotBlank() && title.length <= MAX_TITLE_CHARS)
        require(targetMimeType.isNotBlank() && targetMimeType.length <= MAX_MIME_CHARS)
        require(!targetMimeType.contains('\n') && !targetMimeType.contains('\r'))
        require(participatingModules.isNotEmpty())
        require(participatingModules.size <= MAX_MODULES)
        require(participatingModules.none { it.isBlank() || it.length > MAX_MODULE_CHARS })
        require(inputPhotonIds.size <= MAX_INPUT_PHOTONS)
        require(stagedPhotons.size <= MAX_STAGED_PHOTONS)
        require(stagedPhotons.map { it.id to it.revision }.distinct().size == stagedPhotons.size) {
            "Owner asset review candidate contains duplicate staged Photon revisions"
        }
        require(previewText == null || previewText.length <= MAX_PREVIEW_CHARS)
        require(metadata.size <= MAX_METADATA_ENTRIES)
        require(metadata.all { (key, value) ->
            key.isNotBlank() && key.length <= MAX_METADATA_KEY_CHARS && value.length <= MAX_METADATA_VALUE_CHARS
        })
        require(
            id == createId(
                subjectType = subjectType,
                subjectId = subjectId,
                revisionKey = revisionKey,
                kind = kind,
                title = title,
                targetMimeType = targetMimeType,
                createdAt = createdAt,
                participatingModules = participatingModules,
                inputPhotonIds = inputPhotonIds,
                materializedAsset = materializedAsset,
                stagedPhotons = stagedPhotons,
                previewText = previewText,
                metadata = metadata,
            )
        ) { "Owner asset review candidate identity does not match its immutable revision" }
    }

    companion object {
        const val MAX_ID_CHARS = 256
        const val MAX_TITLE_CHARS = 512
        const val MAX_MIME_CHARS = 256
        const val MAX_MODULES = 64
        const val MAX_MODULE_CHARS = 128
        const val MAX_INPUT_PHOTONS = 4_096
        const val MAX_STAGED_PHOTONS = 32
        const val MAX_PREVIEW_CHARS = 65_536
        const val MAX_METADATA_ENTRIES = 64
        const val MAX_METADATA_KEY_CHARS = 128
        const val MAX_METADATA_VALUE_CHARS = 4_096

        fun create(
            subjectType: OwnerAssetReviewSubjectType,
            subjectId: String,
            revisionKey: String,
            kind: ArtifactKind,
            title: String,
            targetMimeType: String,
            createdAt: Instant,
            participatingModules: Set<String>,
            inputPhotonIds: Set<PhotonId>,
            materializedAsset: AssetRef? = null,
            stagedPhotons: List<Photon> = emptyList(),
            previewText: String? = null,
            metadata: Map<String, String> = emptyMap(),
        ): OwnerAssetReviewCandidate {
            val canonicalModules = participatingModules.toSortedSet()
            val canonicalInputs = inputPhotonIds.toSortedSet(compareBy { it.value })
            val canonicalStaged = stagedPhotons.sortedWith(
                compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value }.thenBy { it.revision }
            )
            val canonicalMetadata = metadata.toSortedMap()
            return OwnerAssetReviewCandidate(
                id = createId(
                    subjectType = subjectType,
                    subjectId = subjectId,
                    revisionKey = revisionKey,
                    kind = kind,
                    title = title,
                    targetMimeType = targetMimeType,
                    createdAt = createdAt,
                    participatingModules = canonicalModules,
                    inputPhotonIds = canonicalInputs,
                    materializedAsset = materializedAsset,
                    stagedPhotons = canonicalStaged,
                    previewText = previewText,
                    metadata = canonicalMetadata,
                ),
                subjectType = subjectType,
                subjectId = subjectId,
                revisionKey = revisionKey,
                kind = kind,
                title = title,
                targetMimeType = targetMimeType,
                createdAt = createdAt,
                participatingModules = canonicalModules,
                inputPhotonIds = canonicalInputs,
                materializedAsset = materializedAsset,
                stagedPhotons = canonicalStaged,
                previewText = previewText,
                metadata = canonicalMetadata,
            )
        }

        private fun createId(
            subjectType: OwnerAssetReviewSubjectType,
            subjectId: String,
            revisionKey: String,
            kind: ArtifactKind,
            title: String,
            targetMimeType: String,
            createdAt: Instant,
            participatingModules: Set<String>,
            inputPhotonIds: Set<PhotonId>,
            materializedAsset: AssetRef?,
            stagedPhotons: List<Photon>,
            previewText: String?,
            metadata: Map<String, String>,
        ): OwnerAssetReviewCandidateId = OwnerAssetReviewCandidateId(
            StableCognitiveIds.fingerprint(
                "owner-asset-review-candidate/v1",
                subjectType.name,
                subjectId,
                revisionKey,
                kind.name,
                title,
                targetMimeType,
                createdAt.toString(),
                *participatingModules.sorted().toTypedArray(),
                *inputPhotonIds.map { it.value }.sorted().toTypedArray(),
                materializedAsset?.id?.value.orEmpty(),
                materializedAsset?.mediaType.orEmpty(),
                materializedAsset?.sha256.orEmpty(),
                materializedAsset?.byteCount?.toString().orEmpty(),
                *stagedPhotons
                    .sortedWith(compareBy<Photon> { it.id.value }.thenBy { it.revision })
                    .flatMap { photon ->
                        listOf(
                            photon.id.value,
                            photon.revision.toString(),
                            sha256(PhotonCodec.encode(photon)),
                        )
                    }
                    .toTypedArray(),
                StableCognitiveIds.fingerprint("owner-asset-review-preview/v1", previewText.orEmpty()),
                *metadata.toSortedMap().flatMap { (key, value) -> listOf(key, value) }.toTypedArray(),
            )
        )

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

data class OwnerAssetReviewDecisionRecord(
    val candidateId: OwnerAssetReviewCandidateId,
    val decision: OwnerAssetReviewDecision,
    val ownerActorId: String,
    val feedback: String? = null,
    val decidedAt: Instant,
    val decisionPhotonId: PhotonId,
) {
    init {
        require(ownerActorId.isNotBlank() && ownerActorId.length <= 128)
        require(feedback == null || feedback.length <= MAX_FEEDBACK_CHARS)
        if (decision == OwnerAssetReviewDecision.CHANGES_REQUESTED) {
            require(!feedback.isNullOrBlank()) { "Changes requested requires owner feedback" }
        }
    }

    companion object {
        const val MAX_FEEDBACK_CHARS = 8_192
    }
}

data class OwnerAssetReviewRecord(
    val candidate: OwnerAssetReviewCandidate,
    val decision: OwnerAssetReviewDecisionRecord? = null,
    val publishedAt: Instant? = null,
) {
    init {
        require(decision == null || decision.candidateId == candidate.id)
        require(decision == null || decision.decidedAt >= candidate.createdAt) {
            "Owner asset review decision cannot predate its generated candidate"
        }
        require(publishedAt == null || decision?.decision == OwnerAssetReviewDecision.APPROVED) {
            "Only an approved candidate may be marked published"
        }
        require(publishedAt == null || (decision != null && publishedAt >= decision.decidedAt)) {
            "Publication cannot predate owner approval"
        }
    }

    val pending: Boolean get() = decision == null
}

interface OwnerAssetReviewRepository {
    suspend fun stage(candidate: OwnerAssetReviewCandidate): OwnerAssetReviewRecord
    suspend fun load(candidateId: OwnerAssetReviewCandidateId): OwnerAssetReviewRecord?
    suspend fun loadAll(): List<OwnerAssetReviewRecord>
    suspend fun recordDecision(decision: OwnerAssetReviewDecisionRecord): OwnerAssetReviewRecord
    suspend fun markPublished(candidateId: OwnerAssetReviewCandidateId, publishedAt: Instant): OwnerAssetReviewRecord
}
