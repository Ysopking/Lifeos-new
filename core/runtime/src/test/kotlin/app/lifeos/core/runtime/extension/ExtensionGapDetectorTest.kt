package app.lifeos.core.runtime.extension

import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtensionGapDetectorTest {
    @Test
    fun emitsAllB152GapKindsAsBootWorkOnly() {
        val coupling = WorldCouplingRequirement(
            sourceDimension = WorldSignalDimension.RELIABILITY,
            targetDimension = WorldSignalDimension.GOAL_RELEVANCE,
        )
        val causal = CausalModelRequirement(
            sourceSemanticKey = "strategy-a",
            targetSemanticKey = "outcome-b",
        )

        val result = ExtensionGapDetector().detect(
            ExtensionGapDetectionInput(
                sourceFingerprint = "source-v1",
                representedNodeKinds = setOf(WorldNodeKind.PHOTON),
                requiredNodeKinds = setOf(WorldNodeKind.PHOTON, WorldNodeKind.GOAL),
                representedSignalDimensions = setOf(WorldSignalDimension.RELIABILITY),
                requiredSignalDimensions = setOf(
                    WorldSignalDimension.RELIABILITY,
                    WorldSignalDimension.GOAL_RELEVANCE,
                ),
                availableCouplings = emptySet(),
                requiredCouplings = setOf(coupling),
                availableCausalRelations = emptySet(),
                requiredCausalRelations = setOf(causal),
                equationCoveredCouplings = emptySet(),
            )
        )

        assertEquals(
            ExtensionGapKind.entries.toSet(),
            result.gaps.mapTo(linkedSetOf()) { it.kind },
        )
        assertEquals(result.gaps.size, result.bootWork.size)
        assertEquals(
            result.gaps.map { it.id }.toSet(),
            result.bootWork.map { it.gapId }.toSet(),
        )
        assertTrue(result.bootWork.all { it.type == ExtensionGapWorkType.ASSESS_EXTENSION_GAP })
    }

    @Test
    fun producesNoGapWhenExplicitCoverageIsComplete() {
        val coupling = WorldCouplingRequirement(
            WorldSignalDimension.RELIABILITY,
            WorldSignalDimension.GOAL_RELEVANCE,
        )
        val causal = CausalModelRequirement("strategy-a", "outcome-b")

        val result = ExtensionGapDetector().detect(
            ExtensionGapDetectionInput(
                sourceFingerprint = "source-complete",
                representedNodeKinds = setOf(WorldNodeKind.PHOTON, WorldNodeKind.GOAL),
                requiredNodeKinds = setOf(WorldNodeKind.PHOTON, WorldNodeKind.GOAL),
                representedSignalDimensions = setOf(
                    WorldSignalDimension.RELIABILITY,
                    WorldSignalDimension.GOAL_RELEVANCE,
                ),
                requiredSignalDimensions = setOf(
                    WorldSignalDimension.RELIABILITY,
                    WorldSignalDimension.GOAL_RELEVANCE,
                ),
                availableCouplings = setOf(coupling),
                requiredCouplings = setOf(coupling),
                availableCausalRelations = setOf(causal),
                requiredCausalRelations = setOf(causal),
                equationCoveredCouplings = setOf(coupling),
            )
        )

        assertTrue(result.gaps.isEmpty())
        assertTrue(result.bootWork.isEmpty())
    }

    @Test
    fun detectionIsDeterministicAcrossInputSetOrder() {
        val a = WorldCouplingRequirement(
            WorldSignalDimension.RELIABILITY,
            WorldSignalDimension.GOAL_RELEVANCE,
        )
        val b = WorldCouplingRequirement(
            WorldSignalDimension.AUTHORITY,
            WorldSignalDimension.CONTEXT_RELEVANCE,
        )

        val first = ExtensionGapDetector().detect(
            ExtensionGapDetectionInput(
                sourceFingerprint = "same-source",
                representedNodeKinds = emptySet(),
                requiredNodeKinds = linkedSetOf(WorldNodeKind.GOAL, WorldNodeKind.PHOTON),
                representedSignalDimensions = emptySet(),
                requiredSignalDimensions = linkedSetOf(
                    WorldSignalDimension.GOAL_RELEVANCE,
                    WorldSignalDimension.RELIABILITY,
                ),
                requiredCouplings = linkedSetOf(a, b),
            )
        )
        val second = ExtensionGapDetector().detect(
            ExtensionGapDetectionInput(
                sourceFingerprint = "same-source",
                representedNodeKinds = emptySet(),
                requiredNodeKinds = linkedSetOf(WorldNodeKind.PHOTON, WorldNodeKind.GOAL),
                representedSignalDimensions = emptySet(),
                requiredSignalDimensions = linkedSetOf(
                    WorldSignalDimension.RELIABILITY,
                    WorldSignalDimension.GOAL_RELEVANCE,
                ),
                requiredCouplings = linkedSetOf(b, a),
            )
        )

        assertEquals(first.inputFingerprint, second.inputFingerprint)
        assertEquals(first.gaps, second.gaps)
        assertEquals(first.bootWork, second.bootWork)
    }
}
