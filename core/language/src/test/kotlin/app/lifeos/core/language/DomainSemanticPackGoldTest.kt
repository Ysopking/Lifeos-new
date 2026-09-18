package app.lifeos.core.language

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DomainSemanticPackGoldTest {
    private val interpreter = DomainSemanticInterpreter()
    private val utterance = UtteranceNormalizer().normalize("test")

    @Test
    fun appointmentBindsCanonicalTemporalValue() {
        val appointment = entity(EntityTypeRegistry.APPOINTMENT, "termin", 0)
        val temporal = SemanticTemporalValue(
            relation = TemporalRelation.AT,
            startInclusive = Instant.parse("2026-09-21T08:00:00Z"),
            endInclusive = Instant.parse("2026-09-21T09:00:00Z"),
            sourceText = "am Montag",
            span = TextSpan(0, 4),
            confidence = 0.98,
        )

        val graph = interpret(
            entities = listOf(appointment),
            quantityTemporal = QuantityTemporalResult(emptyList(), listOf(temporal)),
        )

        assertTrue(DomainSemanticPackId.APPOINTMENT in graph.packs)
        assertTrue(graph.relations.any { it.type == DomainSemanticRelationType.RELATES_TO })
    }

    @Test
    fun contractBindsDurationAndDeadline() {
        val graph = interpret(
            entities = listOf(
                entity(EntityTypeRegistry.CONTRACT, "vertrag", 0),
                entity(EntityTypeRegistry.DURATION, "12 monate", 1),
                entity(EntityTypeRegistry.DEADLINE, "kuendigungsfrist", 2),
            )
        )

        assertTrue(DomainSemanticPackId.CONTRACT in graph.packs)
        assertTrue(graph.relations.any { it.type == DomainSemanticRelationType.HAS_DURATION })
        assertTrue(graph.relations.any { it.type == DomainSemanticRelationType.HAS_DEADLINE })
    }

    @Test
    fun medicationBindsDosage() {
        val graph = interpret(
            entities = listOf(
                entity(EntityTypeRegistry.MEDICATION, "ibuprofen", 0),
                entity(EntityTypeRegistry.DOSAGE, "400 mg", 1),
            )
        )

        assertTrue(DomainSemanticPackId.HEALTH in graph.packs)
        assertTrue(graph.relations.any { it.type == DomainSemanticRelationType.HAS_DOSAGE })
    }

    @Test
    fun documentContainsClaim() {
        val graph = interpret(
            entities = listOf(
                entity(EntityTypeRegistry.NOTICE, "bescheid", 0),
                entity(EntityTypeRegistry.CLAIM, "rueckforderung", 1),
            )
        )

        assertTrue(graph.relations.any { it.type == DomainSemanticRelationType.CONTAINS })
    }

    @Test
    fun sameInputsProduceSameFingerprint() {
        val entities = listOf(
            entity(EntityTypeRegistry.CONTRACT, "vertrag", 0),
            entity(EntityTypeRegistry.DEADLINE, "frist", 1),
        )

        val first = interpret(entities)
        val second = interpret(entities.reversed())

        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.nodes, second.nodes)
        assertEquals(first.relations, second.relations)
    }

    private fun interpret(
        entities: List<SemanticEntityV2>,
        quantityTemporal: QuantityTemporalResult = QuantityTemporalResult(emptyList(), emptyList()),
    ): DomainSemanticGraph = interpreter.interpret(
        utterance = utterance,
        entities = entities,
        quantityTemporal = quantityTemporal,
        actionGraph = SemanticActionGraph.empty(),
    )

    private fun entity(
        type: SemanticEntityTypeDefinition,
        value: String,
        tokenStart: Int,
    ): SemanticEntityV2 = SemanticEntityV2(
        typeId = type.id,
        rawText = value,
        normalizedValue = value,
        tokenStart = tokenStart,
        tokenEndExclusive = tokenStart + 1,
        confidence = 1.0,
        source = "domain-gold-test",
    )
}
