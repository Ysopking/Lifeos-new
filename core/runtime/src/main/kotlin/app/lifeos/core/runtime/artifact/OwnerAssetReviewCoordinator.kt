package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant

object OwnerAssetReviewContract {
    const val SCHEMA = "lifeos.owner-asset-review-decision.v1"
    const val MIME_TYPE = "application/vnd.lifeos.owner-asset-review-decision+json"
    const val PROVENANCE_SOURCE = "lifeos.owner-asset-review"
}

data class OwnerAssetReviewApplyResult(
    val record: OwnerAssetReviewRecord,
    val decisionPhoton: Photon,
    val newlyPublishedPhotonIds: List<PhotonId>,
) {
    init {
        require(record.decision != null)
        require(newlyPublishedPhotonIds.distinct().size == newlyPublishedPhotonIds.size)
        if (record.decision.decision != OwnerAssetReviewDecision.APPROVED) {
            require(newlyPublishedPhotonIds.isEmpty()) {
                "Non-approved owner review must not publish staged Photons"
            }
        }
    }
}

/**
 * Human-in-the-loop publication boundary for generated assets.
 *
 * The review repository is the source of truth for pending/decision state. Candidate Photons stay
 * outside the canonical PhotonRepository until the configured private owner approves the exact
 * candidate id. Publication is replay-safe: exact canonical Photons are skipped, identity collisions
 * fail closed, staged parent dependencies are published before their children, and approved
 * subject-specific effects are replayed before the review is marked published.
 */
