package app.lifeos.core.runtime.boot

import app.lifeos.core.field.ConvergenceConfig
import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.DomainContext
import app.lifeos.core.field.FieldContext
import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.field.FieldConvergenceRequest
import app.lifeos.core.field.FieldGraph
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.FieldNode
import app.lifeos.core.field.FieldNodeKind
import app.lifeos.core.field.FieldSnapshot
import app.lifeos.core.field.HypothesisScope
import app.lifeos.core.field.HypothesisState
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalContext
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.checkpoint.CheckpointId
import app.lifeos.core.model.checkpoint.TaskCheckpoint
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.GeneratedToolManifest
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootIntegrityScannerTest {
    private val t0 = Instant.parse("2026-09-08T12:00:00Z")
    private val generation = BootGenerationId("0".repeat(64))

    @Test
    fun cleanCrossStoreSnapshotHasNoFindings() {
        val photon = photon("p-1", revision = 2)
        val task = task(
            id = "task-1",
            inputIds = setOf(photon.id),
            inputRevisions = mapOf(photon.id to photon.revision),
        )
        val report = BootIntegrityScanner(now = { t0 }).scan(
            snapshot(
                photons = listOf(photon),
                tasks = listOf(task),
            ),
        )

        assertTrue(report.findings.isEmpty())
        assertTrue(report.canProceedNormally)
        assertFalse(report.requiresRecovery)
        assertEquals(generation, report.generationId)
    }

    @Test
    fun unreadableDurableEntryBlocksNormalBootWithoutMutatingReadableState() {
        val readable = photon("readable")
        val report = BootIntegrityScanner(now = { t0 }).scan(
            snapshot(
                photons = listOf(readable),
                readFailures = listOf(
                    BootSnapshotReadFailure(
                        source = BootSnapshotSource.PHOTON,
                        entry = "broken.photon",
                        reason = "unreadable-entry",
                    ),
                ),
            ),
        )

        assertEquals(listOf("SOURCE_ENTRY_UNREADABLE"), report.findings.map { it.code })
        assertFalse(report.canProceedNormally)
        assertTrue(report.requiresRecovery)
        assertEquals(BootRepairability.RECOVERY_REQUIRED, report.findings.single().repairability)
    }

    @Test
    fun detectsTaskPhotonAndCheckpointCrossStoreViolations() {
        val task = task(
            id = "task-missing-input",
            inputIds = setOf(PhotonId("missing-photon")),
        )
        val orphanCheckpoint = TaskCheckpoint(
            id = CheckpointId("checkpoint-orphan"),
            taskId = TaskId("missing-task"),
            sequence = 1,
            runtimeGeneration = 1,
            payload = byteArrayOf(1),
            createdAt = t0,
        )

        val report = BootIntegrityScanner(now = { t0 }).scan(
            snapshot(
                tasks = listOf(task),
                checkpoints = listOf(orphanCheckpoint),
            ),
        )

        assertEquals(
            setOf("TASK_INPUT_PHOTON_MISSING", "ORPHAN_CHECKPOINT"),
            report.findings.map { it.code }.toSet(),
        )
        assertTrue(report.findings.all { it.severity == BootIntegritySeverity.ERROR })
        assertTrue(report.requiresRecovery)
    }

    @Test
    fun detectsCheckpointSequenceGenerationAndTaskStateViolations() {
        val task = LifeTask(
            id = TaskId("task-checkpointed"),
            type = TaskType.PROCESS_PHOTON,
            state = TaskState.CHECKPOINTED,
            priority = TaskPriority.NORMAL,
            idempotencyKey = "checkpointed",
            attempt = 1,
            createdAt = t0.minusSeconds(60),
            updatedAt = t0.minusSeconds(30),
            claimedBy = WorkerId("worker-1"),
            leaseExpiresAt = t0.plusSeconds(60),
        )
        val first = TaskCheckpoint(
            id = CheckpointId("cp-a"),
            taskId = task.id,
            sequence = 1,
            runtimeGeneration = 4,
            payload = byteArrayOf(1),
            createdAt = t0.minusSeconds(20),
        )
        val duplicate = TaskCheckpoint(
            id = CheckpointId("cp-b"),
            taskId = task.id,
            sequence = 1,
            runtimeGeneration = 4,
            payload = byteArrayOf(2),
            createdAt = t0.minusSeconds(10),
        )
        val regressed = TaskCheckpoint(
            id = CheckpointId("cp-c"),
            taskId = task.id,
            sequence = 2,
            runtimeGeneration = 3,
            payload = byteArrayOf(3),
            createdAt = t0,
        )

        val report = BootIntegrityScanner(now = { t0 }).scan(
            snapshot(
                tasks = listOf(task),
                checkpoints = listOf(first, duplicate, regressed),
                workerLeases = listOf(
                    BootWorkerLease(
                        workerId = WorkerId("worker-1"),
                        taskId = task.id.value,
                        taskState = task.state,
                        leaseExpiresAt = t0.plusSeconds(60),
                    ),
                ),
            ),
        )

        assertTrue(report.findings.any { it.code == "DUPLICATE_CHECKPOINT_SEQUENCE" })
        assertTrue(report.findings.any { it.code == "CHECKPOINT_GENERATION_REGRESSION" })
        assertFalse(report.findings.any { it.code == "CHECKPOINTED_TASK_WITHOUT_CHECKPOINT" })
    }

    @Test
    fun staleContextAndExpiredLeaseRemainReportOnlyEvidence() {
        val source = photon("context-source", revision = 2)
        val task = LifeTask(
            id = TaskId("task-running"),
            type = TaskType.PROCESS_PHOTON,
            state = TaskState.RUNNING,
            priority = TaskPriority.NORMAL,
            idempotencyKey = "running",
            attempt = 1,
            createdAt = t0.minusSeconds(120),
            updatedAt = t0.minusSeconds(90),
            claimedBy = WorkerId("worker-1"),
            leaseExpiresAt = t0.minusSeconds(1),
        )
        val context = BootContextProjection(
            kind = BootContextKind.PROJECT,
            contextId = "lifeos",
            sourcePhotonId = source.id,
            sourcePhotonRevision = 1,
            sourceCreatedAt = source.provenance.createdAt,
            explicitlyActive = true,
        )
        val lease = BootWorkerLease(
            workerId = WorkerId("worker-1"),
            taskId = task.id.value,
            taskState = task.state,
            leaseExpiresAt = requireNotNull(task.leaseExpiresAt),
        )

        val report = BootIntegrityScanner(now = { t0 }).scan(
            snapshot(
                photons = listOf(source),
                tasks = listOf(task),
                contexts = listOf(context),
                workerLeases = listOf(lease),
            ),
        )

        assertTrue(report.findings.any {
            it.code == "CONTEXT_SOURCE_REVISION_STALE" &&
                it.repairability == BootRepairability.REBUILD_DERIVED_PROJECTION
        })
        assertTrue(report.findings.any {
            it.code == "STALE_WORKER_LEASE" && it.severity == BootIntegritySeverity.WARNING
        })
    }

    @Test
    fun activeGeneratedToolRequiresSourceCapabilityAndBuildIdentity() {
        val tool = GeneratedToolRecord(
            manifest = GeneratedToolManifest(
                toolId = "generated-a",
                sourceCapability = CapabilityId("missing.capability"),
                sourceHash = "source-hash",
                buildHash = null,
                permissions = emptySet(),
                generatedAt = t0,
            ),
            state = GeneratedToolState.ACTIVE,
            verificationConfidence = 0.95,
        )

        val report = BootIntegrityScanner(now = { t0 }).scan(snapshot(tools = listOf(tool)))

        assertEquals(
            setOf("TOOL_SOURCE_CAPABILITY_MISSING", "TOOL_BUILD_HASH_MISSING"),
            report.findings.map { it.code }.toSet(),
        )
        assertTrue(report.findings.all { it.severity == BootIntegritySeverity.ERROR })
    }

    @Test
    fun fieldSnapshotStatusMustMatchHypothesisState() {
        val field = mismatchedFieldSnapshot()

        val report = BootIntegrityScanner(now = { t0 }).scan(snapshot(fieldSnapshots = listOf(field)))

        assertTrue(report.findings.any {
            it.code == if (field.status == ConvergenceStatus.CONVERGED) {
                "FIELD_CONVERGENCE_STATE_MISMATCH"
            } else {
                "FIELD_UNRESOLVED_STATE_MISMATCH"
            }
        })
        assertFalse(report.canProceedNormally)
    }

    @Test
    fun orphanPhotonRelationIsNonDestructiveWarning() {
        val photon = photon("source").copy(
            relations = setOf(
                PhotonRelation(
                    target = PhotonId("missing-target"),
                    type = RelationType.REFERENCES,
                ),
            ),
        )

        val report = BootIntegrityScanner(now = { t0 }).scan(snapshot(photons = listOf(photon)))

        val finding = report.findings.single()
        assertEquals("ORPHAN_PHOTON_RELATION", finding.code)
        assertEquals(BootIntegritySeverity.WARNING, finding.severity)
        assertEquals(BootRepairability.USER_REVIEW, finding.repairability)
        assertTrue(report.canProceedNormally)
    }

    private fun snapshot(
        photons: List<Photon> = emptyList(),
        tasks: List<LifeTask> = emptyList(),
        checkpoints: List<TaskCheckpoint> = emptyList(),
        contexts: List<BootContextProjection> = emptyList(),
        workerLeases: List<BootWorkerLease> = emptyList(),
        tools: List<GeneratedToolRecord> = emptyList(),
        fieldSnapshots: List<FieldSnapshot> = emptyList(),
        readFailures: List<BootSnapshotReadFailure> = emptyList(),
    ) = DurableBootSnapshot(
        generationId = generation,
        capturedAt = t0,
        photons = photons.sortedWith(compareBy<Photon>({ it.id.value }, { it.revision })),
        tasks = tasks.sortedBy { it.id.value },
        checkpoints = checkpoints.sortedWith(compareBy<TaskCheckpoint>({ it.taskId.value }, { it.sequence }, { it.id.value })),
        contexts = contexts.sortedWith(compareBy({ it.kind.name }, { it.contextId }, { it.sourceCreatedAt }, { it.sourcePhotonId.value }, { it.sourcePhotonRevision })),
        capabilities = emptyList(),
        workerLeases = workerLeases.sortedWith(compareBy({ it.workerId.value }, { it.taskId }, { it.taskState.name }, { it.leaseExpiresAt })),
        tools = tools.sortedBy { it.manifest.toolId },
        fieldSnapshots = fieldSnapshots.sortedWith(compareBy({ it.domainId.value }, { it.id.value })),
        readFailures = readFailures,
    )

    private fun photon(id: String, revision: Long = 1) = Photon(
        id = PhotonId(id),
        revision = revision,
        content = "content-$id-r$revision",
        provenance = Provenance(
            source = "test",
            actor = "tester",
            createdAt = t0.minusSeconds(300),
        ),
    )

    private fun task(
        id: String,
        inputIds: Set<PhotonId> = emptySet(),
        inputRevisions: Map<PhotonId, Long> = emptyMap(),
    ) = LifeTask(
        id = TaskId(id),
        type = TaskType.PROCESS_PHOTON,
        state = TaskState.CREATED,
        priority = TaskPriority.NORMAL,
        inputPhotonIds = inputIds,
        inputPhotonRevisions = inputRevisions,
        idempotencyKey = "key-$id",
        createdAt = t0.minusSeconds(60),
        updatedAt = t0.minusSeconds(60),
    )

    private fun mismatchedFieldSnapshot(): FieldSnapshot {
        val domain = StableFieldIds.domain("boot-integrity-field")
        val node = FieldNode.create(
            domainId = domain,
            kind = FieldNodeKind.HYPOTHESIS,
            semanticKey = "candidate",
            baseEnergy = 1.0,
        )
        val request = FieldConvergenceRequest(
            domainId = domain,
            graph = FieldGraph(domain, listOf(node)),
            evidence = emptyList(),
            hypotheses = listOf(
                FieldHypothesis.create(
                    domainId = domain,
                    semanticKey = "candidate",
                    scope = HypothesisScope.DOMAIN,
                    nodeIds = setOf(node.id),
                    explanation = "candidate",
                ),
            ),
            context = FieldContext(
                temporal = TemporalContext(t0),
                domain = DomainContext(domain),
            ),
        )
        val result = FieldConvergenceEngine(
            config = ConvergenceConfig(maxIterations = 4, requiredStableRounds = 2, epsilon = 0.01),
        ).converge(request)
        val hasWinner = result.hypotheses.any { it.state == HypothesisState.CONVERGED }
        val mismatchedStatus = if (hasWinner) ConvergenceStatus.UNRESOLVED else ConvergenceStatus.CONVERGED
        return FieldSnapshot.create(
            status = mismatchedStatus,
            state = result.state,
            hypotheses = result.hypotheses,
            inputFingerprint = result.snapshot.inputFingerprint,
            fieldSetFingerprint = result.snapshot.fieldSetFingerprint,
            traceFingerprint = result.snapshot.traceFingerprint,
        )
    }
}
