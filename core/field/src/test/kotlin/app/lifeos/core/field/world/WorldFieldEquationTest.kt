package app.lifeos.core.field.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorldFieldEquationTest {
    @Test
    fun `explicit cross dimension coefficient transfers only into declared target dimension`() {
        val source = node(
            key = "domain",
            kind = WorldNodeKind.DOMAIN_FIELD,
            values = listOf(
                value(WorldSignalDimension.EVIDENCE_SUPPORT, 0.8, 0.5, "domain-support"),
                value(WorldSignalDimension.UNCERTAINTY, 0.4, 1.0, "domain-uncertainty"),
            ),
        )
        val target = node(
            key = "health",
            kind = WorldNodeKind.HEALTH,
            values = listOf(
                value(WorldSignalDimension.HEALTH_STABILITY, 0.9, 1.0, "health-base"),
                value(WorldSignalDimension.COGNITIVE_PRIORITY, 0.25, 1.0, "priority-base"),
            ),
        )
        val coefficient = WorldTransferCoefficient.create(
            semanticKey = "support-to-health",
            sourceDimension = WorldSignalDimension.EVIDENCE_SUPPORT,
            targetDimension = WorldSignalDimension.HEALTH_STABILITY,
            multiplier = -0.5,
            confidenceMultiplier = 0.5,
            explanation = "high support consumes bounded health headroom",
        )
        val edge = WorldFieldEdge.create(
            sourceNodeId = source.id,
            targetNodeId = target.id,
            sourceDimension = WorldSignalDimension.EVIDENCE_SUPPORT,
            targetDimension = WorldSignalDimension.HEALTH_STABILITY,
            coefficientId = coefficient.id,
            strength = 1.0,
            explanation = "typed support to health transfer",
        )
        val graph = WorldFieldGraph(listOf(source, target), listOf(edge))
        val spec = WorldEquationSpec("equation-v1", listOf(coefficient))
        val equation = WorldFieldEquation(spec)
        val initial = WorldFieldState.initial(graph, spec.fingerprint())

        val result = equation.evaluate(graph, initial)

        assertEquals(1, result.state.generation)
        assertEquals(-0.2, result.contributions.single().signedDelta, 1e-12)
        assertEquals(
            0.7,
            result.state[target.id]!![WorldSignalDimension.HEALTH_STABILITY]!!.value,
            1e-12,
        )
        assertEquals(
            0.25,
            result.state[target.id]!![WorldSignalDimension.COGNITIVE_PRIORITY]!!.value,
            1e-12,
        )
        assertEquals(
            0.4,
            result.state[source.id]!![WorldSignalDimension.UNCERTAINTY]!!.value,
            1e-12,
        )
    }

    @Test
    fun `same dimension contributions aggregate while other dimensions remain independent`() {
        val sourceA = node(
            "a",
            WorldNodeKind.DOMAIN_FIELD,
            listOf(value(WorldSignalDimension.CONFLICT_PRESSURE, 0.5, 1.0, "a")),
        )
        val sourceB = node(
            "b",
            WorldNodeKind.DOMAIN_FIELD,
            listOf(value(WorldSignalDimension.CONFLICT_PRESSURE, 0.4, 1.0, "b")),
        )
        val target = node(
            "capability",
            WorldNodeKind.CAPABILITY,
            listOf(
                value(WorldSignalDimension.CAPABILITY_READINESS, 0.8, 1.0, "ready"),
                value(WorldSignalDimension.COGNITIVE_PRIORITY, 0.6, 1.0, "priority"),
            ),
        )
        val coefficient = WorldTransferCoefficient.create(
            semanticKey = "conflict-to-readiness",
            sourceDimension = WorldSignalDimension.CONFLICT_PRESSURE,
            targetDimension = WorldSignalDimension.CAPABILITY_READINESS,
            multiplier = -0.25,
            explanation = "conflict reduces readiness",
        )
        val edges = listOf(sourceA, sourceB).map { source ->
            WorldFieldEdge.create(
                sourceNodeId = source.id,
                targetNodeId = target.id,
                sourceDimension = WorldSignalDimension.CONFLICT_PRESSURE,
                targetDimension = WorldSignalDimension.CAPABILITY_READINESS,
                coefficientId = coefficient.id,
                strength = 1.0,
                explanation = "conflict contribution from ${source.target.key}",
            )
        }
        val graph = WorldFieldGraph(listOf(target, sourceB, sourceA), edges.reversed())
        val spec = WorldEquationSpec("equation-v1", listOf(coefficient))
        val result = WorldFieldEquation(spec).evaluate(
            graph,
            WorldFieldState.initial(graph, spec.fingerprint()),
        )

        assertEquals(0.575, result.state[target.id]!![WorldSignalDimension.CAPABILITY_READINESS]!!.value, 1e-12)
        assertEquals(0.6, result.state[target.id]!![WorldSignalDimension.COGNITIVE_PRIORITY]!!.value, 1e-12)
        assertEquals(2, result.contributions.size)
    }

    @Test
    fun `graph and edge ordering do not change deterministic equation result`() {
        val source = node(
            "source",
            WorldNodeKind.DOMAIN_FIELD,
            listOf(value(WorldSignalDimension.EVIDENCE_SUPPORT, 0.75, 0.8, "source")),
        )
        val thought = node(
            "thought",
            WorldNodeKind.THOUGHT,
            listOf(value(WorldSignalDimension.COGNITIVE_PRIORITY, 0.1, 1.0, "thought")),
        )
        val capability = node(
            "capability",
            WorldNodeKind.CAPABILITY,
            listOf(value(WorldSignalDimension.CAPABILITY_READINESS, 0.2, 1.0, "capability")),
        )
        val toThought = WorldTransferCoefficient.create(
            semanticKey = "support-to-thought",
            sourceDimension = WorldSignalDimension.EVIDENCE_SUPPORT,
            targetDimension = WorldSignalDimension.COGNITIVE_PRIORITY,
            multiplier = 0.5,
            explanation = "raise thought priority",
        )
        val toCapability = WorldTransferCoefficient.create(
            semanticKey = "support-to-capability",
            sourceDimension = WorldSignalDimension.EVIDENCE_SUPPORT,
            targetDimension = WorldSignalDimension.CAPABILITY_READINESS,
            multiplier = 0.4,
            explanation = "raise capability readiness",
        )
        val edgeThought = WorldFieldEdge.create(
            source.id,
            thought.id,
            WorldSignalDimension.EVIDENCE_SUPPORT,
            WorldSignalDimension.COGNITIVE_PRIORITY,
            toThought.id,
            1.0,
            "support to thought",
        )
        val edgeCapability = WorldFieldEdge.create(
            source.id,
            capability.id,
            WorldSignalDimension.EVIDENCE_SUPPORT,
            WorldSignalDimension.CAPABILITY_READINESS,
            toCapability.id,
            1.0,
            "support to capability",
        )
        val spec = WorldEquationSpec("equation-v1", listOf(toCapability, toThought))
        val leftGraph = WorldFieldGraph(
            nodes = listOf(source, thought, capability),
            edges = listOf(edgeThought, edgeCapability),
        )
        val rightGraph = WorldFieldGraph(
            nodes = listOf(capability, source, thought),
            edges = listOf(edgeCapability, edgeThought),
        )
        val equation = WorldFieldEquation(spec)

        val left = equation.evaluate(leftGraph, WorldFieldState.initial(leftGraph, spec.fingerprint()))
        val right = equation.evaluate(rightGraph, WorldFieldState.initial(rightGraph, spec.fingerprint()))

        assertEquals(leftGraph.fingerprint(), rightGraph.fingerprint())
        assertEquals(left.state.fingerprint(), right.state.fingerprint())
        assertEquals(left.contributions, right.contributions)
    }

    @Test
    fun `missing or dimension mismatched coefficient fails closed`() {
        val source = node(
            "source",
            WorldNodeKind.DOMAIN_FIELD,
            listOf(value(WorldSignalDimension.EVIDENCE_SUPPORT, 0.7, 1.0, "source")),
        )
        val target = node(
            "target",
            WorldNodeKind.THOUGHT,
            listOf(value(WorldSignalDimension.COGNITIVE_PRIORITY, 0.2, 1.0, "target")),
        )
        val declared = WorldTransferCoefficient.create(
            semanticKey = "declared",
            sourceDimension = WorldSignalDimension.EVIDENCE_SUPPORT,
            targetDimension = WorldSignalDimension.COGNITIVE_PRIORITY,
            multiplier = 0.5,
            explanation = "declared transfer",
        )
        val missingEdge = WorldFieldEdge.create(
            source.id,
            target.id,
            WorldSignalDimension.EVIDENCE_SUPPORT,
            WorldSignalDimension.COGNITIVE_PRIORITY,
            WorldCoefficientId("missing"),
            1.0,
            "missing coefficient",
        )
        val missingGraph = WorldFieldGraph(listOf(source, target), listOf(missingEdge))
        val missingSpec = WorldEquationSpec("v1", listOf(declared))
        val missingEquation = WorldFieldEquation(missingSpec)

        assertTrue(missingEquation.validate(missingGraph).single().contains("missing-coefficient"))
        assertFailsWith<IllegalStateException> {
            missingEquation.evaluate(
                missingGraph,
                WorldFieldState.initial(missingGraph, missingSpec.fingerprint()),
            )
        }

        val wrongCoefficient = declared.copy(
            targetDimension = WorldSignalDimension.HEALTH_STABILITY,
        )
        val mismatchSpec = WorldEquationSpec("v2", listOf(wrongCoefficient))
        val mismatchEdge = missingEdge.copy(coefficientId = wrongCoefficient.id)
        val mismatchGraph = WorldFieldGraph(listOf(source, target), listOf(mismatchEdge))
        val mismatchEquation = WorldFieldEquation(mismatchSpec)
        assertTrue(mismatchEquation.validate(mismatchGraph).single().contains("target-dimension-mismatch"))
        assertFailsWith<IllegalArgumentException> {
            mismatchEquation.evaluate(
                mismatchGraph,
                WorldFieldState.initial(mismatchGraph, mismatchSpec.fingerprint()),
            )
        }
    }

    @Test
    fun `state cannot be replayed under another equation version`() {
        val node = node(
            "domain",
            WorldNodeKind.DOMAIN_FIELD,
            listOf(value(WorldSignalDimension.EVIDENCE_SUPPORT, 0.7, 1.0, "domain")),
        )
        val graph = WorldFieldGraph(listOf(node))
        val firstSpec = WorldEquationSpec("v1", emptyList())
        val secondSpec = WorldEquationSpec("v2", emptyList())
        val state = WorldFieldState.initial(graph, firstSpec.fingerprint())

        assertNotEquals(firstSpec.fingerprint(), secondSpec.fingerprint())
        assertFailsWith<IllegalArgumentException> {
            WorldFieldEquation(secondSpec).evaluate(graph, state)
        }
    }

    @Test
    fun `world state generation cannot add or remove graph nodes`() {
        val node = node(
            "domain",
            WorldNodeKind.DOMAIN_FIELD,
            listOf(value(WorldSignalDimension.EVIDENCE_SUPPORT, 0.5, 1.0, "domain")),
        )
        val graph = WorldFieldGraph(listOf(node))
        val spec = WorldEquationSpec("v1", emptyList())
        val state = WorldFieldState.initial(graph, spec.fingerprint())

        assertFailsWith<IllegalArgumentException> {
            state.next(emptyMap())
        }
    }

    private fun node(
        key: String,
        kind: WorldNodeKind,
        values: List<WorldDimensionValue>,
    ): WorldFieldNode = WorldFieldNode.create(
        target = WorldTargetRef(kind, key),
        intrinsic = WorldFieldVector(values),
    )

    private fun value(
        dimension: WorldSignalDimension,
        magnitude: Double,
        confidence: Double,
        provenance: String,
    ) = WorldDimensionValue(
        dimension = dimension,
        value = magnitude,
        confidence = confidence,
        provenanceFingerprints = setOf("prov-$provenance"),
    )
}
