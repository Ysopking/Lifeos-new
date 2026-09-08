package app.lifeos.core.runtime.boot

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BootContextRehydratorTest {
    private val t0 = Instant.parse("2026-09-08T10:00:00Z")
    private val t1 = Instant.parse("2026-09-08T10:01:00Z")

    @Test
    fun rehydratesActiveContextAndRecentRelevantPhotonsFromDurableTruth() {
        val source = photon(
            id = "context-source",
            content = "Continue LIFEOS",
            createdAt = t0,
            tags = setOf("context:conversation:conv-1", "context:active"),
        )
        val followUp = photon(
            id = "follow-up",
            content = "latest durable message",
            createdAt = t1,
            tags = setOf("context:conversation:conv-1"),
        )
        val snapshot = snapshot(
            photons = listOf(source, followUp),
            contexts = listOf(
                BootContextProjection(
                    kind = BootContextKind.CONVERSATION,
                    contextId = "conv-1",
                    sourcePhotonId = source.id,
                    sourcePhotonRevision = source.revision,
                    sourceCreatedAt = t0,
                    explicitlyActive = true,
                )
            ),
        )

        val rehydrated = BootContextRehydrator().rehydrate(snapshot)
        val resolution = rehydrated.resolve(BootContextKind.CONVERSATION)

        assertEquals(BootContextResolutionStatus.RESOLVED, resolution.status)
        assertEquals("conv-1", resolution.resolved?.contextId)
        assertEquals(listOf("follow-up", "context-source"), resolution.resolved?.relevantPhotonIds?.map { it.value })
        assertEquals(emptyList(), rehydrated.missingSourcePhotonIds)
    }

    @Test
    fun equallyRecentActiveContextsRemainUnresolved() {
        val a = photon("a", "A", t0)
        val b = photon("b", "B", t0)
        val contexts = listOf(
            BootContextProjection(
                BootContextKind.PROJECT, "project-a", a.id, 1, t0, true,
            ),
            BootContextProjection(
                BootContextKind.PROJECT, "project-b", b.id, 1, t0, true,
            ),
        )
        val result = BootContextRehydrator().rehydrate(snapshot(listOf(a, b), contexts))

        val resolution = result.resolve(BootContextKind.PROJECT)

        assertEquals(BootContextResolutionStatus.UNRESOLVED, resolution.status)
        assertEquals(listOf("project-a", "project-b"), resolution.candidates.map { it.contextId })
        assertNull(resolution.resolved)
    }

    @Test
    fun missingSourcePhotonIsReportedInsteadOfInventingContext() {
        val missing = PhotonId("missing")
        val result = BootContextRehydrator().rehydrate(
            snapshot(
                photons = emptyList(),
                contexts = listOf(
                    BootContextProjection(
                        BootContextKind.GOAL,
                        "goal-1",
                        missing,
                        1,
                        t0,
                        true,
                    )
                ),
            )
        )

        assertEquals(BootContextResolutionStatus.NONE, result.resolve(BootContextKind.GOAL).status)
        assertEquals(listOf(missing), result.missingSourcePhotonIds)
    }

    private fun snapshot(
        photons: List<Photon>,
        contexts: List<BootContextProjection>,
    ) = DurableBootSnapshot(
        generationId = BootGenerationId("a".repeat(64)),
        capturedAt = t1,
        photons = photons.sortedWith(compareBy<Photon>({ it.id.value }, { it.revision })),
        tasks = emptyList(),
        checkpoints = emptyList(),
        contexts = contexts.sortedWith(compareBy({ it.kind.name }, { it.contextId }, { it.sourceCreatedAt }, { it.sourcePhotonId.value }, { it.sourcePhotonRevision })),
        capabilities = emptyList(),
        workerLeases = emptyList(),
        tools = emptyList(),
        fieldSnapshots = emptyList(),
        readFailures = emptyList(),
    )

    private fun photon(
        id: String,
        content: String,
        createdAt: Instant,
        tags: Set<String> = emptySet(),
    ) = Photon(
        id = PhotonId(id),
        content = content,
        provenance = Provenance("test", "tester", createdAt),
        tags = tags,
    )
}