class OwnerAssetReviewCoordinator(
    private val reviews: OwnerAssetReviewRepository,
    private val photons: PhotonRepository,
    private val ingress: ArtifactPhotonIngress,
    private val authorizedOwnerActorId: String,
    private val onApproved: suspend (OwnerAssetReviewCandidate) -> Unit = {},
) {
    init {
        require(authorizedOwnerActorId.isNotBlank()) {
            "Owner asset review requires one nonblank authorized owner actor"
        }
    }

    suspend fun stage(candidate: OwnerAssetReviewCandidate): OwnerAssetReviewRecord =
        reviews.stage(candidate)

    suspend fun snapshot(): List<OwnerAssetReviewRecord> = reviews.loadAll()

    suspend fun decide(
        candidateId: OwnerAssetReviewCandidateId,
        decision: OwnerAssetReviewDecision,
        ownerActorId: String,
        feedback: String?,
        decidedAt: Instant,
    ): OwnerAssetReviewApplyResult {
        require(ownerActorId == authorizedOwnerActorId) {
            "Owner asset review decision is not authorized for actor $ownerActorId"
        }
        val current = requireNotNull(reviews.load(candidateId)) {
            "Owner asset review candidate ${candidateId.value} does not exist"
        }
        require(decidedAt >= current.candidate.createdAt) {
            "Owner asset review decision cannot predate the generated candidate"
        }
        val decisionRecord = current.decision ?: createDecisionRecord(
            candidate = current.candidate,
            decision = decision,
            ownerActorId = ownerActorId,
            feedback = feedback,
            decidedAt = decidedAt,
        ).also { reviews.recordDecision(it) }

        if (current.decision != null) {
            require(decisionRecord.decision == decision) {
                "Owner asset review decision is immutable once recorded"
            }
            require(decisionRecord.ownerActorId == ownerActorId) {
                "Owner asset review decision belongs to another owner actor"
            }
            require(decisionRecord.feedback == feedback) {
                "Owner asset review decision feedback is immutable once recorded"
            }
        }

        val decisionPhoton = createDecisionPhoton(current.candidate, decisionRecord)
        publishExact(decisionPhoton)

        if (decisionRecord.decision != OwnerAssetReviewDecision.APPROVED) {
            val settled = requireNotNull(reviews.load(candidateId))
            return OwnerAssetReviewApplyResult(
                record = settled,
                decisionPhoton = decisionPhoton,
                newlyPublishedPhotonIds = emptyList(),
            )
        }

        val newlyPublished = publishApprovedCandidate(current.candidate)
        val published = reviews.markPublished(
            candidateId = candidateId,
            publishedAt = decisionRecord.decidedAt,
        )
        return OwnerAssetReviewApplyResult(
            record = published,
            decisionPhoton = decisionPhoton,
            newlyPublishedPhotonIds = newlyPublished,
        )
    }

    /** Reconciles recorded approvals after process death without creating a new owner decision. */
    suspend fun reconcileApproved(): List<OwnerAssetReviewRecord> {
        val reconciled = mutableListOf<OwnerAssetReviewRecord>()
        reviews.loadAll()
            .filter { it.decision?.decision == OwnerAssetReviewDecision.APPROVED }
            .forEach { record ->
                val decision = requireNotNull(record.decision)
                require(decision.ownerActorId == authorizedOwnerActorId) {
                    "Persisted owner asset approval is not authorized for actor ${decision.ownerActorId}"
                }
                require(decision.decidedAt >= record.candidate.createdAt) {
                    "Persisted owner asset approval predates its generated candidate"
                }
                publishExact(createDecisionPhoton(record.candidate, decision))
                publishApprovedCandidate(record.candidate)
                reconciled += if (record.publishedAt == null) {
                    reviews.markPublished(record.candidate.id, decision.decidedAt)
                } else {
                    record
                }
            }
        return reconciled
    }

    private suspend fun publishApprovedCandidate(candidate: OwnerAssetReviewCandidate): List<PhotonId> {
        val newlyPublished = mutableListOf<PhotonId>()
        publicationOrder(candidate.stagedPhotons).forEach { photon ->
            if (publishExact(photon)) newlyPublished += photon.id
        }
        onApproved(candidate)
        return newlyPublished
    }

    private fun createDecisionRecord(
        candidate: OwnerAssetReviewCandidate,
        decision: OwnerAssetReviewDecision,
        ownerActorId: String,
        feedback: String?,
        decidedAt: Instant,
    ): OwnerAssetReviewDecisionRecord {
        require(ownerActorId == authorizedOwnerActorId)
        require(decidedAt >= candidate.createdAt)
        val fingerprint = StableCognitiveIds.fingerprint(
            "owner-asset-review-decision/v1",
            candidate.id.value,
            candidate.subjectType.name,
            candidate.subjectId,
            candidate.revisionKey,
            decision.name,
            ownerActorId,
            feedback.orEmpty(),
            decidedAt.toString(),
        )
        return OwnerAssetReviewDecisionRecord(
            candidateId = candidate.id,
            decision = decision,
            ownerActorId = ownerActorId,
            feedback = feedback,
            decidedAt = decidedAt,
            decisionPhotonId = PhotonId("owner_asset_review_$fingerprint"),
        )
    }

    private fun createDecisionPhoton(
        candidate: OwnerAssetReviewCandidate,
        decision: OwnerAssetReviewDecisionRecord,
    ): Photon {
        val parentIds = candidate.inputPhotonIds.toSortedSet(compareBy { it.value })
        return Photon(
            id = decision.decisionPhotonId,
            revision = 1L,
            content = decisionEnvelope(candidate, decision),
            mimeType = OwnerAssetReviewContract.MIME_TYPE,
            phase = PhotonPhase.CONVERGED,
            semanticMass = 1.0,
            energy = 1.0,
            confidence = 1.0,
            provenance = Provenance(
                source = OwnerAssetReviewContract.PROVENANCE_SOURCE,
                actor = decision.ownerActorId,
                createdAt = decision.decidedAt,
                parentIds = parentIds,
            ),
            relations = parentIds.mapTo(linkedSetOf()) { parentId ->
                PhotonRelation(parentId, RelationType.REFERENCES)
            },
            tags = buildSet {
                add("owner-asset-review")
                add("owner-asset-review-candidate:${candidate.id.value}")
                add("owner-asset-review-decision:${decision.decision.name.lowercase()}")
                add("owner-asset-review-subject:${candidate.subjectType.name.lowercase()}")
                add("artifact-kind:${candidate.kind.name.lowercase()}")
                add("artifact-id:${candidate.subjectId}")
                add("artifact-revision:${candidate.revisionKey}")
            },
        )
    }

    /** Returns true only when this call had to send the Photon through canonical ingress. */
    private suspend fun publishExact(photon: Photon): Boolean {
        val existing = photons.load(photon.id)
        if (existing != null) {
            require(existing == photon) {
                "Canonical Photon identity ${photon.id.value} is occupied by different content"
            }
            return false
        }

        ingress.ingest(photon)
        val durable = photons.load(photon.id)
        require(durable == photon) {
            "Owner asset review publication did not durably persist ${photon.id.value}"
        }
        return true
    }

    private fun publicationOrder(staged: List<Photon>): List<Photon> {
        if (staged.size < 2) return staged
        val byId = staged.associateBy { it.id }
        require(byId.size == staged.size) { "Staged owner-review Photons contain duplicate ids" }

        val remainingParents = staged.associate { photon ->
            photon.id to photon.provenance.parentIds.filterTo(linkedSetOf()) { it in byId }
        }.toMutableMap()
        val ready = java.util.PriorityQueue<Photon>(compareBy { it.id.value })
        staged.filter { remainingParents.getValue(it.id).isEmpty() }.forEach(ready::add)
        val ordered = mutableListOf<Photon>()

        while (ready.isNotEmpty()) {
            val next = ready.remove()
            ordered += next
            remainingParents.remove(next.id)
            remainingParents.entries.forEach { (childId, parents) ->
                if (parents.remove(next.id) && parents.isEmpty()) {
                    ready += requireNotNull(byId[childId])
                }
            }
        }
        require(ordered.size == staged.size) {
            "Staged owner-review Photon dependencies contain a cycle"
        }
        return ordered
    }

    private fun decisionEnvelope(
        candidate: OwnerAssetReviewCandidate,
        decision: OwnerAssetReviewDecisionRecord,
    ): String = buildString {
        append('{')
        append("\"schema\":"); appendJson(OwnerAssetReviewContract.SCHEMA); append(',')
        append("\"candidateId\":"); appendJson(candidate.id.value); append(',')
        append("\"subjectType\":"); appendJson(candidate.subjectType.name); append(',')
        append("\"subjectId\":"); appendJson(candidate.subjectId); append(',')
        append("\"revisionKey\":"); appendJson(candidate.revisionKey); append(',')
        append("\"kind\":"); appendJson(candidate.kind.name); append(',')
        append("\"decision\":"); appendJson(decision.decision.name); append(',')
        append("\"ownerActorId\":"); appendJson(decision.ownerActorId); append(',')
        append("\"decidedAt\":"); appendJson(decision.decidedAt.toString()); append(',')
        append("\"feedback\":")
        if (decision.feedback == null) append("null") else appendJson(decision.feedback)
        append('}')
    }

    private fun StringBuilder.appendJson(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else append(char)
            }
        }
        append('"')
    }
}
