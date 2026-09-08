package app.lifeos.core.runtime.boot

import app.lifeos.core.field.FieldSnapshotLoadReport
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.checkpoint.CheckpointId
import app.lifeos.core.model.checkpoint.CheckpointLoadReport
import app.lifeos.core.model.checkpoint.TaskCheckpoint
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskLoadReport
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BootSnapshotLoaderTest {
    private val t0 = Instant.parse("2026-09-08T10:00:00Z")
    private val t1 = Instant.parse("2026-09-08T10:01:00Z")

    @Test
    fun sameDurableTruthProducesSameGenerationRegardlessOfSourceOrderOrCaptureTime() = runTest {
        val a = photon("a", "alpha")
        val b = photon("b", "beta")
        val taskA = task("task-a")
        val taskB = task("task-b")

        val first = loader(
            photonReport = PhotonLoadReport(listOf(b, a), emptyList()),
            taskReport = TaskLoadReport(listOf(taskB, taskA), emptyList()),
            now = { t0 },
        ).load()
        val second = loader(
            photonReport = PhotonLoadReport(listOf(a, b), emptyList()),
            taskReport = TaskLoadReport(listOf(taskA, taskB), emptyList()),
            now = { t1 },
        ).load()

        assertEquals(first.generationId, second.generationId)
        assertNotEquals(first.capturedAt, second.capturedAt)
        assertEquals(listOf("a", "b"), first.photons.map { it.id.value })
        assertEquals(listOf("task-a", "task-b"), first.tasks.map { it.id.value })
        assertFalse(first.partial)
    }

    @Test
    fun photonRevisionOrContentChangesBootGeneration() = runTest {
        val original = photon("p", "before", revision = 1)
        val revised = photon("p", "after", revision = 2)

        val first = loader(photonReport = PhotonLoadReport(listOf(original), emptyList())).load()
        val second = loader(photonReport = PhotonLoadReport(listOf(revised), emptyList())).load()

        assertNotEquals(first.generationId, second.generationId)
    }

    @Test
    fun contextAndWorkerStateAreProjectedOnlyFromLoadedDurableRecords() = runTest {
        val conversation = photon(
            id = "conversation-photon",
            content = "durable chat context",
            tags = setOf(
                "context:conversation:conv-7",
                "context:project:lifeos",
                "context:goal:continue-build",
                "context:active",
            ),
        )
        val running = LifeTask(
            id = TaskId("task-running"),
            type = TaskType.PROCESS_PHOTON,
            state = TaskState.RUNNING,
            priority = TaskPriority.HIGH,
            idempotencyKey = "run-1",
            attempt = 1,
            maxAttempts = 3,
            createdAt = t0,
            updatedAt = t0,
            claimedBy = WorkerId("worker-7"),
            leaseExpiresAt = t1,
        )

        val snapshot = loader(
            photonReport = PhotonLoadReport(listOf(conversation), emptyList()),
            taskReport = TaskLoadReport(listOf(running), emptyList()),
        ).load()

        assertEquals("conv-7", snapshot.activeConversations.single().contextId)
        assertEquals("lifeos", snapshot.activeProjects.single().contextId)
        assertEquals("continue-build", snapshot.activeGoals.single().contextId)
        assertEquals("worker-7", snapshot.workerLeases.single().workerId.value)
        assertEquals("task-running", snapshot.workerLeases.single().taskId)
    }

    @Test
    fun partialReadKeepsReadableDataAndRecordsEveryUnavailableEntry() = runTest {
        val readable = photon("ok", "still available")
        val snapshot = loader(
            photonReport = PhotonLoadReport(listOf(readable), listOf("broken.photon")),
            taskReport = TaskLoadReport(emptyList(), listOf("broken.task")),
            checkpointReport = CheckpointLoadReport(emptyList(), listOf("broken.checkpoint")),
            fieldReport = FieldSnapshotLoadReport(emptyList(), listOf("broken.field")),
            capabilities = BootCapabilityStateSource { error("registry unavailable") },
        ).load()

        assertTrue(snapshot.partial)
        assertEquals(listOf("ok"), snapshot.photons.map { it.id.value })
        assertEquals(
            setOf(
                BootSnapshotSource.PHOTON,
                BootSnapshotSource.TASK,
                BootSnapshotSource.CHECKPOINT,
                BootSnapshotSource.CAPABILITY,
                BootSnapshotSource.FIELD,
            ),
            snapshot.readFailures.map { it.source }.toSet(),
        )
        assertTrue(snapshot.readFailures.any { it.reason == "source-exception:IllegalStateException" })
    }

    @Test
    fun capabilityAndCheckpointStateParticipateInGenerationIdentity() = runTest {
        val checkpoint = TaskCheckpoint(
            id = CheckpointId("checkpoint-1"),
            taskId = TaskId("task-a"),
            sequence = 1,
            runtimeGeneration = 3,
            payload = byteArrayOf(1, 2, 3),
            createdAt = t0,
        )
        val active = capability(ProviderState.ACTIVE)
        val degraded = capability(ProviderState.DEGRADED)

        val first = loader(
            checkpointReport = CheckpointLoadReport(listOf(checkpoint), emptyList()),
            capabilities = BootCapabilityStateSource { listOf(active) },
        ).load()
        val second = loader(
            checkpointReport = CheckpointLoadReport(listOf(checkpoint), emptyList()),
            capabilities = BootCapabilityStateSource { listOf(degraded) },
        ).load()

        assertEquals("checkpoint-1", first.checkpoints.single().id.value)
        assertNotEquals(first.generationId, second.generationId)
    }

    @Test
    fun cancellationPropagatesInsteadOfBecomingPartialSnapshot() = runTest {
        val loader = BootSnapshotLoader(
            photons = BootPhotonSource { throw CancellationException("cancel") },
            tasks = BootTaskSource { TaskLoadReport(emptyList(), emptyList()) },
            checkpoints = BootCheckpointSource { CheckpointLoadReport(emptyList(), emptyList()) },
            capabilities = BootCapabilityStateSource { emptyList() },
            tools = BootToolStateSource { emptyList() },
            fieldSnapshots = BootFieldSnapshotSource { FieldSnapshotLoadReport(emptyList(), emptyList()) },
            now = { t0 },
        )

        assertFailsWith<CancellationException> { loader.load() }
    }

    private fun loader(
        photonReport: PhotonLoadReport = PhotonLoadReport(emptyList(), emptyList()),
        taskReport: TaskLoadReport = TaskLoadReport(emptyList(), emptyList()),
        checkpointReport: CheckpointLoadReport = CheckpointLoadReport(emptyList(), emptyList()),
        fieldReport: FieldSnapshotLoadReport = FieldSnapshotLoadReport(emptyList(), emptyList()),
        capabilities: BootCapabilityStateSource = BootCapabilityStateSource { emptyList() },
        now: () -> Instant = { t0 },
    ) = BootSnapshotLoader(
        photons = BootPhotonSource { photonReport },
        tasks = BootTaskSource { taskReport },
        checkpoints = BootCheckpointSource { checkpointReport },
        capabilities = capabilities,
        tools = BootToolStateSource { emptyList() },
        fieldSnapshots = BootFieldSnapshotSource { fieldReport },
        now = now,
    )

    private fun photon(
        id: String,
        content: String,
        revision: Long = 1,
        tags: Set<String> = emptySet(),
    ) = Photon(
        id = PhotonId(id),
        revision = revision,
        content = content,
        provenance = Provenance(
            source = "test",
            actor = "tester",
            createdAt = t0,
        ),
        tags = tags,
    )

    private fun task(id: String) = LifeTask(
        id = TaskId(id),
        type = TaskType.PROCESS_PHOTON,
        state = TaskState.CREATED,
        priority = TaskPriority.NORMAL,
        idempotencyKey = "key-$id",
        createdAt = t0,
        updatedAt = t0,
    )

    private fun capability(state: ProviderState) = CapabilityDescriptor(
        capabilityId = CapabilityId("test.capability"),
        providerId = "provider-a",
        providerType = ProviderType.MODULE,
        state = state,
        trustLevel = TrustLevel.SYSTEM,
        reliability = 0.9,
        cost = 0.0,
    )
}
