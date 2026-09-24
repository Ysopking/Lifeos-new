package app.lifeos.core.runtime.state

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.GapSeverity

sealed interface WorldGap {
    val id: String
    val domain: FieldDomainId
    val severity: GapSeverity
    val reason: String

    data class Perception(
        override val domain: FieldDomainId,
        val missingDimensions: Set<StateDimensionId>,
        val staleDimensions: Set<StateDimensionId> = emptySet(),
        override val severity: GapSeverity = GapSeverity.BLOCKING,
        override val reason: String,
    ) : WorldGap {
        init {
            require(missingDimensions.isNotEmpty() || staleDimensions.isNotEmpty())
            require(reason.isNotBlank())
        }

        override val id: String = "world-gap:" + StableFieldIds.fingerprint(
            "world-gap-perception/v1",
            domain.value,
            severity.name,
            reason,
            *missingDimensions.map { "missing:${it.value}" }.sorted().toTypedArray(),
            *staleDimensions.map { "stale:${it.value}" }.sorted().toTypedArray(),
        )
    }

    data class Capability(
        override val domain: FieldDomainId,
        val capabilityId: String,
        val providerCandidates: Set<String> = emptySet(),
        override val severity: GapSeverity = GapSeverity.BLOCKING,
        override val reason: String,
    ) : WorldGap {
        init {
            require(capabilityId.isNotBlank())
            require(providerCandidates.none { it.isBlank() })
            require(reason.isNotBlank())
        }

        override val id: String = "world-gap:" + StableFieldIds.fingerprint(
            "world-gap-capability/v1",
            domain.value,
            capabilityId,
            severity.name,
            reason,
            *providerCandidates.sorted().toTypedArray(),
        )
    }

    data class Consistency(
        override val domain: FieldDomainId,
        val conflictingDimensions: Set<StateDimensionId>,
        val evidenceIds: Set<String>,
        override val severity: GapSeverity = GapSeverity.BLOCKING,
        override val reason: String,
    ) : WorldGap {
        init {
            require(conflictingDimensions.isNotEmpty())
            require(evidenceIds.none { it.isBlank() })
            require(reason.isNotBlank())
        }

        override val id: String = "world-gap:" + StableFieldIds.fingerprint(
            "world-gap-consistency/v1",
            domain.value,
            severity.name,
            reason,
            *conflictingDimensions.map { it.value }.sorted().toTypedArray(),
            *evidenceIds.sorted().toTypedArray(),
        )
    }

    data class Verification(
        override val domain: FieldDomainId,
        val actionGraphId: String,
        val expectedStateContract: String,
        val missingObservationContract: String,
        override val severity: GapSeverity = GapSeverity.BLOCKING,
        override val reason: String,
    ) : WorldGap {
        init {
            require(actionGraphId.isNotBlank())
            require(expectedStateContract.isNotBlank())
            require(missingObservationContract.isNotBlank())
            require(reason.isNotBlank())
        }

        override val id: String = "world-gap:" + StableFieldIds.fingerprint(
            "world-gap-verification/v1",
            domain.value,
            actionGraphId,
            expectedStateContract,
            missingObservationContract,
            severity.name,
            reason,
        )
    }
}

/**
 * B456 bridge from B455 StateSufficiency to explicit world gaps.
 *
 * Perception and consistency are intentionally separate: contradictory evidence is not represented
 * as merely "more information needed".
 */
object StateWorldGapDetector {
    fun detect(
        contract: StateContract,
        result: StateSufficiencyResult,
    ): List<WorldGap> {
        require(result.contractId == contract.id)
        require(result.contractFingerprint == contract.fingerprint)

        return buildList {
            if (result.missing.isNotEmpty() || result.stale.isNotEmpty()) {
                add(
                    WorldGap.Perception(
                        domain = contract.domain,
                        missingDimensions = result.missing,
                        staleDimensions = result.stale,
                        reason = when {
                            result.missing.isNotEmpty() && result.stale.isNotEmpty() ->
                                "state-dimensions-missing-and-stale"
                            result.missing.isNotEmpty() ->
                                "state-dimensions-missing"
                            else ->
                                "state-dimensions-stale"
                        },
                    )
                )
            }
            if (result.conflicted.isNotEmpty()) {
                add(
                    WorldGap.Consistency(
                        domain = contract.domain,
                        conflictingDimensions = result.conflicted,
                        evidenceIds = result.supportingEvidenceIds,
                        reason = "state-evidence-conflict",
                    )
                )
            }
        }.sortedBy { it.id }
    }
}

/** Adapts the existing capability gap model instead of replacing CapabilityRegistry semantics. */
object CapabilityWorldGapAdapter {
    fun adapt(
        domain: FieldDomainId,
        gap: CapabilityGap,
    ): WorldGap.Capability = WorldGap.Capability(
        domain = domain,
        capabilityId = gap.requirement.capabilityId.value,
        providerCandidates = gap.candidateProviderIds.toSet(),
        severity = gap.requirement.severity,
        reason = gap.type.name.lowercase(),
    )
}
