package app.lifeos.core.runtime.life

import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.field.stableEvidenceOrder
import app.lifeos.core.runtime.world.StateDimensionId

/**
 * Converts a typed observation Photon into a separate semantic result and then, only when resolved,
 * into a normal user utterance. Raw/source evidence is never rewritten or masqueraded as semantics.
 */
class PerceptionSemanticPhotonFactory {
    fun recognition(
        observation: Photon,
        modality: PerceptionModality,
        recognizedText: String?,
        confidence: Double,
        traceFingerprint: String,
        candidates: List<PerceptionCandidate> = emptyList(),
    ): Photon {
        require(modality == PerceptionModality.SPEECH || modality == PerceptionModality.WRITING) {
            "Only speech/writing observations can become language recognition Photons"
        }
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(traceFingerprint.isNotBlank())
        val resolvedText = recognizedText?.trim()?.takeIf { it.isNotBlank() }
        val candidateFingerprint = StableCognitiveIds.fingerprint(
            "perception-semantic-candidates/v1",
            *candidates.sortedWith(
                compareByDescending<PerceptionCandidate> { it.confidence }
                    .thenBy { it.value }
                    .thenBy { it.semanticTag.orEmpty() }
            ).flatMap { candidate ->
                listOf(
                    candidate.value,
                    candidate.semanticTag.orEmpty(),
                    java.lang.Double.toHexString(candidate.confidence),
                )
            }.toTypedArray(),
        )
        val stateHash = CanonicalPhotonState.inputHash(observation).value
        val idFingerprint = StableCognitiveIds.fingerprint(
            "perception-semantic-photon/v2",
            observation.id.value,
            observation.revision.toString(),
            stateHash,
            modality.name,
            traceFingerprint,
            resolvedText.orEmpty(),
            java.lang.Double.toHexString(confidence),
            candidateFingerprint,
        )
        return Photon(
            id = PhotonId("perception-semantic-${idFingerprint.take(48)}"),
            content = resolvedText ?: "[unresolved:$traceFingerprint]",
            mimeType = "application/vnd.lifeos.${modality.name.lowercase()}-semantic+text",
            phase = PhotonPhase.CONVERGED,
            semanticMass = observation.semanticMass,
            energy = maxOf(observation.energy, confidence.coerceAtLeast(0.05)),
            confidence = confidence,
            provenance = Provenance(
                source = "perception-semantic:${modality.name.lowercase()}",
                actor = "lifeos-field",
                createdAt = observation.provenance.createdAt,
                parentIds = setOf(observation.id),
            ),
            relations = setOf(
                PhotonRelation(observation.id, RelationType.DERIVED_FROM),
                PhotonRelation(observation.id, RelationType.TRANSFORMS),
            ),
            tags = buildSet {
                add("perception")
                add("perception-semantic")
                add("perception-modality:${modality.name.lowercase()}")
                add("source-state:$stateHash")
                add("trace:$traceFingerprint")
                add("candidate-distribution:$candidateFingerprint")
                add(if (resolvedText == null) "recognition:unresolved" else "recognition:resolved")
                observation.tags.filterTo(this) {
                    it.startsWith("conversation:") || it.startsWith("turn:") || it.startsWith("source-asset:")
                }
            },
        )
    }

    fun asUserUtterance(
        recognition: Photon,
        modality: PerceptionModality,
        conversationId: String = "default",
    ): Photon {
        require(modality == PerceptionModality.SPEECH || modality == PerceptionModality.WRITING)
        require("recognition:resolved" in recognition.tags) {
            "Unresolved multimodal recognition cannot become a user utterance"
        }
        require(conversationId.isNotBlank())
        val fingerprint = StableCognitiveIds.fingerprint(
            "perception-user-utterance/v2",
            recognition.id.value,
            CanonicalPhotonState.inputHash(recognition).value,
            modality.name,
            conversationId,
            recognition.content,
        )
        return Photon(
            id = PhotonId("perception-chat-${fingerprint.take(48)}"),
            content = recognition.content,
            mimeType = "text/plain",
            phase = PhotonPhase.CREATED,
            semanticMass = recognition.semanticMass,
            energy = recognition.energy,
            confidence = recognition.confidence,
            provenance = Provenance(
                source = "multimodal-perception",
                actor = "user",
                createdAt = recognition.provenance.createdAt,
                parentIds = setOf(recognition.id),
            ),
            relations = setOf(
                PhotonRelation(recognition.id, RelationType.DERIVED_FROM),
                PhotonRelation(recognition.id, RelationType.TRANSFORMS),
            ),
            tags = setOf(
                "chat",
                "chat:user",
                "conversation:$conversationId",
                "turn:perception-${fingerprint.take(20)}",
                "input:${modality.name.lowercase()}",
                "perception-derived-utterance",
            ),
        )
    }
}


// ---- B457 Semantic Observation Projection ----

data class SemanticEvidenceCandidate(
    val stateDimension: StateDimensionId,
    val semanticKey: String,
    val kind: EvidenceKind = EvidenceKind.OBSERVATION,
    val confidence: Double,
    val reliability: EvidenceReliability,
    val validity: TemporalValidity = TemporalValidity.UNBOUNDED,
    val payload: EvidencePayload,
    val explanation: String,
) {
    init {
        require(semanticKey.isNotBlank()) { "Semantic evidence key must not be blank" }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Semantic evidence confidence must be finite in 0..1"
        }
        require(explanation.isNotBlank()) { "Semantic evidence explanation must not be blank" }
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "semantic-evidence-candidate/v1",
        stateDimension.value,
        semanticKey,
        kind.name,
        java.lang.Double.toHexString(confidence),
        java.lang.Double.toHexString(reliability.score),
        reliability.reason,
        validity.validFrom?.toString().orEmpty(),
        validity.validUntilExclusive?.toString().orEmpty(),
        payload.stableFingerprint(),
        explanation,
    )
}

