package app.lifeos.core.field

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNotEquals

class FieldContextScopeFingerprintTest {
    private val at = Instant.parse("2026-09-10T17:00:00Z")
    private val domain = StableFieldIds.domain("context-fingerprint-test")

    @Test
    fun `same photon in project versus goal context changes fingerprint`() {
        val project = context(FieldContextScope.CURRENT_PROJECT)
        val goal = context(FieldContextScope.CURRENT_GOAL)

        assertNotEquals(project.fingerprint(), goal.fingerprint())
    }

    private fun context(scope: FieldContextScope) = FieldContext(
        temporal = TemporalContext(at),
        domain = DomainContext(domain),
        photonReferences = listOf(
            PhotonContextReference(
                photonId = PhotonId("context-target"),
                revision = 1,
                scopes = setOf(scope),
                semanticTerms = setOf("goal"),
                confidence = 0.9,
                observedAt = at,
            )
        ),
        activeScopes = setOf(FieldContextScope.CURRENT_TASK, scope),
    )
}
