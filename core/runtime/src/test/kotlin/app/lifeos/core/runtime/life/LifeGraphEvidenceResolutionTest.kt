package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifeGraphEvidenceResolutionTest {
    private val at = Instant.parse("2026-09-12T12:00:00Z")

    @Test
    fun supportedAliasCollapsesToCanonicalEntityWithSourceLineage() {
        val evidence = photon(
            id = "alias-evidence",
            confidence = 0.9,
            tags = setOf(
                "person:Alice",
                "person:Ally",
                "entity-alias:PERSON:Ally=Alice",
            ),
        )

        val graph = LifeGraphProjector().project(listOf(evidence))

        val people = graph.entities.filter { it.type == LifeEntityType.PERSON }
        assertEquals(1, people.size)
        val alice = people.single()
        assertTrue(alice.label.equals("Alice", ignoreCase = true))
        assertTrue(alice.aliases.any { it.equals("Ally", ignoreCase = true) })
        assertEquals(setOf(evidence.id), alice.sourcePhotonIds)
        assertTrue(alice.confidence >= 0.9)
    }

    @Test
    fun ambiguousAliasDoesNotInventIdentityMerge() {
        val alice = photon(
            id = "alias-a",
            confidence = 0.9,
            tags = setOf("person:Alice", "person:Ally", "entity-alias:PERSON:Ally=Alice"),
        )
        val alicia = photon(
            id = "alias-b",
            confidence = 0.9,
            tags = setOf("person:Alicia", "entity-alias:PERSON:Ally=Alicia"),
        )

        val graph = LifeGraphProjector().project(listOf(alice, alicia))
        val labels = graph.entities.filter { it.type == LifeEntityType.PERSON }.map { it.label.lowercase() }.toSet()

        assertTrue("alice" in labels)
        assertTrue("alicia" in labels)
        assertTrue("ally" in labels)
        assertFalse(graph.entities.any { it.aliases.any { alias -> alias.equals("Ally", ignoreCase = true) } })
    }

    @Test
    fun explicitRelationshipKeepsTypeConfidenceAndEvidenceSource() {
        val evidence = photon(
            id = "relationship-evidence",
            confidence = 0.82,
            tags = setOf(
                "person:Alice",
                "project:LifeOS",
                "entity-relationship:OWNS|PERSON:Alice|PROJECT:LifeOS",
            ),
        )

        val graph = LifeGraphProjector().project(listOf(evidence))
        val relationship = graph.relationships.single { it.type == "OWNS" }

        assertEquals(setOf(evidence.id), relationship.sourcePhotonIds)
        assertEquals(0.82, relationship.confidence)
        assertTrue(graph.entities.any { it.id == relationship.fromEntityId })
        assertTrue(graph.entities.any { it.id == relationship.toEntityId })
    }

    @Test
    fun lowConfidenceAliasIsNotApplied() {
        val evidence = photon(
            id = "weak-alias",
            confidence = 0.3,
            tags = setOf(
                "person:Alice",
                "person:Ally",
                "entity-alias:PERSON:Ally=Alice",
            ),
        )

        val graph = LifeGraphProjector().project(listOf(evidence))

        assertEquals(2, graph.entities.count { it.type == LifeEntityType.PERSON })
        assertTrue(graph.entities.all { it.aliases.isEmpty() })
    }

    private fun photon(id: String, confidence: Double, tags: Set<String>): Photon = Photon(
        id = PhotonId(id),
        content = "evidence",
        confidence = confidence,
        provenance = Provenance("test", "user", at),
        tags = tags,
    )
}
