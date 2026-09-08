package app.lifeos.core.runtime.boot

import app.lifeos.core.field.FieldSnapshot
import app.lifeos.core.model.Photon
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

enum class BootDeltaImpact {
    INFO,
    CONTEXT,
    WORK,
    RECOVERY,
}

enum class BootDeltaKind {
    PHOTON_ADDED,
    PHOTON_REMOVED,
    PHOTON_REVISED,
    CONTEXT_ACTIVATED,
    CONTEXT_DEACTIVATED,
    CONTEXT_SOURCE_CHANGED,
    TASK_CHANGED,
    MISSED_DURABLE_WORK,
    FIELD_WATERMARK_CHANGED,
    CAPABILITY_ADDED,
    CAPABILITY_REMOVED,
    CAPABILITY_CHANGED,
    TOOL_ADDED,
    TOOL_REMOVED,
    TOOL_VERSION_CHANGED,
    TOOL_PERMISSIONS_CHANGED,
}

data class BootDelta(
    val kind: BootDeltaKind,
    val impact: BootDeltaImpact,
    val entityId: String,
    val previousFingerprint: String? = null,
    val currentFingerprint: String? = null,
    val detail: String,
) {
    init {
        require(entityId.isNotBlank()) { "Boot delta entity id must not be blank" }
        require(detail.isNotBlank()) { "Boot delta detail must not be blank" }
    }
}

enum class MissedWorkReason {
    QUEUED_DUE,
    RETRY_DUE,
    RECOVERY_PENDING,
    LEASE_EXPIRED,
}

data class MissedDurableWork(
    val taskId: String,
    val taskType: String,
    val state: TaskState,
    val reason: MissedWorkReason,
    val scheduledAt: Instant? = null,
    val leaseExpiresAt: Instant? = null,
)

data class FieldSnapshotWatermark(
    val domainId: String,
    val fingerprint: String,
    val snapshotIds: List<String>,
    val runIds: List<String>,
) {
    init {
        require(domainId.isNotBlank())
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        require(snapshotIds == snapshotIds.distinct().sorted())
        require(runIds == runIds.distinct().sorted())
    }
}

/**
 * Compact deterministic reference state intended to be embedded by D04 into durable boot evidence.
 * It contains only comparison identities/fingerprints, never mutable process/UI state.
 */
data class BootDeltaBaseline(
    val generationId: BootGenerationId,
    val photonVersions: Map<String, String>,
    val activeContexts: Map<String, String>,
    val taskVersions: Map<String, String>,
    val capabilityVersions: Map<String, String>,
    val toolVersions: Map<String, String>,
    val toolPermissions: Map<String, Set<String>>,
    val fieldWatermarks: Map<String, FieldSnapshotWatermark>,
) {
    companion object {
        fun from(snapshot: DurableBootSnapshot): BootDeltaBaseline = BootDeltaBaseline(
            generationId = snapshot.generationId,
            photonVersions = snapshot.photons
                .associate { it.id.value to photonFingerprint(it) }
                .toSortedMap(),
            activeContexts = snapshot.contexts
                .filter { it.explicitlyActive }
                .associate { contextKey(it) to contextFingerprint(it) }
                .toSortedMap(),
            taskVersions = snapshot.tasks
                .associate { it.id.value to taskFingerprint(it) }
                .toSortedMap(),
            capabilityVersions = snapshot.capabilities
                .associate { capabilityKey(it) to capabilityFingerprint(it) }
                .toSortedMap(),
            toolVersions = snapshot.tools
                .associate { it.manifest.toolId to toolFingerprint(it) }
                .toSortedMap(),
            toolPermissions = snapshot.tools
                .associate { tool ->
                    tool.manifest.toolId to tool.manifest.permissions.mapTo(sortedSetOf()) { it.name }
                }
                .toSortedMap(),
            fieldWatermarks = fieldWatermarks(snapshot.fieldSnapshots),
        )
    }
}

