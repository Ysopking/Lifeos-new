package app.lifeos.core.runtime.boot

import app.lifeos.core.field.FieldSnapshot
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

enum class BootContextDeltaKind { ACTIVATED, DEACTIVATED, SOURCE_ADVANCED }

data class BootContextDelta(
    val kind: BootContextKind,
    val contextId: String,
    val change: BootContextDeltaKind,
    val previousSourceRevision: Long? = null,
    val currentSourceRevision: Long? = null,
)

enum class BootPhotonDeltaKind { ADDED, REMOVED, REVISED, RELATIONS_CHANGED }

data class BootPhotonDelta(
    val photonId: PhotonId,
    val change: BootPhotonDeltaKind,
    val previousRevision: Long? = null,
    val currentRevision: Long? = null,
)

enum class MissedDurableTaskReason { OVERDUE_SCHEDULE, EXPIRED_LEASE, INTERRUPTED_RECOVERY }

data class MissedDurableTask(
    val taskId: String,
    val state: TaskState,
    val reason: MissedDurableTaskReason,
    val scheduledAt: Instant? = null,
    val leaseExpiresAt: Instant? = null,
)

data class BootFieldWatermarkDelta(
    val domainId: String,
    val previousWatermark: String?,
    val currentWatermark: String?,
)

data class BootCapabilityDelta(
    val capabilityId: String,
    val providerId: String,
    val previousFingerprint: String?,
    val currentFingerprint: String?,
)

data class BootToolDelta(
    val toolId: String,
    val previousFingerprint: String?,
    val currentFingerprint: String?,
    val addedPermissions: List<String>,
    val removedPermissions: List<String>,
)

data class BootDeltaAnalysis(
    val previousGenerationId: BootGenerationId?,
    val currentGenerationId: BootGenerationId,
    val contextChanges: List<BootContextDelta>,
    val photonChanges: List<BootPhotonDelta>,
    val missedDurableTasks: List<MissedDurableTask>,
    val fieldWatermarkChanges: List<BootFieldWatermarkDelta>,
    val capabilityChanges: List<BootCapabilityDelta>,
    val toolChanges: List<BootToolDelta>,
) {
    val hasMeaningfulDelta: Boolean
        get() = contextChanges.isNotEmpty() || photonChanges.isNotEmpty() ||
            missedDurableTasks.isNotEmpty() || fieldWatermarkChanges.isNotEmpty() ||
            capabilityChanges.isNotEmpty() || toolChanges.isNotEmpty()
}

/**
 * Compares durable boot truth without mutating it. The analyzer treats field state as a set
 * watermark because field snapshots are content-addressed and intentionally carry no wall clock.
 */
class BootDeltaAnalyzer {
    fun analyze(
        previous: DurableBootSnapshot?,
        current: DurableBootSnapshot,
    ): BootDeltaAnalysis = BootDeltaAnalysis(
        previousGenerationId = previous?.generationId,
        currentGenerationId = current.generationId,
        contextChanges = compareContexts(previous, current),
        photonChanges = comparePhotons(previous, current),
        missedDurableTasks = detectMissedTasks(current),
        fieldWatermarkChanges = compareFieldWatermarks(previous, current),
        capabilityChanges = compareCapabilities(previous, current),
        toolChanges = compareTools(previous, current),
    )

    private fun compareContexts(
        previous: DurableBootSnapshot?,
        current: DurableBootSnapshot,
    ): List<BootContextDelta> {
        val before = activeContextMap(previous)
        val after = activeContextMap(current)
        return (before.keys + after.keys).sortedWith(compareBy<Pair<BootContextKind, String>>({ it.first.name }, { it.second }))
            .mapNotNull { key ->
                val old = before[key]
                val new = after[key]
                when {
                    old == null && new != null -> BootContextDelta(
                        key.first, key.second, BootContextDeltaKind.ACTIVATED,
                        currentSourceRevision = new.sourcePhotonRevision,
                    )
                    old != null && new == null -> BootContextDelta(
                        key.first, key.second, BootContextDeltaKind.DEACTIVATED,
                        previousSourceRevision = old.sourcePhotonRevision,
                    )
                    old != null && new != null && (
                        old.sourcePhotonId != new.sourcePhotonId || old.sourcePhotonRevision != new.sourcePhotonRevision
                    ) -> BootContextDelta(
                        key.first,
                        key.second,
                        BootContextDeltaKind.SOURCE_ADVANCED,
                        previousSourceRevision = old.sourcePhotonRevision,
                        currentSourceRevision = new.sourcePhotonRevision,
                    )
                    else -> null
                }
            }
    }

