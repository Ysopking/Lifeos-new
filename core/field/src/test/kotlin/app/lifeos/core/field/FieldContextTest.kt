package app.lifeos.core.field

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FieldContextTest {
    private val now = Instant.parse("2026-09-08T12:00:00Z")
    private val domain = StableFieldIds.domain("conversation.whatsapp")

    @Test
    fun `only active context scopes contribute semantic support`() {
        val conversation = reference(
            id = "conversation",
            scopes = setOf(FieldContextScope.CURRENT_CONVERSATION),
            terms = setOf("repair", "support"),
            confidence = 0.8,
        )
        val legal = reference(
            id = "legal",
            scopes = setOf(FieldContextScope.LEGAL_CONTEXT),
            terms = setOf("repair"),
            confidence = 1.0,
        )
        val context = FieldContext(
            temporal = TemporalContext(now),
            domain = DomainContext(domain),
            photonReferences = listOf(legal, conversation),
            activeScopes = setOf(FieldContextScope.CURRENT_CONVERSATION),
        )

        assertEquals(0.8, context.semanticSupport("repair"))
        assertEquals(listOf(conversation), context.relevantReferences())
    }

    @Test
    fun `context fingerprint changes when source revision changes`() {
        val first = FieldContext(
            temporal = TemporalContext(now),
            domain = DomainContext(domain, mapOf("conversation" to "a")),
            photonReferences = listOf(reference("p", revision = 1)),
            activeScopes = setOf(FieldContextScope.CURRENT_CONVERSATION),
        )
        val second = FieldContext(
            temporal = TemporalContext(now),
            domain = DomainContext(domain, mapOf("conversation" to "a")),
            photonReferences = listOf(reference("p", revision = 2)),
            activeScopes = setOf(FieldContextScope.CURRENT_CONVERSATION),
        )

        assertNotEquals(first.fingerprint(), second.fingerprint())
    }

    private fun reference(
        id: String,
        revision: Long = 1,
        scopes: Set<FieldContextScope> = setOf(FieldContextScope.CURRENT_CONVERSATION),
        terms: Set<String> = setOf("support"),
        confidence: Double = 0.7,
    ): PhotonContextReference = PhotonContextReference(
        photonId = PhotonId(id),
        revision = revision,
        scopes = scopes,
        semanticTerms = terms,
        confidence = confidence,
        observedAt = now.minusSeconds(30),
    )
}