data class BootDeltaReport(
    val previousGenerationId: BootGenerationId?,
    val currentGenerationId: BootGenerationId,
    val baselineEstablished: Boolean,
    val deltas: List<BootDelta>,
    val missedWork: List<MissedDurableWork>,
    val fieldWatermarks: Map<String, FieldSnapshotWatermark>,
    val nextBaseline: BootDeltaBaseline,
) {
    init {
        require(deltas == deltas.sortedWith(bootDeltaComparator)) { "Boot deltas must be canonical" }
        require(missedWork == missedWork.sortedWith(missedWorkComparator)) { "Missed work must be canonical" }
        require(nextBaseline.generationId == currentGenerationId)
    }

    val hasChanges: Boolean get() = deltas.isNotEmpty()
    val requiresRecovery: Boolean get() = deltas.any { it.impact == BootDeltaImpact.RECOVERY }
}

/**
 * Compares the current D01 durable snapshot against a compact prior durable baseline. Capture time
 * itself never changes an entity fingerprint; it is used only to determine whether durable work is
 * already due or a persisted lease has expired.
 */
class BootDeltaAnalyzer {
    fun analyze(
        previous: BootDeltaBaseline?,
        current: DurableBootSnapshot,
    ): BootDeltaReport {
        val next = BootDeltaBaseline.from(current)
        val deltas = mutableListOf<BootDelta>()
        val missed = missedWork(current.tasks, current.capturedAt)

        if (previous != null) {
            compareSimpleMap(
                previous = previous.photonVersions,
                current = next.photonVersions,
                added = BootDeltaKind.PHOTON_ADDED,
                removed = BootDeltaKind.PHOTON_REMOVED,
                changed = BootDeltaKind.PHOTON_REVISED,
                impact = BootDeltaImpact.INFO,
                label = "Photon",
                sink = deltas,
            )
            compareContexts(previous.activeContexts, next.activeContexts, deltas)
            compareChangedOnly(
                previous = previous.taskVersions,
                current = next.taskVersions,
                kind = BootDeltaKind.TASK_CHANGED,
                impact = BootDeltaImpact.WORK,
                label = "Durable task",
                sink = deltas,
            )
            compareSimpleMap(
                previous = previous.capabilityVersions,
                current = next.capabilityVersions,
                added = BootDeltaKind.CAPABILITY_ADDED,
                removed = BootDeltaKind.CAPABILITY_REMOVED,
                changed = BootDeltaKind.CAPABILITY_CHANGED,
                impact = BootDeltaImpact.CONTEXT,
                label = "Capability provider",
                sink = deltas,
            )
            compareTools(previous, next, deltas)
            compareFieldWatermarks(previous.fieldWatermarks, next.fieldWatermarks, deltas)
        }

        missed.forEach { work ->
            deltas += BootDelta(
                kind = BootDeltaKind.MISSED_DURABLE_WORK,
                impact = if (work.reason == MissedWorkReason.LEASE_EXPIRED || work.reason == MissedWorkReason.RECOVERY_PENDING) {
                    BootDeltaImpact.RECOVERY
                } else {
                    BootDeltaImpact.WORK
                },
                entityId = work.taskId,
                currentFingerprint = next.taskVersions[work.taskId],
                detail = "${work.taskType}:${work.state.name}:${work.reason.name}",
            )
        }

        val canonicalDeltas = deltas.distinct().sortedWith(bootDeltaComparator)
        return BootDeltaReport(
            previousGenerationId = previous?.generationId,
            currentGenerationId = current.generationId,
            baselineEstablished = previous == null,
            deltas = canonicalDeltas,
            missedWork = missed,
            fieldWatermarks = next.fieldWatermarks,
            nextBaseline = next,
        )
    }

