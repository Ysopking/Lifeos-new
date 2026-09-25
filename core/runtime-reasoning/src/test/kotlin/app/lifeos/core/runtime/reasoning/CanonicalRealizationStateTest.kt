package app.lifeos.core.runtime.reasoning

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CanonicalRealizationStateTest {
    private val t0 = Instant.parse("2026-09-25T12:00:00Z")

    @Test
    fun `component ordering is canonical and deterministic`() {
        val personal = component(
            RealizationComponentKind.PERSONAL_CONTEXT,
            "personal:r1",
            "personal-semantic",
        )
        val world = component(
            RealizationComponentKind.PRODUCTIVE_WORLD,
            "world:r1",
            "world-semantic",
        )

        val first = CanonicalRealizationState.create(
            revision = 1L,
            asOf = t0,
            components = listOf(world, personal),
        )
        val second = CanonicalRealizationState.create(
            revision = 1L,
            asOf = t0,
            components = listOf(personal, world),
        )

        assertEquals(first, second)
        assertEquals(
            listOf(
                RealizationComponentKind.PERSONAL_CONTEXT,
                RealizationComponentKind.PRODUCTIVE_WORLD,
            ),
            first.components.map { it.kind },
        )
    }

    @Test
    fun `same semantics with another concrete representation preserves state identity`() {
        val first = CanonicalRealizationState.create(
            revision = 1L,
            asOf = t0,
            components = listOf(
                component(
                    RealizationComponentKind.PERSONAL_CONTEXT,
                    "personal:encoded-a",
                    "semantic-personal-v1",
                )
            ),
        )
        val second = CanonicalRealizationState.create(
            revision = 1L,
            asOf = t0,
            components = listOf(
                component(
                    RealizationComponentKind.PERSONAL_CONTEXT,
                    "personal:encoded-b",
                    "semantic-personal-v1",
                )
            ),
        )

        assertEquals(first.id, second.id)
        assertEquals(first.equivalenceFingerprint, second.equivalenceFingerprint)
        assertNotEquals(first.revisionId, second.revisionId)
        assertNotEquals(first.representationFingerprint, second.representationFingerprint)
    }

    @Test
    fun `semantic change creates another realization equivalence class`() {
        val first = state("semantic-v1")
        val second = state("semantic-v2")

        assertNotEquals(first.id, second.id)
        assertNotEquals(first.equivalenceFingerprint, second.equivalenceFingerprint)
    }

    @Test
    fun `non initial revision binds predecessor and transition`() {
        val first = state("semantic-v1")
        val second = CanonicalRealizationState.create(
            revision = 2L,
            asOf = t0.plusSeconds(1),
            predecessorRevisionId = first.revisionId,
            transitionFingerprint = "transition-1",
            components = listOf(
                component(
                    RealizationComponentKind.PERSONAL_CONTEXT,
                    "personal:r2",
                    "semantic-v2",
                )
            ),
        )

        assertEquals(first.revisionId, second.predecessorRevisionId)
        assertEquals("transition-1", second.transitionFingerprint)
        assertTrue(second.revisionId.startsWith("realization-revision:"))
    }

    @Test
    fun `non initial revision without predecessor is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CanonicalRealizationState.create(
                revision = 2L,
                asOf = t0,
                components = listOf(
                    component(
                        RealizationComponentKind.PERSONAL_CONTEXT,
                        "personal:r2",
                        "semantic-v2",
                    )
                ),
            )
        }
    }

    @Test
    fun `initial revision with predecessor is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CanonicalRealizationState.create(
                revision = 1L,
                asOf = t0,
                predecessorRevisionId = "realization-revision:previous",
                transitionFingerprint = "transition",
                components = listOf(
                    component(
                        RealizationComponentKind.PERSONAL_CONTEXT,
                        "personal:r1",
                        "semantic-v1",
                    )
                ),
            )
        }
    }

    @Test
    fun `duplicate component kinds are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CanonicalRealizationState.create(
                revision = 1L,
                asOf = t0,
                components = listOf(
                    component(
                        RealizationComponentKind.PERSONAL_CONTEXT,
                        "personal:a",
                        "semantic-a",
                    ),
                    component(
                        RealizationComponentKind.PERSONAL_CONTEXT,
                        "personal:b",
                        "semantic-b",
                    ),
                ),
            )
        }
    }

    @Test
    fun `component provenance must already be canonical`() {
        assertFailsWith<IllegalArgumentException> {
            RealizationComponentRef(
                kind = RealizationComponentKind.COGNITIVE_STATE,
                representationId = "cognition:r1",
                semanticFingerprint = "cognition-semantic",
                provenanceFingerprints = listOf("z", "a"),
            )
        }
    }

    @Test
    fun `canonical state never grants truth or execution authority`() {
        val state = state("semantic-v1")

        assertFalse(state.truthAuthority)
        assertFalse(state.executionAuthority)
    }

    @Test
    fun `replay of identical frozen inputs produces identical fingerprints`() {
        val components = listOf(
            RealizationComponentRef(
                kind = RealizationComponentKind.RESOURCE_STATE,
                representationId = "resources:r7",
                semanticFingerprint = "resources-semantic",
                provenanceFingerprints = listOf("evidence:a", "evidence:b"),
            ),
            component(
                RealizationComponentKind.POLICY_STATE,
                "policy:r4",
                "policy-semantic",
            ),
        )

        val first = CanonicalRealizationState.create(
            revision = 7L,
            asOf = t0,
            predecessorRevisionId = "realization-revision:previous",
            transitionFingerprint = "transition-fingerprint",
            components = components,
        )
        val replay = CanonicalRealizationState.create(
            revision = 7L,
            asOf = t0,
            predecessorRevisionId = "realization-revision:previous",
            transitionFingerprint = "transition-fingerprint",
            components = components.reversed(),
        )

        assertEquals(first.id, replay.id)
        assertEquals(first.revisionId, replay.revisionId)
        assertEquals(first.representationFingerprint, replay.representationFingerprint)
    }

    private fun state(semanticFingerprint: String): CanonicalRealizationState =
        CanonicalRealizationState.create(
            revision = 1L,
            asOf = t0,
            components = listOf(
                component(
                    RealizationComponentKind.PERSONAL_CONTEXT,
                    "personal:r1",
                    semanticFingerprint,
                )
            ),
        )

    private fun component(
        kind: RealizationComponentKind,
        representationId: String,
        semanticFingerprint: String,
    ): RealizationComponentRef =
        RealizationComponentRef(
            kind = kind,
            representationId = representationId,
            semanticFingerprint = semanticFingerprint,
        )
}
