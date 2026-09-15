package app.lifeos.core.runtime.world

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldDimensionValue
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.runtime.module.ModuleEpistemicState

/**
 * Projects one module's epistemic state into the canonical World Formula type system.
 *
 * The projection is informational only. AUTHORITY here means epistemic/routing standing and must
 * never be interpreted as OwnerPolicy, permission, capability or external-action authority.
 */
class ModuleWorldSignalProjector {
    fun project(
        state: ModuleEpistemicState,
        domainId: FieldDomainId,
    ): WorldFormulaInputSnapshot {
        val knowledge = state.knowledgeFor(domainId)
        val expertise = state.expertiseFor(domainId)
        val baseProvenance = setOf(
            state.identity.stableFingerprint,
            state.stateFingerprint,
            state.authority.fingerprint(),
        )

        val values = buildList {
            add(
                WorldDimensionValue(
                    dimension = WorldSignalDimension.AUTHORITY,
                    value = state.authority.effectiveAuthority,
                    confidence = 1.0,
                    provenanceFingerprints = baseProvenance,
                )
            )
            add(
                WorldDimensionValue(
                    dimension = WorldSignalDimension.MODULE_CONFIDENCE,
                    value = state.currentConfidence,
                    confidence = 1.0,
                    provenanceFingerprints = baseProvenance,
                )
            )
            knowledge?.let { domainKnowledge ->
                val provenance = baseProvenance + domainKnowledge.bindings.map { it.stableKey }
                add(
                    WorldDimensionValue(
                        dimension = WorldSignalDimension.KNOWLEDGE_STRENGTH,
                        value = domainKnowledge.knowledgeStrength,
                        confidence = domainKnowledge.confidence,
                        provenanceFingerprints = provenance,
                    )
                )
            }
            expertise?.let { domainExpertise ->
                val provenance = baseProvenance + domainExpertise.learningEvidenceFingerprints
                add(
                    WorldDimensionValue(
                        dimension = WorldSignalDimension.EXPERTISE,
                        value = domainExpertise.effectiveExpertise,
                        confidence = domainExpertise.calibration,
                        provenanceFingerprints = provenance.ifEmpty { baseProvenance },
                    )
                )
                add(
                    WorldDimensionValue(
                        dimension = WorldSignalDimension.CALIBRATION,
                        value = domainExpertise.calibration,
                        confidence = if (domainExpertise.observedOutcomes > 0L) 1.0 else 0.5,
                        provenanceFingerprints = provenance.ifEmpty { baseProvenance },
                    )
                )
                add(
                    WorldDimensionValue(
                        dimension = WorldSignalDimension.EXPERIENCE_DEPTH,
                        value = domainExpertise.experienceDepth,
                        confidence = if (domainExpertise.observedOutcomes > 0L) 1.0 else 0.5,
                        provenanceFingerprints = provenance.ifEmpty { baseProvenance },
                    )
                )
            }
        }.sortedBy { it.dimension.name }

        val sourceFingerprint = StableFieldIds.fingerprint(
            "module-world-signal/v1",
            state.stateFingerprint,
            domainId.value,
            *values.map { it.fingerprint() }.toTypedArray(),
        )
        return WorldFormulaInputSnapshot(
            target = WorldTargetRef(
                kind = WorldNodeKind.MODULE,
                key = "module:${state.identity.moduleId}:${domainId.value}",
            ),
            vector = WorldFieldVector(values),
            sourceSnapshotFingerprint = sourceFingerprint,
        )
    }
}