    private fun compareContexts(
        previous: Map<String, String>,
        current: Map<String, String>,
        sink: MutableList<BootDelta>,
    ) {
        val keys = (previous.keys + current.keys).toSortedSet()
        keys.forEach { key ->
            val before = previous[key]
            val after = current[key]
            when {
                before == null && after != null -> sink += BootDelta(
                    kind = BootDeltaKind.CONTEXT_ACTIVATED,
                    impact = BootDeltaImpact.CONTEXT,
                    entityId = key,
                    currentFingerprint = after,
                    detail = "Durable context became active",
                )
                before != null && after == null -> sink += BootDelta(
                    kind = BootDeltaKind.CONTEXT_DEACTIVATED,
                    impact = BootDeltaImpact.CONTEXT,
                    entityId = key,
                    previousFingerprint = before,
                    detail = "Durable context is no longer active",
                )
                before != after -> sink += BootDelta(
                    kind = BootDeltaKind.CONTEXT_SOURCE_CHANGED,
                    impact = BootDeltaImpact.CONTEXT,
                    entityId = key,
                    previousFingerprint = before,
                    currentFingerprint = after,
                    detail = "Active durable context source/revision changed",
                )
            }
        }
    }

    private fun compareTools(
        previous: BootDeltaBaseline,
        current: BootDeltaBaseline,
        sink: MutableList<BootDelta>,
    ) {
        val ids = (previous.toolVersions.keys + current.toolVersions.keys).toSortedSet()
        ids.forEach { id ->
            val before = previous.toolVersions[id]
            val after = current.toolVersions[id]
            when {
                before == null && after != null -> sink += BootDelta(
                    BootDeltaKind.TOOL_ADDED,
                    BootDeltaImpact.CONTEXT,
                    id,
                    currentFingerprint = after,
                    detail = "Generated tool became available",
                )
                before != null && after == null -> sink += BootDelta(
                    BootDeltaKind.TOOL_REMOVED,
                    BootDeltaImpact.CONTEXT,
                    id,
                    previousFingerprint = before,
                    detail = "Generated tool is no longer present",
                )
                before != after -> sink += BootDelta(
                    BootDeltaKind.TOOL_VERSION_CHANGED,
                    BootDeltaImpact.CONTEXT,
                    id,
                    previousFingerprint = before,
                    currentFingerprint = after,
                    detail = "Generated tool version/state changed",
                )
            }

            val beforePermissions = previous.toolPermissions[id]
            val afterPermissions = current.toolPermissions[id]
            if (beforePermissions != null && afterPermissions != null && beforePermissions != afterPermissions) {
                sink += BootDelta(
                    BootDeltaKind.TOOL_PERMISSIONS_CHANGED,
                    BootDeltaImpact.RECOVERY,
                    id,
                    previousFingerprint = fingerprintStrings(beforePermissions.toList()),
                    currentFingerprint = fingerprintStrings(afterPermissions.toList()),
                    detail = "Generated tool permission set changed",
                )
            }
        }
    }

    private fun compareFieldWatermarks(
        previous: Map<String, FieldSnapshotWatermark>,
        current: Map<String, FieldSnapshotWatermark>,
        sink: MutableList<BootDelta>,
    ) {
        val domains = (previous.keys + current.keys).toSortedSet()
        domains.forEach { domain ->
            val before = previous[domain]
            val after = current[domain]
            if (before?.fingerprint != after?.fingerprint) {
                sink += BootDelta(
                    kind = BootDeltaKind.FIELD_WATERMARK_CHANGED,
                    impact = BootDeltaImpact.CONTEXT,
                    entityId = domain,
                    previousFingerprint = before?.fingerprint,
                    currentFingerprint = after?.fingerprint,
                    detail = when {
                        before == null -> "Field domain acquired its first durable snapshot watermark"
                        after == null -> "Field domain durable snapshot watermark disappeared"
                        else -> "Field domain durable snapshot watermark changed"
                    },
                )
            }
        }
    }

