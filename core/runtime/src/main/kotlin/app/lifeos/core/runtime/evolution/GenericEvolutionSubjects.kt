package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.extension.ExtensionCandidate
import app.lifeos.core.runtime.extension.ExtensionKind
import app.lifeos.core.runtime.extension.ExtensionValidationBundle
import app.lifeos.core.runtime.extension.ExtensionWorkshopArtifact

enum class ControlledEvolutionSubjectKind {
    SEMANTIC_EXTENSION,
    STRATEGY,
    WORLD_TOPOLOGY,
    WORLD_COEFFICIENT_SET,
    WORLD_EQUATION_VERSION,
}

data class ControlledEvolutionSubjectRef private constructor(
    val id: String,
    val kind: ControlledEvolutionSubjectKind,
    val candidateId: String,
    val sourceArtifactId: String,
    val validationBundleId: String,
    val candidateFingerprint: String,
    val baselineFingerprint: String?,
) {
    init {
        require(id.isNotBlank())
        require(candidateId.isNotBlank())
        require(sourceArtifactId.isNotBlank())
        require(validationBundleId.isNotBlank())
        require(candidateFingerprint.isNotBlank())
        require(baselineFingerprint == null || baselineFingerprint.isNotBlank())
        require(id == expectedId()) {
            "Controlled evolution subject id does not match content"
        }
    }

    val activationAllowed: Boolean
        get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "controlled-evolution-subject/v1",
        kind.name,
        candidateId,
        sourceArtifactId,
        validationBundleId,
        candidateFingerprint,
        baselineFingerprint.orEmpty(),
    )

    private fun expectedId(): String = "evolution-subject:${fingerprint()}"

    companion object {
        fun create(
            kind: ControlledEvolutionSubjectKind,
            candidateId: String,
            sourceArtifactId: String,
            validationBundleId: String,
            candidateFingerprint: String,
            baselineFingerprint: String? = null,
        ): ControlledEvolutionSubjectRef {
            val fingerprint = StableFieldIds.fingerprint(
                "controlled-evolution-subject/v1",
                kind.name,
                candidateId,
                sourceArtifactId,
                validationBundleId,
                candidateFingerprint,
                baselineFingerprint.orEmpty(),
            )
            return ControlledEvolutionSubjectRef(
                id = "evolution-subject:$fingerprint",
                kind = kind,
                candidateId = candidateId,
                sourceArtifactId = sourceArtifactId,
                validationBundleId = validationBundleId,
                candidateFingerprint = candidateFingerprint,
                baselineFingerprint = baselineFingerprint,
            )
        }
    }
}

data class ExtensionEvolutionAdmission(
    val candidateId: String,
    val validationBundleId: String,
    val subjects: List<ControlledEvolutionSubjectRef>,
) {
    init {
        require(candidateId.isNotBlank())
        require(validationBundleId.isNotBlank())
        require(subjects.isNotEmpty())
        require(subjects.map { it.id }.distinct().size == subjects.size)
        require(subjects.all { it.candidateId == candidateId })
        require(subjects.all { it.validationBundleId == validationBundleId })
    }

    val activationAllowed: Boolean
        get() = false
}

/**
 * B157 admission only: verified extension evidence becomes generic Controlled Evolution subjects.
 * The existing Evolution holdout/shadow/adoption/canary authorities remain unchanged.
 */
class ExtensionEvolutionAdmissionGate {
    fun admit(
        candidate: ExtensionCandidate,
        workshopArtifact: ExtensionWorkshopArtifact,
        validation: ExtensionValidationBundle,
        requestedSubjects: Set<ControlledEvolutionSubjectKind>,
        baselineFingerprints: Map<ControlledEvolutionSubjectKind, String> = emptyMap(),
    ): ExtensionEvolutionAdmission {
        require(candidate.id == workshopArtifact.extensionCandidateId) {
            "Evolution admission workshop artifact belongs to another extension candidate"
        }
        require(workshopArtifact.id == validation.workshopArtifactId) {
            "Evolution admission validation belongs to another workshop artifact"
        }
        require(!candidate.activationAllowed)
        require(!workshopArtifact.activationAllowed)
        require(!validation.activationAllowed)
        require(!validation.promotionAllowed)
        require(requestedSubjects.isNotEmpty()) {
            "Evolution admission requires at least one explicit subject kind"
        }

        requestedSubjects.forEach { kind ->
            require(kind.compatibleWith(candidate.requestedKinds)) {
                "Evolution subject $kind is incompatible with extension kinds ${candidate.requestedKinds}"
            }
        }
        baselineFingerprints.forEach { (kind, fingerprint) ->
            require(kind in requestedSubjects) {
                "Baseline fingerprint supplied for an unrequested evolution subject"
            }
            require(fingerprint.isNotBlank()) {
                "Evolution baseline fingerprint must not be blank"
            }
        }

        val subjects = requestedSubjects
            .sortedBy { it.name }
            .map { kind ->
                ControlledEvolutionSubjectRef.create(
                    kind = kind,
                    candidateId = candidate.id,
                    sourceArtifactId = workshopArtifact.id,
                    validationBundleId = validation.id,
                    candidateFingerprint = candidate.fingerprint(),
                    baselineFingerprint = baselineFingerprints[kind],
                )
            }

        return ExtensionEvolutionAdmission(
            candidateId = candidate.id,
            validationBundleId = validation.id,
            subjects = subjects,
        )
    }

    private fun ControlledEvolutionSubjectKind.compatibleWith(
        extensionKinds: Set<ExtensionKind>,
    ): Boolean = when (this) {
        ControlledEvolutionSubjectKind.SEMANTIC_EXTENSION ->
            ExtensionKind.WORLD_SIGNAL_PACK in extensionKinds ||
                ExtensionKind.WORLD_PROJECTION_PACK in extensionKinds
        ControlledEvolutionSubjectKind.WORLD_TOPOLOGY ->
            ExtensionKind.WORLD_TOPOLOGY_PACK in extensionKinds
        ControlledEvolutionSubjectKind.WORLD_COEFFICIENT_SET,
        ControlledEvolutionSubjectKind.WORLD_EQUATION_VERSION ->
            ExtensionKind.WORLD_EQUATION_PACK in extensionKinds
        ControlledEvolutionSubjectKind.STRATEGY -> false
    }
}