    private fun comparePhotons(
        previous: DurableBootSnapshot?,
        current: DurableBootSnapshot,
    ): List<BootPhotonDelta> {
        val before = latestPhotons(previous)
        val after = latestPhotons(current)
        return (before.keys + after.keys).sortedBy { it.value }.mapNotNull { id ->
            val old = before[id]
            val new = after[id]
            when {
                old == null && new != null -> BootPhotonDelta(
                    id, BootPhotonDeltaKind.ADDED, currentRevision = new.revision,
                )
                old != null && new == null -> BootPhotonDelta(
                    id, BootPhotonDeltaKind.REMOVED, previousRevision = old.revision,
                )
                old != null && new != null && old.revision != new.revision -> BootPhotonDelta(
                    id,
                    BootPhotonDeltaKind.REVISED,
                    previousRevision = old.revision,
                    currentRevision = new.revision,
                )
                old != null && new != null && relationFingerprint(old) != relationFingerprint(new) -> BootPhotonDelta(
                    id,
                    BootPhotonDeltaKind.RELATIONS_CHANGED,
                    previousRevision = old.revision,
                    currentRevision = new.revision,
                )
                else -> null
            }
        }
    }

    private fun detectMissedTasks(current: DurableBootSnapshot): List<MissedDurableTask> = current.tasks
        .mapNotNull { task -> missedTask(task, current.capturedAt) }
        .sortedWith(compareBy({ it.reason.name }, { it.taskId }))

    private fun missedTask(task: LifeTask, capturedAt: Instant): MissedDurableTask? {
        val expiredLease = task.state in leaseOwnedStates && task.leaseExpiresAt?.let { !it.isAfter(capturedAt) } == true
        if (expiredLease) {
            return MissedDurableTask(
                taskId = task.id.value,
                state = task.state,
                reason = MissedDurableTaskReason.EXPIRED_LEASE,
                scheduledAt = task.scheduledAt,
                leaseExpiresAt = task.leaseExpiresAt,
            )
        }
        if (task.state in interruptedStates) {
            return MissedDurableTask(
                taskId = task.id.value,
                state = task.state,
                reason = MissedDurableTaskReason.INTERRUPTED_RECOVERY,
                scheduledAt = task.scheduledAt,
            )
        }
        val overdue = task.state in schedulableStates && task.scheduledAt?.let { !it.isAfter(capturedAt) } == true
        return if (overdue) {
            MissedDurableTask(
                taskId = task.id.value,
                state = task.state,
                reason = MissedDurableTaskReason.OVERDUE_SCHEDULE,
                scheduledAt = task.scheduledAt,
            )
        } else {
            null
        }
    }

    private fun compareFieldWatermarks(
        previous: DurableBootSnapshot?,
        current: DurableBootSnapshot,
    ): List<BootFieldWatermarkDelta> {
        val before = fieldWatermarks(previous?.fieldSnapshots.orEmpty())
        val after = fieldWatermarks(current.fieldSnapshots)
        return (before.keys + after.keys).sorted().mapNotNull { domain ->
            val old = before[domain]
            val new = after[domain]
            if (old == new) null else BootFieldWatermarkDelta(domain, old, new)
        }
    }

    private fun compareCapabilities(
        previous: DurableBootSnapshot?,
        current: DurableBootSnapshot,
    ): List<BootCapabilityDelta> {
        val before = previous?.capabilities.orEmpty().associateBy(::capabilityKey)
        val after = current.capabilities.associateBy(::capabilityKey)
        return (before.keys + after.keys).sorted().mapNotNull { key ->
            val old = before[key]
            val new = after[key]
            val oldFingerprint = old?.let(::capabilityFingerprint)
            val newFingerprint = new?.let(::capabilityFingerprint)
            if (oldFingerprint == newFingerprint) null else BootCapabilityDelta(
                capabilityId = new?.capabilityId?.value ?: old!!.capabilityId.value,
                providerId = new?.providerId ?: old!!.providerId,
                previousFingerprint = oldFingerprint,
                currentFingerprint = newFingerprint,
            )
        }
    }