    private fun compareSimpleMap(
        previous: Map<String, String>,
        current: Map<String, String>,
        added: BootDeltaKind,
        removed: BootDeltaKind,
        changed: BootDeltaKind,
        impact: BootDeltaImpact,
        label: String,
        sink: MutableList<BootDelta>,
    ) {
        val keys = (previous.keys + current.keys).toSortedSet()
        keys.forEach { key ->
            val before = previous[key]
            val after = current[key]
            when {
                before == null && after != null -> sink += BootDelta(
                    added,
                    impact,
                    key,
                    currentFingerprint = after,
                    detail = "$label added",
                )
                before != null && after == null -> sink += BootDelta(
                    removed,
                    impact,
                    key,
                    previousFingerprint = before,
                    detail = "$label removed",
                )
                before != after -> sink += BootDelta(
                    changed,
                    impact,
                    key,
                    previousFingerprint = before,
                    currentFingerprint = after,
                    detail = "$label durable identity changed",
                )
            }
        }
    }

    private fun compareChangedOnly(
        previous: Map<String, String>,
        current: Map<String, String>,
        kind: BootDeltaKind,
        impact: BootDeltaImpact,
        label: String,
        sink: MutableList<BootDelta>,
    ) {
        (previous.keys intersect current.keys).toSortedSet().forEach { key ->
            val before = previous.getValue(key)
            val after = current.getValue(key)
            if (before != after) {
                sink += BootDelta(
                    kind,
                    impact,
                    key,
                    previousFingerprint = before,
                    currentFingerprint = after,
                    detail = "$label durable state changed",
                )
            }
        }
    }

    private fun missedWork(tasks: List<LifeTask>, at: Instant): List<MissedDurableWork> = tasks.mapNotNull { task ->
        val reason = when {
            task.state == TaskState.QUEUED && task.scheduledAt?.isAfter(at) != true -> MissedWorkReason.QUEUED_DUE
            task.state == TaskState.RETRY_WAIT && task.scheduledAt?.isAfter(at) != true -> MissedWorkReason.RETRY_DUE
            task.state == TaskState.INTERRUPTED || task.state == TaskState.RECOVERING -> MissedWorkReason.RECOVERY_PENDING
            task.state in leasedTaskStates && task.leaseExpiresAt?.isAfter(at) == false -> MissedWorkReason.LEASE_EXPIRED
            else -> null
        } ?: return@mapNotNull null

        MissedDurableWork(
            taskId = task.id.value,
            taskType = task.type.name,
            state = task.state,
            reason = reason,
            scheduledAt = task.scheduledAt,
            leaseExpiresAt = task.leaseExpiresAt,
        )
    }.sortedWith(missedWorkComparator)

    private companion object {
        val leasedTaskStates = setOf(TaskState.CLAIMED, TaskState.RUNNING, TaskState.CHECKPOINTED)
    }
}

private fun fieldWatermarks(snapshots: List<FieldSnapshot>): Map<String, FieldSnapshotWatermark> = snapshots
    .groupBy { it.domainId.value }
    .toSortedMap()
    .mapValues { (domainId, values) ->
        val ordered = values.sortedBy { it.id.value }
        FieldSnapshotWatermark(
            domainId = domainId,
            fingerprint = fingerprintStrings(
                buildList {
                    add("lifeos-field-watermark/v1")
                    add(domainId)
                    ordered.forEach { snapshot ->
                        add(snapshot.id.value)
                        add(snapshot.runId.value)
                        add(snapshot.status.name)
                        add(snapshot.inputFingerprint)
                        add(snapshot.fieldSetFingerprint)
                        add(snapshot.traceFingerprint)
                    }
                }
            ),
            snapshotIds = ordered.map { it.id.value }.distinct().sorted(),
            runIds = ordered.map { it.runId.value }.distinct().sorted(),
        )
    }

