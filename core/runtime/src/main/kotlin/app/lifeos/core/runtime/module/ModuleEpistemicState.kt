package app.lifeos.core.runtime.module

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.runtime.informationasset.InformationAssetId
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionId
import java.time.Instant
import kotlin.math.abs

/**
 * Epistemic/routing authority only. This type never grants security, OwnerPolicy, capability,
 * mutation, promotion or external-action permission.
 */
data class ModuleAuthorityProfile(
    val architecturalFloor: Double,
    val architecturalBaseline: Double,
    val architecturalCeiling: Double = 1.0,
    val learnedAdjustment: Double = 0.0,
    val maxLearnedAdjustment: Double = 0.20,
    val learningEvidenceFingerprints: Set<String> = emptySet(),
) {
    init {
        require(architecturalFloor.isFinite() && architecturalFloor in 0.0..1.0)
        require(architecturalBaseline.isFinite() && architecturalBaseline in 0.0..1.0)
        require(architecturalCeiling.isFinite() && architecturalCeiling in 0.0..1.0)
        require(architecturalFloor <= architecturalBaseline) {
            "Module authority floor cannot exceed its architectural baseline"
        }
        require(architecturalBaseline <= architecturalCeiling) {
            "Module authority baseline cannot exceed its architectural ceiling"
        }
        require(maxLearnedAdjustment.isFinite() && maxLearnedAdjustment in 0.0..1.0)
        require(learnedAdjustment.isFinite() && abs(learnedAdjustment) <= maxLearnedAdjustment) {
            "Learned module authority adjustment exceeds its configured bound"
        }
        require(learningEvidenceFingerprints.none { it.isBlank() })
        if (learnedAdjustment != 0.0) {
            require(learningEvidenceFingerprints.isNotEmpty()) {
                "Learned module authority adjustment requires outcome evidence"
            }
        }
    }

    val effectiveAuthority: Double =
        (architecturalBaseline + learnedAdjustment).coerceIn(architecturalFloor, architecturalCeiling)

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "module-authority-profile/v1",
        java.lang.Double.toHexString(architecturalFloor),
        java.lang.Double.toHexString(architecturalBaseline),
        java.lang.Double.toHexString(architecturalCeiling),
        java.lang.Double.toHexString(learnedAdjustment),
        java.lang.Double.toHexString(maxLearnedAdjustment),
        *learningEvidenceFingerprints.sorted().toTypedArray(),
    )
}

