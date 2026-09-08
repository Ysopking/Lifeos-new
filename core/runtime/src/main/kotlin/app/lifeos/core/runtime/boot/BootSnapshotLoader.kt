package app.lifeos.core.runtime.boot

import app.lifeos.core.field.FieldSnapshot
import app.lifeos.core.field.FieldSnapshotLoadReport
import app.lifeos.core.field.FieldSnapshotRepository
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.checkpoint.CheckpointLoadReport
import app.lifeos.core.model.checkpoint.CheckpointSnapshotRepository
import app.lifeos.core.model.checkpoint.TaskCheckpoint
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskLoadReport
import app.lifeos.core.model.task.TaskSnapshotRepository
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CancellationException

@JvmInline
value class BootGenerationId(val value: String) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) { "Boot generation id must be SHA-256 hex" }
    }
}

enum class BootSnapshotSource {
    PHOTON,
    TASK,
    CHECKPOINT,
    CAPABILITY,
    TOOL,
    FIELD,
}

data class BootSnapshotReadFailure(
    val source: BootSnapshotSource,
    val entry: String? = null,
    val reason: String,
) {
    init {
        require(entry == null || entry.isNotBlank()) { "Boot failure entry must not be blank" }
        require(reason.isNotBlank()) { "Boot failure reason must not be blank" }
        require(reason.length <= 256) { "Boot failure reason too long" }
    }
}

fun interface BootCapabilityStateSource {
    suspend fun load(): List<CapabilityDescriptor>
}

fun interface BootToolStateSource {
    suspend fun load(): List<GeneratedToolRecord>
}

class CapabilityRegistryBootSource(
    private val registry: CapabilityRegistry,
) : BootCapabilityStateSource {
    override suspend fun load(): List<CapabilityDescriptor> = registry.all(includeUnavailable = true)
}

class GeneratedToolRegistryBootSource(
    private val registry: GeneratedToolRegistry,
) : BootToolStateSource {
    override suspend fun load(): List<GeneratedToolRecord> = registry.snapshot()
}

enum class BootContextKind {
    CONVERSATION,
    PROJECT,
    GOAL,
}

data class BootContextProjection(
    val kind: BootContextKind,
    val contextId: String,
    val sourcePhotonId: PhotonId,
    val sourcePhotonRevision: Long,
    val sourceCreatedAt: Instant,
    val explicitlyActive: Boolean,
) {
    init {
        require(contextId.isNotBlank()) { "Boot context id must not be blank" }
        require(sourcePhotonRevision > 0) { "Boot context source revision must be positive" }
    }
}

data class BootWorkerLease(
    val workerId: WorkerId,
    val taskId: String,
    val taskState: TaskState,
    val leaseExpiresAt: Instant,
)

data class DurableBootSnapshot(
    val generationId: BootGenerationId,
    val capturedAt: Instant,
    val photons: List<Photon>,
    val tasks: List<LifeTask>,
    val checkpoints: List<TaskCheckpoint>,
    val contexts: List<BootContextProjection>,
    val capabilities: List<CapabilityDescriptor>,
    val workerLeases: List<BootWorkerLease>,
    val tools: List<GeneratedToolRecord>,
    val fieldSnapshots: List<FieldSnapshot>,
    val readFailures: List<BootSnapshotReadFailure>,
) {
    init {
        require(photons == photons.sortedWith(compareBy<Photon>({ it.id.value }, { it.revision })))
        require(tasks == tasks.sortedBy { it.id.value })
        require(checkpoints == checkpoints.sortedWith(
            compareBy<TaskCheckpoint>({ it.taskId.value }, { it.sequence }, { it.id.value })
        ))
        require(contexts == contexts.sortedWith(contextProjectionComparator))
        require(capabilities == capabilities.sortedWith(capabilityComparator))
        require(workerLeases == workerLeases.sortedWith(workerLeaseComparator))
        require(tools == tools.sortedBy { it.manifest.toolId })
        require(fieldSnapshots == fieldSnapshots.sortedWith(fieldSnapshotComparator))
        require(readFailures == readFailures.sortedWith(readFailureComparator))
    }

    val partial: Boolean
        get() = readFailures.isNotEmpty()

    val activeConversations: List<BootContextProjection>
        get() = contexts.filter { it.kind == BootContextKind.CONVERSATION && it.explicitlyActive }

    val activeProjects: List<BootContextProjection>
        get() = contexts.filter { it.kind == BootContextKind.PROJECT && it.explicitlyActive }

    val activeGoals: List<BootContextProjection>
        get() = contexts.filter { it.kind == BootContextKind.GOAL && it.explicitlyActive }
}