private fun photonFingerprint(photon: Photon): String = fingerprintStrings(
    buildList {
        add("photon/v1")
        add(photon.id.value)
        add(photon.revision.toString())
        add(photon.phase.name)
        add(photon.mimeType)
        add(photon.content)
        add(photon.semanticMass.toString())
        add(photon.energy.toString())
        add(photon.confidence.toString())
        add(photon.provenance.source)
        add(photon.provenance.actor)
        add(photon.provenance.createdAt.toString())
        photon.provenance.parentIds.map { it.value }.sorted().forEach { add("parent:$it") }
        photon.relations
            .sortedWith(compareBy({ it.target.value }, { it.type.name }, { it.weight }))
            .forEach { add("relation:${it.target.value}:${it.type.name}:${it.weight}") }
        photon.tags.sorted().forEach { add("tag:$it") }
    }
)

private fun contextKey(context: BootContextProjection): String = "${context.kind.name}:${context.contextId}"
private fun contextFingerprint(context: BootContextProjection): String = fingerprintStrings(
    listOf(
        context.kind.name,
        context.contextId,
        context.sourcePhotonId.value,
        context.sourcePhotonRevision.toString(),
        context.sourceCreatedAt.toString(),
        context.explicitlyActive.toString(),
    )
)

private fun taskFingerprint(task: LifeTask): String = fingerprintStrings(
    buildList {
        add(task.id.value)
        add(task.type.name)
        add(task.state.name)
        add(task.priority.name)
        add(task.idempotencyKey)
        add(task.attempt.toString())
        add(task.maxAttempts.toString())
        add(task.createdAt.toString())
        add(task.updatedAt.toString())
        add(task.scheduledAt?.toString() ?: "-")
        add(task.claimedBy?.value ?: "-")
        add(task.leaseExpiresAt?.toString() ?: "-")
        task.inputPhotonIds.map { it.value }.sorted().forEach { add("input:$it") }
        task.inputPhotonRevisions.entries.sortedBy { it.key.value }.forEach {
            add("revision:${it.key.value}:${it.value}")
        }
    }
)

private fun capabilityKey(capability: CapabilityDescriptor): String =
    "${capability.capabilityId.value}:${capability.providerId}"

private fun capabilityFingerprint(capability: CapabilityDescriptor): String = fingerprintStrings(
    buildList {
        add(capability.capabilityId.value)
        add(capability.providerId)
        add(capability.providerType.name)
        add(capability.state.name)
        add(capability.trustLevel.name)
        add(capability.reliability.toString())
        add(capability.cost.toString())
        capability.contract.requiredInputs.sorted().forEach { add("input:$it") }
        capability.contract.outputs.sorted().forEach { add("output:$it") }
    }
)

private fun toolFingerprint(tool: GeneratedToolRecord): String = fingerprintStrings(
    buildList {
        val manifest = tool.manifest
        add(manifest.toolId)
        add(tool.state.name)
        add(tool.verificationConfidence.toString())
        add(tool.lastMessage ?: "-")
        add(manifest.sourceCapability.value)
        add(manifest.sourceHash)
        add(manifest.buildHash ?: "-")
        add(manifest.generatedAt.toString())
        manifest.permissions.map { it.name }.sorted().forEach { add("permission:$it") }
        manifest.requiredInputs.sorted().forEach { add("input:$it") }
        manifest.requiredOutputs.sorted().forEach { add("output:$it") }
    }
)

private fun fingerprintStrings(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.UTF_8))
        digest.update(':'.code.toByte())
        digest.update(bytes)
        digest.update('\n'.code.toByte())
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private val bootDeltaComparator = compareBy<BootDelta>(
    { it.impact.ordinal },
    { it.kind.name },
    { it.entityId },
    { it.previousFingerprint ?: "" },
    { it.currentFingerprint ?: "" },
    { it.detail },
)

private val missedWorkComparator = compareBy<MissedDurableWork>(
    { it.reason.name },
    { it.taskId },
    { it.state.name },
    { it.scheduledAt ?: Instant.EPOCH },
    { it.leaseExpiresAt ?: Instant.EPOCH },
)
