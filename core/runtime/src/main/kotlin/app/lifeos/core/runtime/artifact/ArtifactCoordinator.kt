package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.runtime.cognition.CognitivePriority
import app.lifeos.core.runtime.cognition.CognitiveWorkBudget
import app.lifeos.core.runtime.cognition.ContinuousCognitionEngine
import app.lifeos.core.runtime.cognition.PhotonDelta
import app.lifeos.core.runtime.cognition.PhotonDeltaType
import app.lifeos.core.runtime.cognition.SalienceVector
import java.time.Instant

object ArtifactCoordinatorContract {
    const val ENVELOPE_MIME_TYPE = "application/vnd.lifeos.collaborative-artifact+json"
    const val PROVENANCE_SOURCE = "lifeos.collaborative-artifact"
    const val PROVENANCE_ACTOR = "lifeos-runtime"
    const val SCHEMA = "lifeos.collaborative-artifact.v1"
}

/**
 * Complete productive ingress for one finalized artifact Photon. Implementations own persistence
 * and durable cognition submission as one canonical boundary.
 */
fun interface ArtifactPhotonIngress {
    suspend fun ingest(photon: Photon): ArtifactReentryReceipt
}

/** Legacy continuous-cognition adapter retained for compatibility; productive composition uses [ArtifactPhotonIngress]. */
fun interface ArtifactPhotonReentry {
    suspend fun submit(photon: Photon): ArtifactReentryReceipt
}

/**
 * Legacy re-entry adapter. It does not persist the Photon and therefore must not be used as the
 * productive artifact ingress. Android production composition binds [ArtifactPhotonIngress] to the
 * canonical Photon ingress instead.
 */
class ContinuousCognitionArtifactReentry(
    private val cognition: ContinuousCognitionEngine,
    private val targetModules: Set<String> = emptySet(),
    private val budget: CognitiveWorkBudget = CognitiveWorkBudget(
        maxDurationMs = 5_000,
        maxModuleInvocations = 16,
        maxNewPhotons = 16,
        maxNetworkCalls = 0,
    ),
) : ArtifactPhotonReentry {
    init {
        require(targetModules.none { it.isBlank() }) { "Artifact target modules must not be blank" }
    }

    override suspend fun submit(photon: Photon): ArtifactReentryReceipt {
        val delta = PhotonDelta(
            deltaId = "artifact:${photon.id.value}:revision:${photon.revision}",
            source = ArtifactCoordinatorContract.PROVENANCE_SOURCE,
            photonId = photon.id,
            revisionAfter = photon.revision,
            type = PhotonDeltaType.CREATED,
            importanceHint = photon.semanticMass,
            timestamp = photon.provenance.createdAt,
            causationId = photon.id.value,
            correlationId = photon.id.value,
        )
        val result = cognition.submit(
            delta = delta,
            priority = CognitivePriority.NORMAL,
            salience = SalienceVector(
                novelty = 1.0,
                relevance = 1.0,
                semanticMass = photon.semanticMass,
                confidenceImpact = photon.confidence,
            ),
            targetModules = targetModules,
            budget = budget,
        )
        return ArtifactReentryReceipt(
            accepted = result.accepted,
            durableTaskId = result.durableTaskId,
        )
    }
}

