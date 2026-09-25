package app.lifeos.core.runtime.research

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.reasoning.RealizationComponentKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PersonalRealizationStateAdapterTest {
    @Test
    fun `semantic identity excludes observation clock while representation preserves snapshot identity`() {
        val first = snapshot(Instant.parse("2026-09-25T13:00:00Z"))
        val second = snapshot(Instant.parse("2026-09-25T13:00:01Z"))

        val a = PersonalRealizationStateAdapter.component(first)
        val b = PersonalRealizationStateAdapter.component(second)

        assertEquals(RealizationComponentKind.PERSONAL_CONTEXT, a.kind)
        assertEquals(a.semanticFingerprint, b.semanticFingerprint)
        assertNotEquals(a.representationId, b.representationId)
    }

    private fun snapshot(asOf: Instant): OwnerPersonalContextSnapshot {
        val outcomes = emptyList<String>()
        val fp = StableFieldIds.fingerprint(
            "owner-personal-context-snapshot/v1",
            "world",
            "objective",
            "agency",
            "subjective",
            asOf.toString(),
            *outcomes.map { "verified-outcome:$it" }.toTypedArray(),
        )
        val ctor = OwnerPersonalContextSnapshot::class.java
            .declaredConstructors
            .single()
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(
            "world",
            "objective",
            "agency",
            "subjective",
            outcomes,
            asOf,
            fp,
        ) as OwnerPersonalContextSnapshot
    }
}
