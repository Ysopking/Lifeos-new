package app.lifeos.core.runtime.state

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import java.time.Duration
import java.time.Instant

@JvmInline
value class StateDimensionId(val value: String) {
    init {
        require(value.isNotBlank()) { "State dimension id must not be blank" }
    }

    override fun toString(): String = value
}

data class StateDimensionRequirement(
    val dimension: StateDimensionId,
    val minimumAuthority: ObservationAuthorityClass,
    val maximumAge: Duration? = null,
    val minimumEvidenceCount: Int = 1,
    val allowConflicts: Boolean = false,
) {
    init {
        require(minimumEvidenceCount > 0)
        require(maximumAge == null || (!maximumAge.isNegative && !maximumAge.isZero))
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "state-dimension-requirement/v1",
        dimension.value,
        minimumAuthority.name,
        maximumAge?.toString().orEmpty(),
        minimumEvidenceCount.toString(),
        allowConflicts.toString(),
    )
}

data class StateContract(
    val id: String,
    val domain: FieldDomainId,
    val dimensions: List<StateDimensionRequirement>,
) {
    init {
        require(id.isNotBlank())
        require(dimensions.isNotEmpty())
        require(dimensions.map { it.dimension }.distinct().size == dimensions.size)
        require(dimensions == dimensions.sortedBy { it.dimension.value }) {
            "State contract dimensions must be canonical"
        }
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "state-contract/v1",
        id,
        domain.value,
        *dimensions.map { it.fingerprint }.toTypedArray(),
    )

    companion object {
        fun create(
            id: String,
            domain: FieldDomainId,
            dimensions: Collection<StateDimensionRequirement>,
        ): StateContract = StateContract(
            id = id,
            domain = domain,
            dimensions = dimensions.sortedBy { it.dimension.value },
        )
    }
}

data class StateDimensionEvidence(
    val dimension: StateDimensionId,
    val evidenceIds: Set<String>,
    val strongestAuthority: ObservationAuthorityClass,
    val latestObservedAt: Instant,
    val conflictCount: Int = 0,
) {
    init {
        require(evidenceIds.none { it.isBlank() })
        require(conflictCount >= 0)
    }
}

enum class StateSufficiencyStatus {
    SUFFICIENT,
    INSUFFICIENT,
    STALE,
    CONFLICTED,
}

data class StateSufficiencyResult(
    val contractId: String,
    val contractFingerprint: String,
    val status: StateSufficiencyStatus,
    val satisfied: Set<StateDimensionId>,
    val missing: Set<StateDimensionId>,
    val stale: Set<StateDimensionId>,
    val conflicted: Set<StateDimensionId>,
    val supportingEvidenceIds: Set<String>,
) {
    init {
        require(contractId.isNotBlank())
        require(contractFingerprint.isNotBlank())
        require(
            listOf(satisfied, missing, stale, conflicted)
                .flatten()
                .groupingBy { it }
                .eachCount()
                .values
                .all { it == 1 }
        ) {
            "One state dimension cannot occupy multiple sufficiency result classes"
        }
        require(supportingEvidenceIds.none { it.isBlank() })
    }
}

/**
 * B455 deterministic state sufficiency detector.
 *
 * Sufficiency is evaluated against an explicit contract. Confidence alone cannot create a complete
 * state; missing dimensions, weak authority, stale evidence and conflicts remain explicit.
 */
class StateSufficiencyDetector {
    fun evaluate(
        contract: StateContract,
        evidence: Collection<StateDimensionEvidence>,
        at: Instant,
    ): StateSufficiencyResult {
        val byDimension = evidence
            .groupBy { it.dimension }
            .mapValues { (_, entries) ->
                require(entries.size == 1) {
                    "State dimension evidence must be pre-reconciled into one summary"
                }
                entries.single()
            }

        val satisfied = linkedSetOf<StateDimensionId>()
        val missing = linkedSetOf<StateDimensionId>()
        val stale = linkedSetOf<StateDimensionId>()
        val conflicted = linkedSetOf<StateDimensionId>()
        val supporting = linkedSetOf<String>()

        contract.dimensions.forEach { requirement ->
            val current = byDimension[requirement.dimension]
            if (
                current == null ||
                current.evidenceIds.size < requirement.minimumEvidenceCount ||
                current.strongestAuthority.rank < requirement.minimumAuthority.rank
            ) {
                missing += requirement.dimension
                return@forEach
            }

            supporting += current.evidenceIds

            if (current.conflictCount > 0 && !requirement.allowConflicts) {
                conflicted += requirement.dimension
                return@forEach
            }

            val maximumAge = requirement.maximumAge
            if (
                maximumAge != null &&
                current.latestObservedAt.plus(maximumAge).isBefore(at)
            ) {
                stale += requirement.dimension
                return@forEach
            }

            satisfied += requirement.dimension
        }

        val status = when {
            conflicted.isNotEmpty() -> StateSufficiencyStatus.CONFLICTED
            missing.isNotEmpty() -> StateSufficiencyStatus.INSUFFICIENT
            stale.isNotEmpty() -> StateSufficiencyStatus.STALE
            else -> StateSufficiencyStatus.SUFFICIENT
        }

        return StateSufficiencyResult(
            contractId = contract.id,
            contractFingerprint = contract.fingerprint,
            status = status,
            satisfied = satisfied,
            missing = missing,
            stale = stale,
            conflicted = conflicted,
            supportingEvidenceIds = supporting,
        )
    }
}
