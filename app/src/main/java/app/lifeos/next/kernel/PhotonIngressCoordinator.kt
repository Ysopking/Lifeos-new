package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.cognition.CognitiveDeltaIdentity
import app.lifeos.core.runtime.cognition.CognitivePriority
import app.lifeos.core.runtime.cognition.CognitiveSubmissionResult
import app.lifeos.core.runtime.cognition.CognitiveWorkBudget
import app.lifeos.core.runtime.cognition.PhotonDelta
import app.lifeos.core.runtime.cognition.PhotonDeltaType
import app.lifeos.core.runtime.cognition.SalienceVector
import kotlin.math.abs
import kotlinx.coroutines.CancellationException

internal class PhotonIngressCoordinator(
    private val photonStore: PhotonRepository,
    private val liveSubmissionBudget: CognitiveWorkBudget,
    private val submitCognition: suspend (
        PhotonDelta,
        CognitivePriority,
        SalienceVector,
        Set<String>,
        CognitiveWorkBudget,
    ) -> CognitiveSubmissionResult,
    private val onPhotonPersisted: (Photon) -> Unit,
) {
    suspend fun persistWithoutCognition(
        photon: Photon,
        mode: PhotonIngressMode,
    ): PhotonSubmissionResult {
        ProductivePhotonIngressClassification.requireOrMark(photonStore, photon, mode)
        persistFast(photon)
        onPhotonPersisted(photon)
        return PhotonSubmissionResult(
            photon = photon,
            processingQueued = false,
            processingFailure = null,
        )
    }

    suspend fun persistAndIngest(
        photon: Photon,
    ): PhotonSubmissionResult =
        persistAndIngest(photon, PhotonIngressMode.ORIGIN)

    suspend fun persistAndIngest(
        photon: Photon,
        mode: PhotonIngressMode,
    ): PhotonSubmissionResult {
        ProductivePhotonIngressClassification.requireOrMark(photonStore, photon, mode)
        val previous = persistForCognition(photon)
        onPhotonPersisted(photon)

        return try {
            val submission = submitCognition(
                PhotonDelta(
                    deltaId = CognitiveDeltaIdentity.photonRevision(photon.id, photon.revision),
                    source = "kernel-live-submit",
                    photonId = photon.id,
                    revisionBefore = previous?.revision,
                    revisionAfter = photon.revision,
                    type = if (previous == null) PhotonDeltaType.CREATED else PhotonDeltaType.UPDATED,
                    importanceHint = photon.semanticMass,
                    timestamp = photon.provenance.createdAt,
                    correlationId = photon.id.value,
                ),
                CognitivePriority.USER_BLOCKING,
                SalienceVector(
                    novelty = if (previous == null) 1.0 else 0.25,
                    relevance = 1.0,
                    urgency = 1.0,
                    semanticMass = photon.semanticMass,
                    confidenceImpact = abs(
                        photon.confidence - (previous?.confidence ?: 0.0)
                    ),
                    goalAffinity = if ("chat" in photon.tags || "goal" in photon.tags) {
                        1.0
                    } else {
                        0.5
                    },
                ),
                setOf("Gedankenmatrix"),
                liveSubmissionBudget,
            )
            val durable = submission.accepted && submission.durableTaskId != null
            PhotonSubmissionResult(
                photon = photon,
                processingQueued = durable,
                processingFailure = if (durable) {
                    null
                } else {
                    "Cognitive work was not durabilized"
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PhotonSubmissionResult(
                photon = photon,
                processingQueued = false,
                processingFailure = error.message ?: error::class.simpleName,
            )
        }
    }

    private suspend fun persistFast(photon: Photon) {
        val revisioned = photonStore as? RevisionedPhotonRepository
        if (revisioned != null) {
            when (
                val write = revisioned.saveRevision(
                    photon = photon,
                    expectedPreviousRevision = expectedPreviousRevision(photon),
                )
            ) {
                is PhotonRevisionWriteResult.Created,
                is PhotonRevisionWriteResult.Advanced,
                is PhotonRevisionWriteResult.Idempotent -> Unit

                is PhotonRevisionWriteResult.Conflict ->
                    error("Photon revision conflict: ${write.reason}")
            }
            return
        }

        photonStore.load(photon.id)?.let { existing ->
            check(existing == photon) {
                "Photon identity conflict on fast conversation path"
            }
        } ?: photonStore.save(photon)
    }

    private suspend fun persistForCognition(photon: Photon): Photon? {
        val revisioned = photonStore as? RevisionedPhotonRepository
        if (revisioned != null) {
            return when (
                val write = revisioned.saveRevision(
                    photon = photon,
                    expectedPreviousRevision = expectedPreviousRevision(photon),
                )
            ) {
                is PhotonRevisionWriteResult.Created -> null
                is PhotonRevisionWriteResult.Advanced -> write.previous
                is PhotonRevisionWriteResult.Idempotent -> write.previous
                is PhotonRevisionWriteResult.Conflict ->
                    error("Photon revision conflict: ${write.reason}")
            }
        }

        return photonStore.load(photon.id).also {
            photonStore.save(photon)
        }
    }

    private fun expectedPreviousRevision(photon: Photon): Long? =
        photon.revision.takeIf { it > 1L }?.minus(1L)
}
