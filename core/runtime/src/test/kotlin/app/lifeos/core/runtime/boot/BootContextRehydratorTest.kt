package app.lifeos.core.runtime.boot

import app.lifeos.core.field.FieldSnapshotLoadReport
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.checkpoint.CheckpointLoadReport
import app.lifeos.core.model.task.TaskLoadReport
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BootContextRehydratorTest {
    private val t0 = Instant.parse("2026-09-08T12:00:00Z")

    @Test
    fun firstPostRestartWeiterResolvesAgainstD01DurableConversation() = runTest {
        val earlier = photon(
            id = "chat-1",
            content = "Wir bauen LIFEOS weiter aus",
            createdAt = t0.minusSeconds(120),
            tags = setOf("chat", "context:conversation:lifeos-build"),
        )
        val active = photon(
            id = "chat-2",
            content = "Als nächstes kommt Boot Cognition",
            createdAt = t0.minusSeconds(30),
            tags = setOf(
                "chat",
                "context:conversation:lifeos-build",
                "context:project:lifeos",
                "context:goal:continue-build",
                "context:active",
            ),
            parentIds = setOf(earlier.id),
            relations = setOf(PhotonRelation(earlier.id, RelationType.REFERENCES)),
        )

        // Simulate a new process: no ViewModel/session object is supplied. D01 reconstructs solely
        // from durable source adapters.
        val durable = loader(PhotonLoadReport(listOf(active, earlier), emptyList())).load()
        val integrity = BootIntegrityScanner(now = { t0 }).scan(durable)
        val context = BootContextRehydrator().rehydrate(durable, integrity)
        val resolution = BootContinuationResolver().resolve("weiter", context)

        val resolved = assertIs<BootContinuationResolution.Resolved>(resolution)
        assertEquals(BootContextKind.CONVERSATION, resolved.context.kind)
        assertEquals("lifeos-build", resolved.context.contextId)
        assertEquals(listOf("chat-2", "chat-1"), context.relevantPhotons.map { it.id.value })
        assertTrue(resolved.relevantPhotonIds.containsAll(listOf(active.id, earlier.id)))
        assertEquals(BootContextResolutionState.RESOLVED, context.project.state)
        assertEquals(BootContextResolutionState.RESOLVED, context.goal.state)
    }

    @Test
    fun multipleActiveConversationsStayExplicitlyUnresolved() = runTest {
        val first = photon(
            id = "a",
            content = "A",
            createdAt = t0.minusSeconds(20),
            tags = setOf("context:conversation:a", "context:active"),
        )
        val second = photon(
            id = "b",
            content = "B",
            createdAt = t0.minusSeconds(10),
            tags = setOf("context:conversation:b", "context:active"),
        )
        val durable = loader(PhotonLoadReport(listOf(first, second), emptyList())).load()
        val context = BootContextRehydrator().rehydrate(
            durable,
            BootIntegrityScanner(now = { t0 }).scan(durable),
        )

        assertEquals(BootContextResolutionState.UNRESOLVED, context.conversation.state)
        val unresolved = assertIs<BootContinuationResolution.Unresolved>(
            BootContinuationResolver().resolve("mach weiter", context),
        )
        assertEquals(BootContextKind.CONVERSATION, unresolved.kind)
        assertEquals(listOf("a", "b"), unresolved.candidateContextIds)
    }

    @Test
    fun unreadablePhotonSourceBlocksContinuationRatherThanGuessing() = runTest {
        val active = photon(
            id = "known",
            content = "Known readable fragment",
            createdAt = t0,
            tags = setOf("context:conversation:known", "context:active"),
        )
        val durable = loader(
            PhotonLoadReport(
                photons = listOf(active),
                unreadableFiles = listOf("unknown-context.photon"),
            ),
        ).load()
        val integrity = BootIntegrityScanner(now = { t0 }).scan(durable)
        val context = BootContextRehydrator().rehydrate(durable, integrity)

        assertEquals(BootContextResolutionState.BLOCKED, context.conversation.state)
        val unresolved = assertIs<BootContinuationResolution.Unresolved>(
            BootContinuationResolver().resolve("continue", context),
        )
        assertEquals("photon-source-integrity-blocked", unresolved.reason)
    }

    @Test
    fun nonContinuationInputIsNotHijackedByBootContext() = runTest {
        val active = photon(
            id = "known",
            content = "Context",
            createdAt = t0,
            tags = setOf("context:conversation:known", "context:active"),
        )
        val durable = loader(PhotonLoadReport(listOf(active), emptyList())).load()
        val context = BootContextRehydrator().rehydrate(
            durable,
            BootIntegrityScanner(now = { t0 }).scan(durable),
        )

        assertIs<BootContinuationResolution.NotContinuation>(
            BootContinuationResolver().resolve("Erstelle ein Bild", context),
        )
    }

    private fun loader(report: PhotonLoadReport) = BootSnapshotLoader(
        photons = BootPhotonSource { report },
        tasks = BootTaskSource { TaskLoadReport(emptyList(), emptyList()) },
        checkpoints = BootCheckpointSource { CheckpointLoadReport(emptyList(), emptyList()) },
        capabilities = BootCapabilityStateSource { emptyList() },
        tools = BootToolStateSource { emptyList() },
        fieldSnapshots = BootFieldSnapshotSource { FieldSnapshotLoadReport(emptyList(), emptyList()) },
        now = { t0 },
    )

    private fun photon(
        id: String,
        content: String,
        createdAt: Instant,
        tags: Set<String>,
        parentIds: Set<PhotonId> = emptySet(),
        relations: Set<PhotonRelation> = emptySet(),
    ) = Photon(
        id = PhotonId(id),
        content = content,
        provenance = Provenance(
            source = "durable-chat",
            actor = "user",
            createdAt = createdAt,
            parentIds = parentIds,
        ),
        relations = relations,
        tags = tags,
    )
}