class ArtifactCoordinator(
    private val photons: PhotonRepository,
    private val ingress: ArtifactPhotonIngress,
    private val validator: ArtifactValidator = ArtifactValidator(),
) {
    suspend fun finalize(
        request: CollaborativeArtifactRequest,
        contributions: List<ArtifactContribution>,
        finalizedAt: Instant,
    ): ArtifactFinalizationResult {
        validator.requireValid(request, contributions, finalizedAt)
        val canonicalContributions = contributions.sortedWith(
            compareBy<ArtifactContribution>(
                { it.field },
                { it.module },
                { it.source },
                { it.id },
            )
        )
        val photonId = artifactPhotonId(request, canonicalContributions)
        val existing = photons.load(photonId)
        val photon: Photon
        val effectiveFinalizedAt: Instant
        if (existing == null) {
            photon = createPhoton(
                photonId = photonId,
                request = request,
                contributions = canonicalContributions,
                finalizedAt = finalizedAt,
            )
            effectiveFinalizedAt = finalizedAt
        } else {
            requireOwnedArtifact(existing)
            photon = existing
            effectiveFinalizedAt = existing.provenance.createdAt
        }

        val receipt = ingress.ingest(photon)
        return ArtifactFinalizationResult(
            artifact = CollaborativeArtifact(
                request = request,
                contributions = canonicalContributions,
                photon = photon,
                finalizedAt = effectiveFinalizedAt,
            ),
            reentry = receipt,
        )
    }

    private fun artifactPhotonId(
        request: CollaborativeArtifactRequest,
        contributions: List<ArtifactContribution>,
    ): PhotonId {
        val identity = ArtifactFingerprints.fingerprint(
            "collaborative-artifact-photon/v1",
            request.id.value,
            request.kind.name,
            request.title,
            request.targetMimeType,
            request.requestedAt.toString(),
            *request.requiredFields.sorted().toTypedArray(),
            *contributions.map { it.contentFingerprint() }.toTypedArray(),
        )
        return PhotonId("artifact:${request.id.value}:$identity")
    }

    private fun createPhoton(
        photonId: PhotonId,
        request: CollaborativeArtifactRequest,
        contributions: List<ArtifactContribution>,
        finalizedAt: Instant,
    ): Photon {
        val parentIds = contributions
            .flatMap { it.provenance.parentIds }
            .toSortedSet(compareBy { it.value })
        val confidence = contributions.minOf { it.confidence }
        val content = envelopeJson(request, contributions, finalizedAt)
        return Photon(
            id = photonId,
            revision = 1L,
            content = content,
            mimeType = ArtifactCoordinatorContract.ENVELOPE_MIME_TYPE,
            phase = PhotonPhase.CONVERGED,
            semanticMass = contributions.size.toDouble().coerceAtLeast(1.0),
            energy = 1.0,
            confidence = confidence,
            provenance = Provenance(
                source = ArtifactCoordinatorContract.PROVENANCE_SOURCE,
                actor = ArtifactCoordinatorContract.PROVENANCE_ACTOR,
                createdAt = finalizedAt,
                parentIds = parentIds,
            ),
            relations = parentIds.mapTo(linkedSetOf()) { parentId ->
                PhotonRelation(
                    target = parentId,
                    type = RelationType.DERIVED_FROM,
                )
            },
            tags = buildSet {
                add("artifact")
                add("artifact-kind:${request.kind.name.lowercase()}")
                add("artifact-id:${request.id.value}")
                contributions.map { it.field }.toSortedSet().forEach { field ->
                    add("artifact-field:$field")
                }
            },
        )
    }

    private fun requireOwnedArtifact(photon: Photon) {
        require(photon.mimeType == ArtifactCoordinatorContract.ENVELOPE_MIME_TYPE) {
            "Artifact Photon id collision for ${photon.id.value}: unexpected MIME type"
        }
        require(photon.provenance.source == ArtifactCoordinatorContract.PROVENANCE_SOURCE) {
            "Artifact Photon id collision for ${photon.id.value}: unexpected provenance source"
        }
        require(photon.revision == 1L) {
            "Artifact Photon ${photon.id.value} has unsupported revision ${photon.revision}"
        }
    }

    private fun envelopeJson(
        request: CollaborativeArtifactRequest,
        contributions: List<ArtifactContribution>,
        finalizedAt: Instant,
    ): String = buildString {
        append('{')
        append("\"schema\":"); appendJson(ArtifactCoordinatorContract.SCHEMA); append(',')
        append("\"artifactId\":"); appendJson(request.id.value); append(',')
        append("\"kind\":"); appendJson(request.kind.name); append(',')
        append("\"title\":"); appendJson(request.title); append(',')
        append("\"targetMimeType\":"); appendJson(request.targetMimeType); append(',')
        append("\"requestedAt\":"); appendJson(request.requestedAt.toString()); append(',')
        append("\"finalizedAt\":"); appendJson(finalizedAt.toString()); append(',')
        append("\"requiredFields\":[")
        request.requiredFields.sorted().forEachIndexed { index, field ->
            if (index > 0) append(',')
            appendJson(field)
        }
        append("],\"contributions\":[")
        contributions.forEachIndexed { index, contribution ->
            if (index > 0) append(',')
            append('{')
            append("\"id\":"); appendJson(contribution.id); append(',')
            append("\"module\":"); appendJson(contribution.module); append(',')
            append("\"field\":"); appendJson(contribution.field); append(',')
            append("\"source\":"); appendJson(contribution.source); append(',')
            append("\"confidence\":"); append(java.lang.Double.toString(contribution.confidence)); append(',')
            append("\"contributedAt\":"); appendJson(contribution.contributedAt.toString()); append(',')
            append("\"provenance\":{")
            append("\"source\":"); appendJson(contribution.provenance.source); append(',')
            append("\"actor\":"); appendJson(contribution.provenance.actor); append(',')
            append("\"createdAt\":"); appendJson(contribution.provenance.createdAt.toString()); append(',')
            append("\"parentIds\":[")
            contribution.provenance.parentIds.map { it.value }.sorted().forEachIndexed { parentIndex, parent ->
                if (parentIndex > 0) append(',')
                appendJson(parent)
            }
            append("]},\"content\":")
            appendJson(contribution.content)
            append('}')
        }
        append("]}")
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
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }
}