class BootSnapshotLoader(
    private val photons: PhotonRepository,
    private val tasks: TaskSnapshotRepository,
    private val checkpoints: CheckpointSnapshotRepository,
    private val capabilities: BootCapabilityStateSource,
    private val tools: BootToolStateSource,
    private val fieldSnapshots: FieldSnapshotRepository,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun load(): DurableBootSnapshot {
        val failures = mutableListOf<BootSnapshotReadFailure>()

        val photonReport = readSource(
            source = BootSnapshotSource.PHOTON,
            fallback = PhotonLoadReport(emptyList(), emptyList()),
            failures = failures,
        ) { photons.loadReport() }
        failures += photonReport.unreadableFiles.map {
            BootSnapshotReadFailure(BootSnapshotSource.PHOTON, it, "unreadable-entry")
        }

        val taskReport = readSource(
            source = BootSnapshotSource.TASK,
            fallback = TaskLoadReport(emptyList(), emptyList()),
            failures = failures,
        ) { tasks.loadReport() }
        failures += taskReport.unreadableEntries.map {
            BootSnapshotReadFailure(BootSnapshotSource.TASK, it, "unreadable-entry")
        }

        val checkpointReport = readSource(
            source = BootSnapshotSource.CHECKPOINT,
            fallback = CheckpointLoadReport(emptyList(), emptyList()),
            failures = failures,
        ) { checkpoints.loadReport() }
        failures += checkpointReport.unreadableEntries.map {
            BootSnapshotReadFailure(BootSnapshotSource.CHECKPOINT, it, "unreadable-entry")
        }

        val capabilityState = readSource(
            source = BootSnapshotSource.CAPABILITY,
            fallback = emptyList(),
            failures = failures,
        ) { capabilities.load() }

        val toolState = readSource(
            source = BootSnapshotSource.TOOL,
            fallback = emptyList(),
            failures = failures,
        ) { tools.load() }

        val fieldReport = readSource(
            source = BootSnapshotSource.FIELD,
            fallback = FieldSnapshotLoadReport(emptyList(), emptyList()),
            failures = failures,
        ) { fieldSnapshots.loadReport() }
        failures += fieldReport.unreadableEntries.map {
            BootSnapshotReadFailure(BootSnapshotSource.FIELD, it, "unreadable-entry")
        }

        val canonicalPhotons = photonReport.photons.sortedWith(compareBy<Photon>({ it.id.value }, { it.revision }))
        val canonicalTasks = taskReport.tasks.sortedBy { it.id.value }
        val canonicalCheckpoints = checkpointReport.checkpoints.sortedWith(
            compareBy<TaskCheckpoint>({ it.taskId.value }, { it.sequence }, { it.id.value })
        )
        val canonicalContexts = projectContexts(canonicalPhotons)
        val canonicalCapabilities = capabilityState.sortedWith(capabilityComparator)
        val canonicalWorkerLeases = projectWorkerLeases(canonicalTasks)
        val canonicalTools = toolState.sortedBy { it.manifest.toolId }
        val canonicalFields = fieldReport.snapshots.sortedWith(fieldSnapshotComparator)
        val canonicalFailures = failures.distinct().sortedWith(readFailureComparator)

        val generationId = fingerprintGeneration(
            photons = canonicalPhotons,
            tasks = canonicalTasks,
            checkpoints = canonicalCheckpoints,
            contexts = canonicalContexts,
            capabilities = canonicalCapabilities,
            workerLeases = canonicalWorkerLeases,
            tools = canonicalTools,
            fieldSnapshots = canonicalFields,
            failures = canonicalFailures,
        )

        return DurableBootSnapshot(
            generationId = generationId,
            capturedAt = now(),
            photons = canonicalPhotons,
            tasks = canonicalTasks,
            checkpoints = canonicalCheckpoints,
            contexts = canonicalContexts,
            capabilities = canonicalCapabilities,
            workerLeases = canonicalWorkerLeases,
            tools = canonicalTools,
            fieldSnapshots = canonicalFields,
            readFailures = canonicalFailures,
        )
    }

    private suspend fun <T> readSource(
        source: BootSnapshotSource,
        fallback: T,
        failures: MutableList<BootSnapshotReadFailure>,
        read: suspend () -> T,
    ): T = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        failures += BootSnapshotReadFailure(
            source = source,
            reason = "source-exception:${error.javaClass.simpleName}",
        )
        fallback
    }
}

