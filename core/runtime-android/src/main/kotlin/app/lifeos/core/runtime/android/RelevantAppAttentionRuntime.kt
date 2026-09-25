package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.world.SensorStateDimensionSelector
import app.lifeos.core.runtime.world.StateDimensionId
import app.lifeos.core.runtime.world.WorldGap

data class RelevantAppSurfaceProfile(
    val packageName: String,
    val surfaceKey: String,
    val stateDimensions: List<SensorStateDimensionSelector>,
    val observationContracts: Set<String> = emptySet(),
    val sourceFingerprint: String,
) {
    init {
        require(packageName.isNotBlank())
        require(surfaceKey.isNotBlank())
        require(stateDimensions.isNotEmpty() || observationContracts.isNotEmpty())
        require(
            stateDimensions == stateDimensions.distinct().sortedWith(
                compareBy<SensorStateDimensionSelector>({ it.type.name }, { it.value })
            )
        )
        require(observationContracts.none { it.isBlank() })
        require(sourceFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    fun covers(dimension: StateDimensionId): Boolean =
        stateDimensions.any { it.matches(dimension) }

    val fingerprint: String = androidCapabilityFingerprint(
        "relevant-app-surface-profile/v1",
        packageName,
        surfaceKey,
        sourceFingerprint,
        stateDimensions.joinToString("\u001f") { it.fingerprint },
        observationContracts.sorted().joinToString("\u001f"),
    )

    val observationGrantAuthority: Boolean
        get() = false

    val effectAuthority: Boolean
        get() = false
}

data class RelevantAppAttentionPlan(
    val focusedPackages: List<String>,
    val matchedGapIdsByPackage: Map<String, List<String>>,
    val unresolvedObservationGapIds: List<String>,
    val nonSensorGapIds: List<String>,
) {
    init {
        require(focusedPackages == focusedPackages.distinct().sorted())
        require(matchedGapIdsByPackage.keys == focusedPackages.toSet())
        require(matchedGapIdsByPackage.values.all { it == it.distinct().sorted() })
        require(unresolvedObservationGapIds == unresolvedObservationGapIds.distinct().sorted())
        require(nonSensorGapIds == nonSensorGapIds.distinct().sorted())
    }

    val observationGrantAuthority: Boolean
        get() = false

    val effectAuthority: Boolean
        get() = false

    val fingerprint: String = androidCapabilityFingerprint(
        "relevant-app-attention-plan/v1",
        *focusedPackages.map { "package:$it" }.toTypedArray(),
        *matchedGapIdsByPackage.entries
            .sortedBy { it.key }
            .flatMap { (packageName, gapIds) ->
                listOf("matched-package:$packageName") +
                    gapIds.map { "matched-gap:$packageName:$it" }
            }
            .toTypedArray(),
        *unresolvedObservationGapIds.map { "unresolved:$it" }.toTypedArray(),
        *nonSensorGapIds.map { "non-sensor:$it" }.toTypedArray(),
    )
}

class RelevantAppAttentionResolver {
    fun resolve(
        gaps: Collection<WorldGap>,
        surfaces: Collection<RelevantAppSurfaceProfile>,
    ): RelevantAppAttentionPlan {
        require(gaps.map { it.id }.distinct().size == gaps.size)
        require(surfaces.map { it.fingerprint }.distinct().size == surfaces.size)

        val canonicalGaps = gaps.sortedBy { it.id }
        val observationGaps = canonicalGaps.filterNot { it is WorldGap.Capability }
        val nonSensorGapIds = canonicalGaps
            .filterIsInstance<WorldGap.Capability>()
            .map { it.id }
            .sorted()

        val matchedGapIds = linkedSetOf<String>()
        val matchedByPackage = linkedMapOf<String, MutableSet<String>>()

        surfaces
            .sortedWith(
                compareBy<RelevantAppSurfaceProfile> { it.packageName }
                    .thenBy { it.surfaceKey }
                    .thenBy { it.fingerprint }
            )
            .forEach { surface ->
                val relevant = observationGaps.filter { gap -> matches(surface, gap) }
                if (relevant.isEmpty()) return@forEach

                val packageMatches = matchedByPackage
                    .getOrPut(surface.packageName) { linkedSetOf() }
                relevant.forEach { gap ->
                    packageMatches += gap.id
                    matchedGapIds += gap.id
                }
            }

        val canonicalMatches = matchedByPackage.entries
            .sortedBy { it.key }
            .associate { (packageName, ids) ->
                packageName to ids.toList().sorted()
            }

        return RelevantAppAttentionPlan(
            focusedPackages = canonicalMatches.keys.toList().sorted(),
            matchedGapIdsByPackage = canonicalMatches,
            unresolvedObservationGapIds = observationGaps
                .map { it.id }
                .filterNot(matchedGapIds::contains)
                .sorted(),
            nonSensorGapIds = nonSensorGapIds,
        )
    }

    private fun matches(
        surface: RelevantAppSurfaceProfile,
        gap: WorldGap,
    ): Boolean = when (gap) {
        is WorldGap.Perception ->
            (gap.missingDimensions + gap.staleDimensions).any(surface::covers)

        is WorldGap.Consistency ->
            gap.conflictingDimensions.any(surface::covers)

        is WorldGap.Verification ->
            gap.missingObservationContract in surface.observationContracts

        is WorldGap.Capability -> false
    }
}
