package app.lifeos.core.runtime.research

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

enum class SubjectiveStateDimension {
    PERCEIVED_THREAT,
    SAFETY,
    AFFECTIVE_LOAD,
    DEFENSIVE_DRIVE,
    AGENCY,
    EXPLORATION_CAPACITY,
}

enum class SubjectiveStateEvidenceKind {
    DERIVED,
    OBSERVED,
    OWNER_CONFIRMED,
}

data class SubjectiveStateEvidence(
    val dimension: SubjectiveStateDimension,
    val value: Double,
    val confidence: Double,
    val kind: SubjectiveStateEvidenceKind,
    val sourceEvidenceIds: List<String>,
) {
    init {
        require(value.isFinite() && value in 0.0..1.0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(sourceEvidenceIds == sourceEvidenceIds.distinct().sorted())
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "subjective-state-evidence/v1",
        dimension.name,
        java.lang.Double.toHexString(value),
        java.lang.Double.toHexString(confidence),
        kind.name,
        *sourceEvidenceIds.toTypedArray(),
    )
}

data class SubjectiveStateHypothesis private constructor(
    val personalWorldFingerprint: String,
    val ownerAgencyFingerprint: String,
    val values: Map<SubjectiveStateDimension, Double>,
    val selectedEvidenceFingerprints: List<String>,
    val ownerCorrectionFingerprints: List<String>,
    val asOf: Instant,
    val fingerprint: String,
) {
    init {
        require(personalWorldFingerprint.isNotBlank())
        require(ownerAgencyFingerprint.isNotBlank())
        require(values.keys == values.keys.sortedBy { it.name }.toSet())
        require(values.values.all { it.isFinite() && it in 0.0..1.0 })
        require(selectedEvidenceFingerprints == selectedEvidenceFingerprints.distinct().sorted())
        require(ownerCorrectionFingerprints == ownerCorrectionFingerprints.distinct().sorted())
    }

    val factualWorldAuthority: Boolean
        get() = false

    val diagnosticAuthority: Boolean
        get() = false

    val effectAuthority: Boolean
        get() = false

    companion object {
        fun create(
            personalWorld: PersonalWorldSnapshot,
            ownerAgency: OwnerAgencySnapshot,
            evidence: Collection<SubjectiveStateEvidence>,
            asOf: Instant,
        ): SubjectiveStateHypothesis {
            val selected = evidence
                .groupBy { it.dimension }
                .mapValues { (_, candidates) ->
                    candidates.sortedWith(
                        compareByDescending<SubjectiveStateEvidence> {
                            it.kind == SubjectiveStateEvidenceKind.OWNER_CONFIRMED
                        }
                            .thenByDescending { it.confidence }
                            .thenBy { it.fingerprint }
                    ).first()
                }
                .toSortedMap(compareBy { it.name })

            val values = selected.mapValues { it.value.value }
            val selectedFingerprints = selected.values
                .map { it.fingerprint }
                .distinct()
                .sorted()
            val ownerCorrections = selected.values
                .filter { it.kind == SubjectiveStateEvidenceKind.OWNER_CONFIRMED }
                .map { it.fingerprint }
                .distinct()
                .sorted()

            val fingerprintParts = selected.entries.flatMap { (dimension, signal) ->
                listOf(
                    "dimension:" + dimension.name,
                    "value:" + java.lang.Double.toHexString(signal.value),
                    "evidence:" + signal.fingerprint,
                )
            }
            val fingerprint = StableFieldIds.fingerprint(
                "subjective-state-hypothesis/v1",
                personalWorld.fingerprint,
                ownerAgency.fingerprint,
                asOf.toString(),
                *fingerprintParts.toTypedArray(),
            )

            return SubjectiveStateHypothesis(
                personalWorldFingerprint = personalWorld.fingerprint,
                ownerAgencyFingerprint = ownerAgency.fingerprint,
                values = values,
                selectedEvidenceFingerprints = selectedFingerprints,
                ownerCorrectionFingerprints = ownerCorrections,
                asOf = asOf,
                fingerprint = fingerprint,
            )
        }
    }
}

class SeinEvidenceIntegrator {
    fun infer(
        personalWorld: PersonalWorldSnapshot,
        ownerAgency: OwnerAgencySnapshot,
        evidence: Collection<SubjectiveStateEvidence>,
        asOf: Instant,
    ): SubjectiveStateHypothesis =
        SubjectiveStateHypothesis.create(
            personalWorld = personalWorld,
            ownerAgency = ownerAgency,
            evidence = evidence,
            asOf = asOf,
        )
}