private fun projectContexts(photons: List<Photon>): List<BootContextProjection> = buildList {
    photons.forEach { photon ->
        val tags = photon.tags
        tags.sorted().forEach { tag ->
            val parsed = parseContextTag(tag) ?: return@forEach
            val active = "context:active" in tags ||
                "context:active:${parsed.kind.name.lowercase()}:${parsed.contextId}" in tags
            add(
                BootContextProjection(
                    kind = parsed.kind,
                    contextId = parsed.contextId,
                    sourcePhotonId = photon.id,
                    sourcePhotonRevision = photon.revision,
                    sourceCreatedAt = photon.provenance.createdAt,
                    explicitlyActive = active,
                )
            )
        }
    }
}.distinct().sortedWith(contextProjectionComparator)

private data class ParsedContextTag(
    val kind: BootContextKind,
    val contextId: String,
)

private fun parseContextTag(tag: String): ParsedContextTag? {
    val parts = tag.split(':', limit = 3)
    if (parts.size != 3 || parts[0] != "context") return null
    val kind = when (parts[1]) {
        "conversation" -> BootContextKind.CONVERSATION
        "project" -> BootContextKind.PROJECT
        "goal" -> BootContextKind.GOAL
        else -> return null
    }
    val id = parts[2].trim()
    if (id.isBlank() || id == "active") return null
    return ParsedContextTag(kind, id)
}

private fun projectWorkerLeases(tasks: List<LifeTask>): List<BootWorkerLease> = tasks.mapNotNull { task ->
    val workerId = task.claimedBy ?: return@mapNotNull null
    val lease = task.leaseExpiresAt ?: return@mapNotNull null
    BootWorkerLease(
        workerId = workerId,
        taskId = task.id.value,
        taskState = task.state,
        leaseExpiresAt = lease,
    )
}.sortedWith(workerLeaseComparator)

