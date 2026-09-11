package app.lifeos.core.runtime.goal

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant

enum class LocalShareKind { TEXT, IMAGE }

data class LocalSharePreparation(
    val requestSourceId: PhotonId,
    val requestGoalId: PhotonId,
    val target: Photon,
    val kind: LocalShareKind,
) {
    val mediaType: String = if (kind == LocalShareKind.IMAGE) "image/png" else "text/plain"
}

sealed interface LocalCommunicationGoalResult {
    data class Prepared(val share: LocalSharePreparation) : LocalCommunicationGoalResult
    data class Blocked(val reason: String) : LocalCommunicationGoalResult {
        init { require(reason.isNotBlank()) }
    }
    data class Unsupported(val intent: IntentType) : LocalCommunicationGoalResult
}

/**
 * Offline preparation for explicit user-mediated sharing. This engine selects content only; it has
 * no external communication authority and cannot claim that a message was delivered.
 */
class LocalCommunicationGoalEngine {
    fun supports(intent: IntentType): Boolean = intent == IntentType.COMMUNICATE

    fun prepare(
        goal: GoalFrame,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
        photons: List<Photon>,
    ): LocalCommunicationGoalResult {
        if (!supports(goal.intent)) return LocalCommunicationGoalResult.Unsupported(goal.intent)

        val byId = photons.associateBy { it.id }
        val referenced = goal.references
            .asSequence()
            .filter { it.score >= MIN_REFERENCE_SCORE }
            .mapNotNull { it.targetPhotonId }
            .mapNotNull(byId::get)
            .firstOrNull { candidate -> isShareable(candidate, sourcePhoton.id, goalPhotonId) }

        val target = referenced ?: photons
            .asSequence()
            .filter { candidate -> isShareable(candidate, sourcePhoton.id, goalPhotonId) }
            .filter(::isResultLike)
            .maxWithOrNull(compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value })
            ?: return LocalCommunicationGoalResult.Blocked("share-source-missing")

        val kind = if (isImage(target)) LocalShareKind.IMAGE else LocalShareKind.TEXT
        return LocalCommunicationGoalResult.Prepared(
            LocalSharePreparation(
                requestSourceId = sourcePhoton.id,
                requestGoalId = goalPhotonId,
                target = target,
                kind = kind,
            )
        )
    }

    fun createHandoffReceipt(
        share: LocalSharePreparation,
        createdAt: Instant = Instant.now(),
    ): Photon = Photon(
        content = buildString {
            appendLine("communication-receipt/v1")
            appendLine("status=share-sheet-opened")
            appendLine("targetPhotonId=${share.target.id.value}")
            append("mediaType=${share.mediaType}")
        },
        mimeType = RECEIPT_MIME,
        phase = PhotonPhase.CONVERGED,
        semanticMass = 0.5,
        energy = 0.5,
        confidence = 1.0,
        provenance = Provenance(
            source = "local-share-handoff",
            actor = "LocalCommunicationGoalEngine",
            createdAt = createdAt,
            parentIds = setOf(share.requestSourceId, share.requestGoalId, share.target.id),
        ),
        relations = setOf(
            PhotonRelation(share.requestSourceId, RelationType.DERIVED_FROM),
            PhotonRelation(share.requestGoalId, RelationType.REFERENCES),
            PhotonRelation(share.target.id, RelationType.REFERENCES),
        ),
        tags = setOf("communication", "share-handoff", "result"),
    )

    private fun isShareable(candidate: Photon, sourceId: PhotonId, goalId: PhotonId): Boolean {
        if (candidate.id == sourceId || candidate.id == goalId) return false
        if (candidate.phase == PhotonPhase.ARCHIVED) return false
        if ("goal" in candidate.tags || "scene-graph" in candidate.tags) return false
        if ("capability-gap" in candidate.tags || "tool-request" in candidate.tags) return false
        if (candidate.mimeType == LocalScheduleGoalEngine.REMINDER_MIME) return false
        return isImage(candidate) || candidate.mimeType.startsWith("text/") ||
            "answer" in candidate.tags || "memory" in candidate.tags || "result" in candidate.tags
    }

    private fun isResultLike(candidate: Photon): Boolean =
        "result" in candidate.tags || "answer" in candidate.tags || "image" in candidate.tags ||
            "deepsearch-answer" in candidate.tags || "local-query-answer" in candidate.tags ||
            isImage(candidate)

    private fun isImage(candidate: Photon): Boolean =
        "image" in candidate.tags || candidate.mimeType.startsWith("image/") ||
            candidate.mimeType == IMAGE_REFERENCE_MIME

    companion object {
        const val RECEIPT_MIME = "application/vnd.lifeos.communication-receipt+text"
        const val IMAGE_REFERENCE_MIME = "application/vnd.lifeos.image-ref+text"
        private const val MIN_REFERENCE_SCORE = 0.55
    }
}
