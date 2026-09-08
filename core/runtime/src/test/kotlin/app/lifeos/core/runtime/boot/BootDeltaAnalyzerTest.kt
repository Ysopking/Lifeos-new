package app.lifeos.core.runtime.boot

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldEnergySnapshot
import app.lifeos.core.field.FieldSnapshot
import app.lifeos.core.field.FieldState
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.GeneratedToolManifest
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.ToolPermission
import app.lifeos.core.runtime.capability.TrustLevel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootDeltaAnalyzerTest {
    private val t0 = Instant.parse("2026-09-08T10:00:00Z")
    private val t1 = Instant.parse("2026-09-08T10:05:00Z")

    @Test
    fun detectsContextPhotonTaskFieldCapabilityAndToolPermissionChanges() {
        val oldPhoton = photon("p", 1, "before")
        val newPhoton = photon("p", 2, "after")
        val oldContext = BootContextProjection(
            BootContextKind.CONVERSATION, "conv-1", oldPhoton.id, 1, t0, true,
        )
        val newContext = BootContextProjection(
            BootContextKind.CONVERSATION, "conv-1", newPhoton.id, 2, t1, true,
        )

        val previous = snapshot(
            generation = "a",
            capturedAt = t0,
            photons = listOf(oldPhoton),
            contexts = listOf(oldContext),
            capabilities = listOf(capability(ProviderState.ACTIVE)),
            tools = listOf(tool(setOf(ToolPermission.READ_LOCAL_FILE))),
            fieldSnapshots = listOf(fieldSnapshot("input-a")),
        )
        val current = snapshot(
            generation = "b",
            capturedAt = t1,
            photons = listOf(newPhoton),
            contexts = listOf(newContext),
            tasks = listOf(overdueTask()),
            capabilities = listOf(capability(ProviderState.DEGRADED)),
            tools = listOf(tool(setOf(ToolPermission.READ_LOCAL_FILE, ToolPermission.NETWORK_ACCESS))),
            fieldSnapshots = listOf(fieldSnapshot("input-b")),
        )

        val delta = BootDeltaAnalyzer().analyze(previous, current)

        assertTrue(delta.hasMeaningfulDelta)
        assertEquals(BootContextDeltaKind.SOURCE_ADVANCED, delta.contextChanges.single().change)
        assertEquals(BootPhotonDeltaKind.REVISED, delta.photonChanges.single().change)
        assertEquals(MissedDurableTaskReason.OVERDUE_SCHEDULE, delta.missedDurableTasks.single().reason)
        assertEquals(1, delta.fieldWatermarkChanges.size)
        assertEquals(1, delta.capabilityChanges.size)
        assertEquals(listOf("NETWORK_ACCESS"), delta.toolChanges.single().addedPermissions)
        assertEquals(emptyList(), delta.toolChanges.single().removedPermissions)
    }

    @Test
    fun identicalDurableTruthProducesNoDelta() {
        val photon = photon("p", 1, "same")
        val context = BootContextProjection(
            BootContextKind.PROJECT, "lifeos", photon.id, 1, t0, true,
        )
        val base = snapshot(
            generation = "c",
            capturedAt = t0,
            photons = listOf(photon),
            contexts = listOf(context),
            capabilities = listOf(capability(ProviderState.ACTIVE)),
            tools = listOf(tool(setOf(ToolPermission.READ_LOCAL_FILE))),
            fieldSnapshots = listOf(fieldSnapshot("same-input")),
        )
        val same = base.copy(capturedAt = t1)

        val delta = BootDeltaAnalyzer().analyze(base, same)

        assertFalse(delta.hasMeaningfulDelta)
        assertEquals(emptyList(), delta.contextChanges)
        assertEquals(emptyList(), delta.photonChanges)
        assertEquals(emptyList(), delta.missedDurableTasks)
    }

    @Test
    fun expiredOwnedTaskIsSurfacedForRecovery() {
        val running = LifeTask(
            id = TaskId("running"),
            type = TaskType.PROCESS_PHOTON,
            state = TaskState.RUNNING,
            priority = TaskPriority.HIGH,
            idempotencyKey = "run",
            attempt = 1,
            maxAttempts = 3,
            createdAt = t0,
            updatedAt = t0,
            claimedBy = app.lifeos.core.model.worker.WorkerId("worker-1"),
            leaseExpiresAt = t0.plusSeconds(30),
        )
        val current = snapshot(
            generation = "d",
            capturedAt = t1,
            tasks = listOf(running),
        )

        val missed = BootDeltaAnalyzer().analyze(null, current).missedDurableTasks.single()

        assertEquals(MissedDurableTaskReason.EXPIRED_LEASE, missed.reason)
        assertEquals("running", missed.taskId)
    }

    private fun snapshot(
        generation: String,
        capturedAt: Instant,
        photons: List<Photon> = emptyList(),
        contexts: List<BootContextProjection> = emptyList(),
        tasks: List<LifeTask> = emptyList(),
        capabilities: List<CapabilityDescriptor> = emptyList(),
        tools: List<GeneratedToolRecord> = emptyList(),
        fieldSnapshots: List<FieldSnapshot> = emptyList(),
    ) = DurableBootSnapshot(
        generationId = BootGenerationId(generation.repeat(64)),
        capturedAt = capturedAt,
        photons = photons.sortedWith(compareBy<Photon>({ it.id.value }, { it.revision })),
        tasks = tasks.sortedBy { it.id.value },
        checkpoints = emptyList(),
        contexts = contexts.sortedWith(compareBy({ it.kind.name }, { it.contextId }, { it.sourceCreatedAt }, { it.sourcePhotonId.value }, { it.sourcePhotonRevision })),
        capabilities = capabilities.sortedWith(compareBy({ it.capabilityId.value }, { it.providerId })),
        workerLeases = emptyList(),
        tools = tools.sortedBy { it.manifest.toolId },
        fieldSnapshots = fieldSnapshots.sortedWith(compareBy({ it.domainId.value }, { it.id.value })),
        readFailures = emptyList(),
    )

    private fun photon(id: String, revision: Long, content: String) = Photon(
        id = PhotonId(id),
        revision = revision,
        content = content,
        provenance = Provenance("test", "tester", if (revision == 1L) t0 else t1),
    )

    private fun overdueTask() = LifeTask(
        id = TaskId("scheduled"),
        type = TaskType.REPROCESS_PHOTON,
        state = TaskState.QUEUED,
        priority = TaskPriority.NORMAL,
        idempotencyKey = "scheduled",
        createdAt = t0,
        updatedAt = t0,
        scheduledAt = t0.plusSeconds(30),
    )

    private fun capability(state: ProviderState) = CapabilityDescriptor(
        capabilityId = CapabilityId("cap.test"),
        providerId = "provider",
        providerType = ProviderType.MODULE,
        state = state,
        trustLevel = TrustLevel.SYSTEM,
        reliability = 0.9,
        cost = 0.0,
    )

    private fun tool(permissions: Set<ToolPermission>) = GeneratedToolRecord(
        manifest = GeneratedToolManifest(
            toolId = "tool-a",
            sourceCapability = CapabilityId("cap.test"),
            sourceHash = "source-hash",
            buildHash = "build-hash",
            permissions = permissions,
            generatedAt = t0,
        ),
        state = GeneratedToolState.ACTIVE,
        verificationConfidence = 0.9,
    )

    private fun fieldSnapshot(input: String): FieldSnapshot {
        val domainId = FieldDomainId("boot.test")
        val energy = FieldEnergySnapshot(emptyMap(), emptyMap())
        val state = FieldState.initial(domainId, input, energy)
        return FieldSnapshot.create(
            status = ConvergenceStatus.UNRESOLVED,
            state = state,
            hypotheses = emptyList(),
            inputFingerprint = input,
            fieldSetFingerprint = "field-set",
            traceFingerprint = "trace-$input",
        )
    }
}