    private fun compareTools(
        previous: DurableBootSnapshot?,
        current: DurableBootSnapshot,
    ): List<BootToolDelta> {
        val before = previous?.tools.orEmpty().associateBy { it.manifest.toolId }
        val after = current.tools.associateBy { it.manifest.toolId }
        return (before.keys + after.keys).sorted().mapNotNull { toolId ->
            val old = before[toolId]
            val new = after[toolId]
            val oldFingerprint = old?.let(::toolFingerprint)
            val newFingerprint = new?.let(::toolFingerprint)
            if (oldFingerprint == newFingerprint) return@mapNotNull null
            val oldPermissions = old?.manifest?.permissions.orEmpty().map { it.name }.toSet()
            val newPermissions = new?.manifest?.permissions.orEmpty().map { it.name }.toSet()
            BootToolDelta(
                toolId = toolId,
                previousFingerprint = oldFingerprint,
                currentFingerprint = newFingerprint,
                addedPermissions = (newPermissions - oldPermissions).sorted(),
                removedPermissions = (oldPermissions - newPermissions).sorted(),
            )
        }
    }
}

private fun activeContextMap(snapshot: DurableBootSnapshot?): Map<Pair<BootContextKind, String>, BootContextProjection> =
    snapshot?.contexts.orEmpty().filter { it.explicitlyActive }
        .groupBy { it.kind to it.contextId }
        .mapValues { (_, values) ->
            values.maxWithOrNull(
                compareBy<BootContextProjection>(
                    { it.sourceCreatedAt }, { it.sourcePhotonRevision }, { it.sourcePhotonId.value },
                )
            )!!
        }

private fun latestPhotons(snapshot: DurableBootSnapshot?): Map<PhotonId, Photon> = snapshot?.photons.orEmpty()
    .groupBy { it.id }
    .mapValues { (_, revisions) -> revisions.maxWithOrNull(compareBy<Photon>({ it.revision }, { it.provenance.createdAt }))!! }

private fun relationFingerprint(photon: Photon): String = stableHash(buildList {
    add("photon-relations/v1")
    photon.provenance.parentIds.map { it.value }.sorted().forEach { add("parent:$it") }
    photon.relations.sortedWith(compareBy({ it.target.value }, { it.type.name }, { it.weight })).forEach {
        add("relation:${it.target.value}:${it.type.name}:${java.lang.Double.toHexString(it.weight)}")
    }
})

private fun fieldWatermarks(snapshots: List<FieldSnapshot>): Map<String, String> = snapshots
    .groupBy { it.domainId.value }
    .mapValues { (_, values) ->
        stableHash(buildList {
            add("field-watermark/v1")
            values.sortedBy { it.id.value }.forEach {
                add(it.id.value)
                add(it.runId.value)
                add(it.inputFingerprint)
                add(it.fieldSetFingerprint)
                add(it.traceFingerprint)
            }
        })
    }

private fun capabilityKey(descriptor: CapabilityDescriptor): String =
    "${descriptor.capabilityId.value}\u0000${descriptor.providerId}"

private fun capabilityFingerprint(descriptor: CapabilityDescriptor): String = stableHash(buildList {
    add("capability-state/v1")
    add(descriptor.capabilityId.value)
    add(descriptor.providerId)
    add(descriptor.providerType.name)
    add(descriptor.state.name)
    add(descriptor.trustLevel.name)
    add(java.lang.Double.toHexString(descriptor.reliability))
    add(java.lang.Double.toHexString(descriptor.cost))
    descriptor.contract.requiredInputs.sorted().forEach { add("in:$it") }
    descriptor.contract.outputs.sorted().forEach { add("out:$it") }
})

private fun toolFingerprint(record: GeneratedToolRecord): String = stableHash(buildList {
    val manifest = record.manifest
    add("generated-tool-state/v1")
    add(manifest.toolId)
    add(record.state.name)
    add(manifest.sourceCapability.value)
    add(manifest.sourceHash)
    add(manifest.buildHash ?: "-")
    add(java.lang.Double.toHexString(record.verificationConfidence))
    manifest.permissions.map { it.name }.sorted().forEach { add("permission:$it") }
    manifest.requiredInputs.sorted().forEach { add("in:$it") }
    manifest.requiredOutputs.sorted().forEach { add("out:$it") }
})

private fun stableHash(parts: List<String>): String {
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

private val leaseOwnedStates = setOf(TaskState.CLAIMED, TaskState.RUNNING, TaskState.CHECKPOINTED)
private val interruptedStates = setOf(TaskState.INTERRUPTED, TaskState.RECOVERING)
private val schedulableStates = setOf(TaskState.QUEUED, TaskState.RETRY_WAIT)
