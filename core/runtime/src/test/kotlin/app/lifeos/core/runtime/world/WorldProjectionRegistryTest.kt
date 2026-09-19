package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldDimensionValue
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorldProjectionRegistryTest {
    @Test
    fun universalWorldAbiContainsRequiredLevel7KindsWithoutScalarTruthScore() {
        val requiredKinds = setOf(
            WorldNodeKind.MEMORY,
            WorldNodeKind.PLAN,
            WorldNodeKind.STRATEGY,
            WorldNodeKind.WORLD_MODEL,
            WorldNodeKind.ABSTRACTION,
            WorldNodeKind.EXPERIMENT,
            WorldNodeKind.EXTENSION,
            WorldNodeKind.RESOURCE,
            WorldNodeKind.OUTCOME,
        )
        assertTrue(WorldNodeKind.entries.toSet().containsAll(requiredKinds))

        val requiredSignals = setOf(
            WorldSignalDimension.CAUSAL_SUPPORT,
            WorldSignalDimension.PREDICTIVE_FIT,
            WorldSignalDimension.TRANSFER_RELEVANCE,
            WorldSignalDimension.STRATEGY_FIT,
            WorldSignalDimension.INFORMATION_GAIN,
            WorldSignalDimension.RESOURCE_PRESSURE,
            WorldSignalDimension.NOVELTY,
            WorldSignalDimension.OUTCOME_ALIGNMENT,
            WorldSignalDimension.MODEL_STABILITY,
        )
        assertTrue(WorldSignalDimension.entries.toSet().containsAll(requiredSignals))
        assertTrue(WorldSignalDimension.entries.none { it.name.contains("TRUTH") || it.name.contains("TOTAL") })
    }

    @Test
    fun registryIsDeterministicAndProjectionRemainsNonActivating() {
        val provider = object : UniversalWorldProjectionProvider {
            override val descriptor = WorldProjectionDescriptor(
                providerId = "core.world-model",
                sourceKind = WorldProjectionSourceKind.STRATEGY,
                nodeKinds = setOf(WorldNodeKind.STRATEGY, WorldNodeKind.WORLD_MODEL),
                signalDimensions = setOf(
                    WorldSignalDimension.STRATEGY_FIT,
                    WorldSignalDimension.PREDICTIVE_FIT,
                ),
                relationKinds = setOf(
                    WorldSemanticRelationKind.PREDICTS,
                    WorldSemanticRelationKind.CAUSES,
                ),
            )

            override fun project(context: WorldProjectionContext): UniversalWorldProjection =
                UniversalWorldProjection(
                    providerId = descriptor.providerId,
                    contextFingerprint = context.fingerprint(),
                    inputs = context.inputs.sortedWith(compareBy({ it.target.kind.name }, { it.target.key })),
                    relations = emptyList(),
                )
        }

        val registry = WorldProjectionRegistry(listOf(provider))
        val context = WorldProjectionContext(
            frozenSourceSnapshots = mapOf("strategy" to "strategy-v1"),
            inputs = listOf(
                WorldFormulaInputSnapshot(
                    target = WorldTargetRef(WorldNodeKind.STRATEGY, "strategy-a"),
                    vector = WorldFieldVector.EMPTY,
                    sourceSnapshotFingerprint = "strategy-v1",
                )
            ),
        )

        val projection = registry.project("core.world-model", context)

        assertEquals(provider.descriptor, registry.descriptor("core.world-model"))
        assertFalse(registry.snapshot.directWorldStateMutationAllowed)
        assertFalse(projection.directWorldStateMutationAllowed)
        assertFalse(projection.equationActivationAllowed)
    }


    @Test
    fun registryRejectsProjectionOutsideDeclaredSignalContract() {
        val provider = object : UniversalWorldProjectionProvider {
            override val descriptor = WorldProjectionDescriptor(
                providerId = "contract-bound",
                sourceKind = WorldProjectionSourceKind.STRATEGY,
                nodeKinds = setOf(WorldNodeKind.STRATEGY),
                signalDimensions = setOf(WorldSignalDimension.STRATEGY_FIT),
            )

            override fun project(context: WorldProjectionContext): UniversalWorldProjection =
                UniversalWorldProjection(
                    providerId = descriptor.providerId,
                    contextFingerprint = context.fingerprint(),
                    inputs = listOf(
                        WorldFormulaInputSnapshot(
                            target = WorldTargetRef(WorldNodeKind.STRATEGY, "strategy-contract"),
                            vector = WorldFieldVector(
                                listOf(
                                    WorldDimensionValue(
                                        dimension = WorldSignalDimension.EVIDENCE_SUPPORT,
                                        value = 0.8,
                                        confidence = 1.0,
                                        provenanceFingerprints = setOf("strategy-v1"),
                                    )
                                )
                            ),
                            sourceSnapshotFingerprint = "strategy-v1",
                        )
                    ),
                    relations = emptyList(),
                )
        }
        val registry = WorldProjectionRegistry(listOf(provider))
        val context = WorldProjectionContext(
            frozenSourceSnapshots = mapOf("strategy" to "strategy-v1"),
            inputs = listOf(
                WorldFormulaInputSnapshot(
                    target = WorldTargetRef(WorldNodeKind.STRATEGY, "strategy-input"),
                    vector = WorldFieldVector.EMPTY,
                    sourceSnapshotFingerprint = "strategy-v1",
                )
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            registry.project(provider.descriptor.providerId, context)
        }
    }

    @Test
    fun duplicateProviderIdentityFailsClosed() {
        fun provider() = object : UniversalWorldProjectionProvider {
            override val descriptor = WorldProjectionDescriptor(
                providerId = "duplicate",
                sourceKind = WorldProjectionSourceKind.FIELD,
                nodeKinds = setOf(WorldNodeKind.EVIDENCE),
                signalDimensions = setOf(WorldSignalDimension.EVIDENCE_SUPPORT),
            )

            override fun project(context: WorldProjectionContext): UniversalWorldProjection =
                UniversalWorldProjection(
                    providerId = descriptor.providerId,
                    contextFingerprint = context.fingerprint(),
                    inputs = context.inputs.sortedWith(compareBy({ it.target.kind.name }, { it.target.key })),
                    relations = emptyList(),
                )
        }

        assertFailsWith<IllegalArgumentException> {
            WorldProjectionRegistry(listOf(provider(), provider()))
        }
    }
}