interface SemanticObservationProjector {
    val projectorId: String
    val domainId: FieldDomainId

    fun supports(observation: InformationObservation): Boolean

    /**
     * Returns semantic candidates only. A projector cannot choose source Photon identity, source
     * revision or evidence authority; the central runtime binds those from the observation.
     */
    fun project(observation: InformationObservation): List<SemanticEvidenceCandidate>
}

data class SemanticProjectionResult(
    val projectorId: String,
    val domainId: FieldDomainId,
    val sourceObservationId: InformationObservationId,
    val sourcePhotonId: PhotonId,
    val sourcePhotonRevision: Long,
    val evidence: List<FieldEvidence>,
    val touchedStateDimensions: Set<StateDimensionId>,
) {
    init {
        require(projectorId.isNotBlank())
        require(sourcePhotonRevision > 0L)
        require(evidence == evidence.stableEvidenceOrder()) {
            "Semantic evidence must use stable evidence order"
        }
        require(
            evidence.all {
                it.domainId == domainId &&
                    it.sourcePhotonId == sourcePhotonId &&
                    it.sourceRevision == sourcePhotonRevision
            }
        ) {
            "Semantic projection evidence must retain exact source/domain binding"
        }
    }

    val directWorldStateMutationAllowed: Boolean
        get() = false
}

/**
 * B457 generic observation -> semantic evidence boundary.
 *
 * The source InformationObservation remains immutable. Domain projectors may interpret it, but the
 * runtime owns source identity, revision and epistemic authority. PROJECTED observations cannot be
 * promoted into confirmed transaction evidence merely by a domain projector.
 */
class SemanticProjectionRuntime(
    projectors: Collection<SemanticObservationProjector>,
) {
    private val orderedProjectors = projectors.sortedBy { it.projectorId }.also { ordered ->
        require(ordered.map { it.projectorId }.distinct().size == ordered.size) {
            "Semantic projector ids must be unique"
        }
        require(ordered.all { it.projectorId.isNotBlank() }) {
            "Semantic projector ids must not be blank"
        }
    }

    fun project(
        observation: InformationObservation,
        sourcePhoton: Photon,
    ): List<SemanticProjectionResult> {
        require(
            "observation-id:${observation.id.value}" in sourcePhoton.tags
        ) {
            "Semantic projection source Photon does not bind the supplied observation"
        }
        require(sourcePhoton.revision > 0L) {
            "Semantic projection requires a durable/revisioned source Photon"
        }

        return orderedProjectors
            .asSequence()
            .filter { it.supports(observation) }
            .map { projector ->
                val candidates = projector.project(observation)
                    .distinctBy { it.fingerprint }
                    .sortedWith(
                        compareBy<SemanticEvidenceCandidate> { it.stateDimension.value }
                            .thenBy { it.semanticKey }
                            .thenBy { it.fingerprint }
                    )

                candidates.forEach { candidate ->
                    require(
                        candidate.kind != EvidenceKind.TRANSACTION ||
                            observation.realization.representation == RepresentationLevel.ACTUAL
                    ) {
                        "Projected observation cannot become confirmed transaction evidence"
                    }
                    require(
                        candidate.kind != EvidenceKind.USER_CORRECTION ||
                            observation.realization.epistemicStatus == EpistemicStatus.OWNER_CONFIRMED
                    ) {
                        "User correction evidence requires owner-confirmed epistemic status"
                    }
                }

                val evidence = candidates.mapIndexed { ordinal, candidate ->
                    FieldEvidence.create(
                        domainId = projector.domainId,
                        sourcePhotonId = sourcePhoton.id,
                        sourceRevision = sourcePhoton.revision,
                        kind = candidate.kind,
                        semanticKey =
                            "${candidate.stateDimension.value}:${candidate.semanticKey}",
                        confidence = minOf(
                            observation.confidence ?: 1.0,
                            candidate.confidence,
                        ),
                        reliability = candidate.reliability,
                        authority = observation.authority.toFieldAuthority(),
                        observedAt = observation.observedAt,
                        validity = candidate.validity,
                        payload = candidate.payload,
                        explanation = candidate.explanation,
                        ordinal = ordinal,
                    )
                }.stableEvidenceOrder()

                SemanticProjectionResult(
                    projectorId = projector.projectorId,
                    domainId = projector.domainId,
                    sourceObservationId = observation.id,
                    sourcePhotonId = sourcePhoton.id,
                    sourcePhotonRevision = sourcePhoton.revision,
                    evidence = evidence,
                    touchedStateDimensions =
                        candidates.mapTo(sortedSetOf(compareBy { it.value })) {
                            it.stateDimension
                        },
                )
            }
            .toList()
    }

    private fun ObservationAuthorityClass.toFieldAuthority(): SourceAuthority = when (this) {
        ObservationAuthorityClass.DERIVED_INFERENCE ->
            SourceAuthority.UNVERIFIED
        ObservationAuthorityClass.UI_OBSERVATION ->
            SourceAuthority.UNVERIFIED
        ObservationAuthorityClass.PLATFORM_NOTIFICATION ->
            SourceAuthority.DOCUMENTED
        ObservationAuthorityClass.PLATFORM_PROVIDER ->
            SourceAuthority.PRIMARY_SOURCE
        ObservationAuthorityClass.OWNER_PROVIDED_EXPORT ->
            SourceAuthority.USER_PROVIDED
        ObservationAuthorityClass.AUTHENTICATED_API ->
            SourceAuthority.OFFICIAL
        ObservationAuthorityClass.AUTHORITATIVE_PROVIDER ->
            SourceAuthority.AUTHORITATIVE
    }
}
