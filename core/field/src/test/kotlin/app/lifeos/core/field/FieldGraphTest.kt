package app.lifeos.core.field

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FieldGraphTest {
    private val domain = StableFieldIds.domain("conversation.whatsapp")

    @Test
    fun `graph accepts typed relations within one domain`() {
        val source = FieldNode.create(domain, FieldNodeKind.EVIDENCE, "question")
        val target = FieldNode.create(domain, FieldNodeKind.STATE, "answered")
        val relation = FieldRelation.create(
            domainId = domain,
            source = source.id,
            target = target.id,
            type = FieldRelationType.SUPPORTS,
            weight = 0.8,
            explanation = "answer follows question",
        )
        val graph = FieldGraph(domain, listOf(target, source), listOf(relation))

        assertEquals(target, graph.node(target.id))
        assertEquals(listOf(relation), graph.outgoing(source.id))
        assertEquals(listOf(source, target).sortedBy { it.id.value }, graph.stableNodes())
    }

    @Test
    fun `relation to foreign node is rejected`() {
        val source = FieldNode.create(domain, FieldNodeKind.EVIDENCE, "question")
        val missing = StableFieldIds.node(domain, "STATE", "missing")
        val relation = FieldRelation.create(
            domain,
            source.id,
            missing,
            FieldRelationType.SUPPORTS,
            0.5,
            "missing target",
        )

        assertFailsWith<IllegalArgumentException> {
            FieldGraph(domain, listOf(source), listOf(relation))
        }
    }

    @Test
    fun `nodes from another domain are rejected`() {
        val first = FieldNode.create(domain, FieldNodeKind.STATE, "shared")
        val otherDomain = StableFieldIds.domain("finance.debt")
        val second = FieldNode.create(otherDomain, FieldNodeKind.STATE, "shared")

        assertFailsWith<IllegalArgumentException> {
            FieldGraph(domain, listOf(first, second))
        }
    }

    @Test
    fun `competition and conflict must reference graph nodes`() {
        val a = FieldNode.create(domain, FieldNodeKind.HYPOTHESIS, "a")
        val b = FieldNode.create(domain, FieldNodeKind.HYPOTHESIS, "b")
        val group = CompetitionGroup("meaning", setOf(a.id, b.id))
        val conflict = FieldConflict(
            key = "meaning-conflict",
            nodeIds = setOf(a.id, b.id),
            severity = 0.7,
            evidenceIds = emptySet(),
            explanation = "Mutually exclusive interpretations",
        )
        val graph = FieldGraph(domain, listOf(a, b), competitionGroups = listOf(group), conflicts = listOf(conflict))

        assertEquals(group, graph.competitionGroups.single())
        assertTrue(graph.conflicts.single().severity > 0.0)
    }

    @Test
    fun `hypothesis ordering is deterministic for equal scores`() {
        val node = FieldNode.create(domain, FieldNodeKind.HYPOTHESIS, "base")
        val a = FieldHypothesis.create(
            domainId = domain,
            semanticKey = "a",
            scope = HypothesisScope.CONVERSATION,
            nodeIds = setOf(node.id),
            score = HypothesisScore(total = 0.5),
            explanation = "a",
        )
        val b = FieldHypothesis.create(
            domainId = domain,
            semanticKey = "b",
            scope = HypothesisScope.CONVERSATION,
            nodeIds = setOf(node.id),
            score = HypothesisScore(total = 0.5),
            explanation = "b",
        )

        val first = listOf(b, a).stableHypothesisOrder()
        val second = listOf(a, b).stableHypothesisOrder()
        assertEquals(first, second)
    }
}
