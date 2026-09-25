package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.android.NotificationObservationProjector
import app.lifeos.core.runtime.android.SemanticAppUiObservationProjector
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.SemanticProjectionResult
import app.lifeos.core.runtime.life.SemanticProjectionRuntime

internal class AuthorizedObservationSemanticProjectionRuntime(
    private val projection: SemanticProjectionRuntime =
        SemanticProjectionRuntime(
            listOf(
                NotificationObservationProjector(),
                SemanticAppUiObservationProjector(),
            )
        ),
    private val persistDerived: suspend (Photon) -> Unit,
) {
    suspend fun project(
        observation: InformationObservation,
        sourcePhoton: Photon,
    ): List<Photon> {
        val outputs = projection.project(observation, sourcePhoton)
            .filter { it.evidence.isNotEmpty() }
            .map { SemanticProjectionPhotonFactory.create(it, sourcePhoton) }
            .sortedBy { it.id.value }
        outputs.forEach { persistDerived(it) }
        return outputs
    }
}

internal object SemanticProjectionPhotonFactory {
    const val MIME_TYPE = "application/vnd.lifeos.semantic-projection+text"

    fun create(
        result: SemanticProjectionResult,
        sourcePhoton: Photon,
    ): Photon {
        require(result.sourcePhotonId == sourcePhoton.id)
        require(result.sourcePhotonRevision == sourcePhoton.revision)
        require(result.evidence.isNotEmpty())

        val fingerprint = StableCognitiveIds.fingerprint(
            "semantic-projection-photon/v1",
            result.projectorId,
            result.domainId.value,
            result.sourceObservationId.value,
            sourcePhoton.id.value,
            sourcePhoton.revision.toString(),
            *result.evidence.map { it.id.value }.toTypedArray(),
        )

        return Photon(
            id = PhotonId("semantic-projection:" + fingerprint),
            revision = 1L,
            content = buildString {
                appendLine("schema=1")
                appendLine("projector_id=" + result.projectorId)
                appendLine("domain_id=" + result.domainId.value)
                appendLine("source_observation_id=" + result.sourceObservationId.value)
                appendLine("source_photon_id=" + sourcePhoton.id.value)
                appendLine("source_revision=" + sourcePhoton.revision)
                appendLine(
                    "state_dimensions=" +
                        result.touchedStateDimensions.map { it.value }.sorted().joinToString(",")
                )
                append(
                    "evidence_ids=" +
                        result.evidence.map { it.id.value }.sorted().joinToString(",")
                )
            },
            mimeType = MIME_TYPE,
            semanticMass = 0.7,
            energy = 0.45,
            confidence = result.evidence.minOf { it.confidence },
            provenance = Provenance(
                source = "lifeos.semantic-projection",
                actor = result.projectorId,
                createdAt = sourcePhoton.provenance.createdAt,
                parentIds = setOf(sourcePhoton.id),
            ),
            relations = setOf(
                PhotonRelation(
                    target = sourcePhoton.id,
                    type = RelationType.DERIVED_FROM,
                )
            ),
            tags = buildSet {
                add("semantic-projection")
                add("semantic-projection:" + result.projectorId)
                add("domain:" + result.domainId.value)
                add("source-observation:" + result.sourceObservationId.value)
                add("source-revision:" + sourcePhoton.revision)
                result.touchedStateDimensions.map { it.value }.sorted()
                    .forEach { add("state-dimension:" + it) }
                result.evidence.map { it.id.value }.sorted()
                    .forEach { add("field-evidence:" + it) }
            },
        )
    }
}