data class ModuleKnowledgeBinding(
    val domainId: FieldDomainId,
    val semanticKey: String,
    val assetId: InformationAssetId,
    val assetRevisionId: InformationAssetRevisionId,
    val assetStateHash: String,
    val confidence: Double,
    val observedAt: Instant,
) {
    init {
        require(semanticKey.isNotBlank())
        require(assetStateHash.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }

    val stableKey: String = StableFieldIds.fingerprint(
        "module-knowledge-binding/v1",
        domainId.value,
        semanticKey,
        assetId.value,
        assetRevisionId.value,
        assetStateHash,
        java.lang.Double.toHexString(confidence),
        observedAt.toString(),
    )
}

data class ModuleDomainKnowledgeState(
    val domainId: FieldDomainId,
    val knowledgeDepth: Double,
    val evidenceCoverage: Double,
    val confidence: Double,
    val bindings: List<ModuleKnowledgeBinding>,
) {
    init {
        require(knowledgeDepth.isFinite() && knowledgeDepth in 0.0..1.0)
        require(evidenceCoverage.isFinite() && evidenceCoverage in 0.0..1.0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(bindings.isNotEmpty()) { "Module domain knowledge requires exact knowledge bindings" }
        require(bindings.all { it.domainId == domainId }) { "Knowledge binding domain mismatch" }
        require(bindings == bindings.distinctBy { it.stableKey }.sortedBy { it.stableKey }) {
            "Module knowledge bindings must be unique and deterministically ordered"
        }
    }

    /** Conservative knowledge score: one weak axis prevents an inflated knowledge claim. */
    val knowledgeStrength: Double = minOf(knowledgeDepth, evidenceCoverage, confidence)

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "module-domain-knowledge/v1",
        domainId.value,
        java.lang.Double.toHexString(knowledgeDepth),
        java.lang.Double.toHexString(evidenceCoverage),
        java.lang.Double.toHexString(confidence),
        *bindings.map { it.stableKey }.toTypedArray(),
    )
}

data class ModuleExpertiseState(
    val domainId: FieldDomainId,
    val baselineExpertise: Double,
    val learnedAdjustment: Double = 0.0,
    val maxLearnedAdjustment: Double = 0.25,
    val calibration: Double = 0.5,
    val observedOutcomes: Long = 0,
    val successfulOutcomeFraction: Double = 0.0,
    val learningEvidenceFingerprints: Set<String> = emptySet(),
) {
    init {
        require(baselineExpertise.isFinite() && baselineExpertise in 0.0..1.0)
        require(maxLearnedAdjustment.isFinite() && maxLearnedAdjustment in 0.0..1.0)
        require(learnedAdjustment.isFinite() && abs(learnedAdjustment) <= maxLearnedAdjustment) {
            "Learned expertise adjustment exceeds its configured bound"
        }
        require(calibration.isFinite() && calibration in 0.0..1.0)
        require(observedOutcomes >= 0)
        require(successfulOutcomeFraction.isFinite() && successfulOutcomeFraction in 0.0..1.0)
        require(learningEvidenceFingerprints.none { it.isBlank() })
        if (observedOutcomes == 0L) {
            require(successfulOutcomeFraction == 0.0) {
                "Successful outcome fraction must be zero before any outcome is observed"
            }
        }
        if (learnedAdjustment != 0.0 || observedOutcomes > 0L) {
            require(learningEvidenceFingerprints.isNotEmpty()) {
                "Learned expertise or experience requires outcome evidence"
            }
        }
    }

    val effectiveExpertise: Double = (baselineExpertise + learnedAdjustment).coerceIn(0.0, 1.0)

    /** Saturating experience measure; raw outcome count remains available for auditing. */
    val experienceDepth: Double = if (observedOutcomes == 0L) {
        0.0
    } else {
        observedOutcomes.toDouble() / (observedOutcomes.toDouble() + EXPERIENCE_HALF_SATURATION)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "module-expertise-state/v1",
        domainId.value,
        java.lang.Double.toHexString(baselineExpertise),
        java.lang.Double.toHexString(learnedAdjustment),
        java.lang.Double.toHexString(maxLearnedAdjustment),
        java.lang.Double.toHexString(calibration),
        observedOutcomes.toString(),
        java.lang.Double.toHexString(successfulOutcomeFraction),
        *learningEvidenceFingerprints.sorted().toTypedArray(),
    )

    private companion object {
        const val EXPERIENCE_HALF_SATURATION = 32.0
    }
}

data class ModuleResourceProfile(
    val cpuIntensity: Double,
    val memoryIntensity: Double,
    val ioIntensity: Double,
    val networkIntensity: Double,
    val thermalIntensity: Double,
    val maxParallelism: Int = 1,
) {
    init {
        require(
            listOf(cpuIntensity, memoryIntensity, ioIntensity, networkIntensity, thermalIntensity)
                .all { it.isFinite() && it in 0.0..1.0 },
        ) { "Module resource intensities must be finite and in 0..1" }
        require(maxParallelism in 1..256) { "Module maxParallelism must be in 1..256" }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "module-resource-profile/v1",
        java.lang.Double.toHexString(cpuIntensity),
        java.lang.Double.toHexString(memoryIntensity),
        java.lang.Double.toHexString(ioIntensity),
        java.lang.Double.toHexString(networkIntensity),
        java.lang.Double.toHexString(thermalIntensity),
        maxParallelism.toString(),
    )

    companion object {
        val LIGHT = ModuleResourceProfile(
            cpuIntensity = 0.10,
            memoryIntensity = 0.10,
            ioIntensity = 0.05,
            networkIntensity = 0.0,
            thermalIntensity = 0.05,
            maxParallelism = 4,
        )
    }
}

/**
 * Durable cognitive state of one module implementation. Knowledge is referenced by exact,
 * revision-safe InformationAsset bindings instead of copying domain facts into a second store.
 */
data class ModuleEpistemicState(
    val identity: ModuleIdentity,
    val revision: Long,
    val previousStateFingerprint: String?,
    val authority: ModuleAuthorityProfile,
    val knowledge: List<ModuleDomainKnowledgeState>,
    val expertise: List<ModuleExpertiseState>,
    val currentConfidence: Double,
    val resourceProfile: ModuleResourceProfile,
    val updatedAt: Instant,
) {
    init {
        require(revision > 0) { "Module epistemic revision must be positive" }
        require(currentConfidence.isFinite() && currentConfidence in 0.0..1.0)
        require(knowledge == knowledge.sortedBy { it.domainId.value }) {
            "Module knowledge states must be deterministically ordered"
        }
        require(knowledge.map { it.domainId }.distinct().size == knowledge.size) {
            "Module knowledge domains must be unique"
        }
        require(expertise == expertise.sortedBy { it.domainId.value }) {
            "Module expertise states must be deterministically ordered"
        }
        require(expertise.map { it.domainId }.distinct().size == expertise.size) {
            "Module expertise domains must be unique"
        }
        if (revision == 1L) {
            require(previousStateFingerprint == null) {
                "Initial module epistemic state cannot have a predecessor"
            }
        } else {
            require(!previousStateFingerprint.isNullOrBlank()) {
                "Non-initial module epistemic state must bind its predecessor fingerprint"
            }
        }
    }

    val stateFingerprint: String = StableFieldIds.fingerprint(
        "module-epistemic-state/v1",
        identity.stableFingerprint,
        revision.toString(),
        previousStateFingerprint.orEmpty(),
        authority.fingerprint(),
        java.lang.Double.toHexString(currentConfidence),
        resourceProfile.fingerprint(),
        updatedAt.toString(),
        *knowledge.map { it.fingerprint() }.toTypedArray(),
        *expertise.map { it.fingerprint() }.toTypedArray(),
    )

    fun knowledgeFor(domainId: FieldDomainId): ModuleDomainKnowledgeState? =
        knowledge.firstOrNull { it.domainId == domainId }

    fun expertiseFor(domainId: FieldDomainId): ModuleExpertiseState? =
        expertise.firstOrNull { it.domainId == domainId }

    fun next(
        authority: ModuleAuthorityProfile = this.authority,
        knowledge: List<ModuleDomainKnowledgeState> = this.knowledge,
        expertise: List<ModuleExpertiseState> = this.expertise,
        currentConfidence: Double = this.currentConfidence,
        resourceProfile: ModuleResourceProfile = this.resourceProfile,
        updatedAt: Instant,
    ): ModuleEpistemicState = ModuleEpistemicState(
        identity = identity,
        revision = Math.addExact(revision, 1L),
        previousStateFingerprint = stateFingerprint,
        authority = authority,
        knowledge = knowledge.sortedBy { it.domainId.value },
        expertise = expertise.sortedBy { it.domainId.value },
        currentConfidence = currentConfidence,
        resourceProfile = resourceProfile,
        updatedAt = updatedAt,
    )

    companion object {
        fun initial(
            identity: ModuleIdentity,
            authority: ModuleAuthorityProfile,
            knowledge: List<ModuleDomainKnowledgeState> = emptyList(),
            expertise: List<ModuleExpertiseState> = emptyList(),
            currentConfidence: Double,
            resourceProfile: ModuleResourceProfile = ModuleResourceProfile.LIGHT,
            updatedAt: Instant,
        ): ModuleEpistemicState = ModuleEpistemicState(
            identity = identity,
            revision = 1L,
            previousStateFingerprint = null,
            authority = authority,
            knowledge = knowledge.sortedBy { it.domainId.value },
            expertise = expertise.sortedBy { it.domainId.value },
            currentConfidence = currentConfidence,
            resourceProfile = resourceProfile,
            updatedAt = updatedAt,
        )
    }
}
