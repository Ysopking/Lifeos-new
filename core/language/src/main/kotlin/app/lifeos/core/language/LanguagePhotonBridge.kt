package app.lifeos.core.language

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType

/** Converts probabilistic language interpretation into revisionable, provenance-bound semantic photons. */
class LanguagePhotonBridge {
    fun bridge(result: LanguageUnderstandingResult, utterancePhoton: Photon): List<Photon> {
        val parent = setOf(utterancePhoton.id)
        val provenance = Provenance(
            source = "language-understanding",
            actor = "core:language",
            parentIds = parent,
        )
        val derived = mutableListOf<Photon>()

        derived += Photon(
            id = PhotonId.new(),
            content = result.goal.objective,
            confidence = result.goal.confidence,
            provenance = provenance,
            relations = setOf(PhotonRelation(utterancePhoton.id, RelationType.DERIVED_FROM)),
            tags = setOf("semantic-claim", "intent:${result.goal.intent.name.lowercase()}"),
        )

        result.goal.entities.forEach { entity ->
            derived += Photon(
                id = PhotonId.new(),
                content = entity.normalizedValue,
                confidence = entity.confidence,
                provenance = provenance,
                relations = setOf(PhotonRelation(utterancePhoton.id, RelationType.DERIVED_FROM)),
                tags = setOf("semantic-entity", "entity:${entity.type.name.lowercase()}"),
            )
        }

        result.goal.constraints.forEach { constraint ->
            derived += Photon(
                id = PhotonId.new(),
                content = "${constraint.key}=${constraint.value}",
                confidence = constraint.confidence,
                provenance = provenance,
                relations = setOf(PhotonRelation(utterancePhoton.id, RelationType.DERIVED_FROM)),
                tags = setOf("semantic-constraint", "constraint:${constraint.key}"),
            )
        }
        return derived
    }
}