private fun fingerprintGeneration(
    photons: List<Photon>,
    tasks: List<LifeTask>,
    checkpoints: List<TaskCheckpoint>,
    contexts: List<BootContextProjection>,
    capabilities: List<CapabilityDescriptor>,
    workerLeases: List<BootWorkerLease>,
    tools: List<GeneratedToolRecord>,
    fieldSnapshots: List<FieldSnapshot>,
    failures: List<BootSnapshotReadFailure>,
): BootGenerationId {
    val digest = MessageDigest.getInstance("SHA-256")
    fun part(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.UTF_8))
        digest.update(':'.code.toByte())
        digest.update(bytes)
        digest.update('\n'.code.toByte())
    }

    part("lifeos-boot-generation/v1")
    photons.forEach { photon ->
        part("photon")
        part(photon.id.value); part(photon.revision.toString()); part(photon.phase.name)
        part(sha256(photon.content)); part(photon.mimeType)
        part(java.lang.Double.toHexString(photon.semanticMass))
        part(java.lang.Double.toHexString(photon.energy))
        part(java.lang.Double.toHexString(photon.confidence))
        part(photon.provenance.source); part(photon.provenance.actor); part(photon.provenance.createdAt.toString())
        photon.provenance.parentIds.map { it.value }.sorted().forEach(::part)
        photon.relations.sortedWith(compareBy({ it.target.value }, { it.type.name }, { it.weight })).forEach {
            part("relation:${it.target.value}:${it.type.name}:${java.lang.Double.toHexString(it.weight)}")
        }
        photon.tags.sorted().forEach { part("tag:$it") }
    }
    tasks.forEach { task ->
        part("task")
        part(task.id.value); part(task.type.name); part(task.state.name); part(task.priority.name)
        part(task.idempotencyKey); part(task.attempt.toString()); part(task.maxAttempts.toString())
        part(task.createdAt.toString()); part(task.updatedAt.toString()); part(task.scheduledAt?.toString() ?: "-")
        part(task.claimedBy?.value ?: "-"); part(task.leaseExpiresAt?.toString() ?: "-")
        task.inputPhotonIds.map { it.value }.sorted().forEach { part("input:$it") }
        task.inputPhotonRevisions.entries.sortedBy { it.key.value }.forEach { part("revision:${it.key.value}:${it.value}") }
    }
    checkpoints.forEach { checkpoint ->
        part("checkpoint")
        part(checkpoint.id.value); part(checkpoint.taskId.value); part(checkpoint.sequence.toString())
        part(checkpoint.runtimeGeneration.toString()); part(checkpoint.createdAt.toString()); part(sha256(checkpoint.payload))
    }
    contexts.forEach { context ->
        part("context:${context.kind.name}:${context.contextId}:${context.sourcePhotonId.value}:${context.sourcePhotonRevision}:${context.sourceCreatedAt}:${context.explicitlyActive}")
    }
    capabilities.forEach { capability ->
        part("capability:${capability.capabilityId.value}:${capability.providerId}:${capability.providerType.name}:${capability.state.name}:${capability.trustLevel.name}")
        part(java.lang.Double.toHexString(capability.reliability)); part(java.lang.Double.toHexString(capability.cost))
        capability.contract.requiredInputs.sorted().forEach { part("requires:$it") }
        capability.contract.outputs.sorted().forEach { part("outputs:$it") }
    }
    workerLeases.forEach { lease ->
        part("worker:${lease.workerId.value}:${lease.taskId}:${lease.taskState.name}:${lease.leaseExpiresAt}")
    }
    tools.forEach { tool ->
        val manifest = tool.manifest
        part("tool:${manifest.toolId}:${tool.state.name}:${manifest.sourceCapability.value}:${manifest.sourceHash}:${manifest.buildHash ?: "-"}")
        part(java.lang.Double.toHexString(tool.verificationConfidence)); part(tool.lastMessage ?: "-")
        part(manifest.generatedAt.toString())
        manifest.permissions.map { it.name }.sorted().forEach { part("permission:$it") }
        manifest.requiredInputs.sorted().forEach { part("tool-input:$it") }
        manifest.requiredOutputs.sorted().forEach { part("tool-output:$it") }
    }
    fieldSnapshots.forEach { snapshot ->
        part("field:${snapshot.id.value}:${snapshot.runId.value}:${snapshot.domainId.value}:${snapshot.status.name}")
        part(snapshot.inputFingerprint); part(snapshot.fieldSetFingerprint); part(snapshot.traceFingerprint)
    }
    failures.forEach { failure ->
        part("failure:${failure.source.name}:${failure.entry ?: "-"}:${failure.reason}")
    }

    return BootGenerationId(digest.digest().joinToString("") { "%02x".format(it) })
}

private fun sha256(value: String): String = sha256(value.toByteArray(StandardCharsets.UTF_8))

private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { "%02x".format(it) }

private val contextProjectionComparator = compareBy<BootContextProjection>(
    { it.kind.name },
    { it.contextId },
    { it.sourceCreatedAt },
    { it.sourcePhotonId.value },
    { it.sourcePhotonRevision },
)

private val capabilityComparator = compareBy<CapabilityDescriptor>(
    { it.capabilityId.value },
    { it.providerId },
)

private val workerLeaseComparator = compareBy<BootWorkerLease>(
    { it.workerId.value },
    { it.taskId },
    { it.taskState.name },
    { it.leaseExpiresAt },
)

private val fieldSnapshotComparator = compareBy<FieldSnapshot>(
    { it.domainId.value },
    { it.id.value },
)

private val readFailureComparator = compareBy<BootSnapshotReadFailure>(
    { it.source.name },
    { it.entry ?: "" },
    { it.reason },
)
