package app.lifeos.core.runtime.boot

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.HypothesisState
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.capability.GeneratedToolState
import java.time.Instant

enum class BootIntegritySeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL,
}

enum class BootIntegrityArea {
    SOURCE,
    PHOTON,
    TASK,
    CHECKPOINT,
    CONTEXT,
    WORKER,
    CAPABILITY,
    TOOL,
    FIELD,
}

enum class BootRepairability {
    NONE,
    RELOAD_SOURCE,
    REBUILD_DERIVED_PROJECTION,
    RECOVERY_REQUIRED,
    USER_REVIEW,
}

data class BootIntegrityFinding(
    val code: String,
    val severity: BootIntegritySeverity,
    val area: BootIntegrityArea,
    val entityId: String? = null,
    val message: String,
    val repairability: BootRepairability,
) {
    init {
        require(code.matches(Regex("[A-Z0-9_]{3,96}"))) { "Integrity finding code is invalid" }
        require(entityId == null || entityId.isNotBlank()) { "Integrity entity id must not be blank" }
        require(message.isNotBlank()) { "Integrity finding message must not be blank" }
        require(message.length <= 512) { "Integrity finding message is too long" }
    }
}

data class BootIntegrityReport(
    val generationId: BootGenerationId,
    val scannedAt: Instant,
    val findings: List<BootIntegrityFinding>,
) {
    init {
        require(findings == findings.sortedWith(bootIntegrityFindingComparator)) {
            "Boot integrity findings must be canonical"
        }
    }

    val blockingFindings: List<BootIntegrityFinding>
        get() = findings.filter { it.severity >= BootIntegritySeverity.ERROR }

    val canProceedNormally: Boolean
        get() = blockingFindings.isEmpty()

    val requiresRecovery: Boolean
        get() = findings.any {
            it.severity >= BootIntegritySeverity.ERROR &&
                it.repairability == BootRepairability.RECOVERY_REQUIRED
        }
}

/**
 * Read-only cross-store integrity scanner for a D01 durable boot snapshot.
 *
 * The scanner reports evidence only. It never mutates a Photon, task, checkpoint, registry,
 * field snapshot, or backing vault and therefore cannot silently "repair" corrupt source state.
 */
class BootIntegrityScanner(
    private val now: () -> Instant = Instant::now,
) {
    fun scan(snapshot: DurableBootSnapshot): BootIntegrityReport {
        val findings = buildList {
            scanSourceFailures(snapshot)
            scanPhotons(snapshot)
            scanTasksAndCheckpoints(snapshot)
            scanContextProjections(snapshot)
            scanWorkerLeases(snapshot)
            scanCapabilitiesAndTools(snapshot)
            scanFieldSnapshots(snapshot)
        }.distinct().sortedWith(bootIntegrityFindingComparator)

        return BootIntegrityReport(
            generationId = snapshot.generationId,
            scannedAt = now(),
            findings = findings,
        )
    }

    private fun MutableList<BootIntegrityFinding>.scanSourceFailures(snapshot: DurableBootSnapshot) {
        snapshot.readFailures.forEach { failure ->
            val durableSource = failure.source in setOf(
                BootSnapshotSource.PHOTON,
                BootSnapshotSource.TASK,
                BootSnapshotSource.CHECKPOINT,
                BootSnapshotSource.FIELD,
            )
            val unreadableEntry = failure.reason == "unreadable-entry"
            add(
                BootIntegrityFinding(
                    code = if (unreadableEntry) "SOURCE_ENTRY_UNREADABLE" else "SOURCE_READ_FAILED",
                    severity = if (durableSource) BootIntegritySeverity.ERROR else BootIntegritySeverity.WARNING,
                    area = BootIntegrityArea.SOURCE,
                    entityId = failure.entry ?: failure.source.name.lowercase(),
                    message = "${failure.source.name} boot source: ${failure.reason}",
                    repairability = if (unreadableEntry || durableSource) {
                        BootRepairability.RECOVERY_REQUIRED
                    } else {
                        BootRepairability.RELOAD_SOURCE
                    },
                )
            )
        }
    }

    private fun MutableList<BootIntegrityFinding>.scanPhotons(snapshot: DurableBootSnapshot) {
        val grouped = snapshot.photons.groupBy { it.id }
        grouped.filterValues { it.size > 1 }.forEach { (id, records) ->
            add(
                BootIntegrityFinding(
                    code = "DUPLICATE_PHOTON_ID",
                    severity = BootIntegritySeverity.ERROR,
                    area = BootIntegrityArea.PHOTON,
                    entityId = id.value,
                    message = "${records.size} readable Photon records share one durable id",
                    repairability = BootRepairability.RECOVERY_REQUIRED,
                )
            )
        }

        val knownIds = grouped.keys
        snapshot.photons.forEach { photon ->
            photon.relations
                .filter { it.target !in knownIds }
                .sortedBy { it.target.value }
                .forEach { relation ->
                    add(
                        BootIntegrityFinding(
                            code = "ORPHAN_PHOTON_RELATION",
                            severity = BootIntegritySeverity.WARNING,
                            area = BootIntegrityArea.PHOTON,
                            entityId = photon.id.value,
                            message = "Relation ${relation.type} targets missing Photon ${relation.target.value}",
                            repairability = BootRepairability.USER_REVIEW,
                        )
                    )
                }
        }
    }

    private fun MutableList<BootIntegrityFinding>.scanTasksAndCheckpoints(snapshot: DurableBootSnapshot) {
        val photonsById = snapshot.photons.groupBy { it.id }
        val tasksById = snapshot.tasks.associateBy { it.id }
        val checkpointsByTask = snapshot.checkpoints.groupBy { it.taskId }

        snapshot.tasks.forEach { task ->
            task.inputPhotonIds.sortedBy { it.value }.forEach { photonId ->
                val records = photonsById[photonId]
                if (records.isNullOrEmpty()) {
                    add(
                        BootIntegrityFinding(
                            code = "TASK_INPUT_PHOTON_MISSING",
                            severity = BootIntegritySeverity.ERROR,
                            area = BootIntegrityArea.TASK,
                            entityId = task.id.value,
                            message = "Task references missing input Photon ${photonId.value}",
                            repairability = BootRepairability.RECOVERY_REQUIRED,
                        )
                    )
                    return@forEach
                }

                task.inputPhotonRevisions[photonId]?.let { expectedRevision ->
                    if (records.none { it.revision == expectedRevision }) {
                        add(
                            BootIntegrityFinding(
                                code = "TASK_INPUT_REVISION_STALE",
                                severity = BootIntegritySeverity.ERROR,
                                area = BootIntegrityArea.TASK,
                                entityId = task.id.value,
                                message = "Pinned Photon ${photonId.value} revision $expectedRevision is unavailable",
                                repairability = BootRepairability.RECOVERY_REQUIRED,
                            )
                        )
                    }
                }
            }

            if (task.state == TaskState.CHECKPOINTED && checkpointsByTask[task.id].isNullOrEmpty()) {
                add(
                    BootIntegrityFinding(
                        code = "CHECKPOINTED_TASK_WITHOUT_CHECKPOINT",
                        severity = BootIntegritySeverity.ERROR,
                        area = BootIntegrityArea.CHECKPOINT,
                        entityId = task.id.value,
                        message = "Task is CHECKPOINTED but no readable checkpoint exists",
                        repairability = BootRepairability.RECOVERY_REQUIRED,
                    )
                )
            }
        }

        snapshot.checkpoints.forEach { checkpoint ->
            val task = tasksById[checkpoint.taskId]
            if (task == null) {
                add(
                    BootIntegrityFinding(
                        code = "ORPHAN_CHECKPOINT",
                        severity = BootIntegritySeverity.ERROR,
                        area = BootIntegrityArea.CHECKPOINT,
                        entityId = checkpoint.id.value,
                        message = "Checkpoint references missing task ${checkpoint.taskId.value}",
                        repairability = BootRepairability.RECOVERY_REQUIRED,
                    )
                )
            } else if (checkpoint.createdAt.isBefore(task.createdAt)) {
                add(
                    BootIntegrityFinding(
                        code = "CHECKPOINT_PREDATES_TASK",
                        severity = BootIntegritySeverity.ERROR,
                        area = BootIntegrityArea.CHECKPOINT,
                        entityId = checkpoint.id.value,
                        message = "Checkpoint timestamp predates its task",
                        repairability = BootRepairability.RECOVERY_REQUIRED,
                    )
                )
            }
        }

        checkpointsByTask.forEach { (taskId, taskCheckpoints) ->
            taskCheckpoints.groupBy { it.sequence }
                .filterValues { it.size > 1 }
                .forEach { (sequence, duplicates) ->
                    add(
                        BootIntegrityFinding(
                            code = "DUPLICATE_CHECKPOINT_SEQUENCE",
                            severity = BootIntegritySeverity.ERROR,
                            area = BootIntegrityArea.CHECKPOINT,
                            entityId = taskId.value,
                            message = "${duplicates.size} checkpoints share sequence $sequence",
                            repairability = BootRepairability.RECOVERY_REQUIRED,
                        )
                    )
                }

            val ordered = taskCheckpoints.sortedWith(compareBy({ it.sequence }, { it.createdAt }, { it.id.value }))
            ordered.zipWithNext().forEach { (previous, next) ->
                if (next.runtimeGeneration < previous.runtimeGeneration) {
                    add(
                        BootIntegrityFinding(
                            code = "CHECKPOINT_GENERATION_REGRESSION",
                            severity = BootIntegritySeverity.ERROR,
                            area = BootIntegrityArea.CHECKPOINT,
                            entityId = next.id.value,
                            message = "Runtime generation regressed from ${previous.runtimeGeneration} to ${next.runtimeGeneration}",
                            repairability = BootRepairability.RECOVERY_REQUIRED,
                        )
                    )
                }
                if (next.createdAt.isBefore(previous.createdAt)) {
                    add(
                        BootIntegrityFinding(
                            code = "CHECKPOINT_TIME_REGRESSION",
                            severity = BootIntegritySeverity.WARNING,
                            area = BootIntegrityArea.CHECKPOINT,
                            entityId = next.id.value,
                            message = "Checkpoint creation time regressed across increasing sequence",
                            repairability = BootRepairability.USER_REVIEW,
                        )
                    )
                }
            }
        }
    }

    private fun MutableList<BootIntegrityFinding>.scanContextProjections(snapshot: DurableBootSnapshot) {
        val photonsById = snapshot.photons.groupBy { it.id }
        snapshot.contexts.forEach { context ->
            val source = photonsById[context.sourcePhotonId]
            if (source.isNullOrEmpty()) {
                add(
                    BootIntegrityFinding(
                        code = "CONTEXT_SOURCE_MISSING",
                        severity = BootIntegritySeverity.ERROR,
                        area = BootIntegrityArea.CONTEXT,
                        entityId = "${context.kind.name}:${context.contextId}",
                        message = "Context projection source Photon is missing",
                        repairability = BootRepairability.REBUILD_DERIVED_PROJECTION,
                    )
                )
            } else if (source.none { it.revision == context.sourcePhotonRevision }) {
                add(
                    BootIntegrityFinding(
                        code = "CONTEXT_SOURCE_REVISION_STALE",
                        severity = BootIntegritySeverity.ERROR,
                        area = BootIntegrityArea.CONTEXT,
                        entityId = "${context.kind.name}:${context.contextId}",
                        message = "Context projection points to unavailable source revision ${context.sourcePhotonRevision}",
                        repairability = BootRepairability.REBUILD_DERIVED_PROJECTION,
                    )
                )
            }
        }

        snapshot.contexts
            .filter { it.explicitlyActive }
            .groupBy { it.kind to it.contextId }
            .filterValues { it.map { context -> context.sourcePhotonId }.distinct().size > 1 }
            .forEach { (key, contexts) ->
                add(
                    BootIntegrityFinding(
                        code = "DUPLICATE_ACTIVE_CONTEXT_PROJECTION",
                        severity = BootIntegritySeverity.WARNING,
                        area = BootIntegrityArea.CONTEXT,
                        entityId = "${key.first.name}:${key.second}",
                        message = "${contexts.size} durable Photon sources assert the same active context",
                        repairability = BootRepairability.USER_REVIEW,
                    )
                )
            }
    }

    private fun MutableList<BootIntegrityFinding>.scanWorkerLeases(snapshot: DurableBootSnapshot) {
        val tasksById = snapshot.tasks.associateBy { it.id.value }
        val expectedLeases = snapshot.tasks
            .filter { it.claimedBy != null && it.leaseExpiresAt != null }
            .associateBy { it.id.value }

        snapshot.workerLeases.forEach { lease ->
            val task = tasksById[lease.taskId]
            if (
                task == null ||
                task.claimedBy != lease.workerId ||
                task.leaseExpiresAt != lease.leaseExpiresAt ||
                task.state != lease.taskState
            ) {
                add(
                    BootIntegrityFinding(
                        code = "WORKER_LEASE_PROJECTION_MISMATCH",
                        severity = BootIntegritySeverity.ERROR,
                        area = BootIntegrityArea.WORKER,
                        entityId = lease.taskId,
                        message = "Worker lease projection does not match durable task ownership",
                        repairability = BootRepairability.REBUILD_DERIVED_PROJECTION,
                    )
                )
            }

            if (!lease.leaseExpiresAt.isAfter(snapshot.capturedAt)) {
                add(
                    BootIntegrityFinding(
                        code = "STALE_WORKER_LEASE",
                        severity = BootIntegritySeverity.WARNING,
                        area = BootIntegrityArea.WORKER,
                        entityId = lease.taskId,
                        message = "Worker lease expired before or at boot snapshot capture",
                        repairability = BootRepairability.RECOVERY_REQUIRED,
                    )
                )
            }
        }

        val projectedTaskIds = snapshot.workerLeases.mapTo(mutableSetOf()) { it.taskId }
        expectedLeases.keys.filter { it !in projectedTaskIds }.sorted().forEach { taskId ->
            add(
                BootIntegrityFinding(
                    code = "WORKER_LEASE_PROJECTION_MISSING",
                    severity = BootIntegritySeverity.ERROR,
                    area = BootIntegrityArea.WORKER,
                    entityId = taskId,
                    message = "Owned durable task has no worker lease projection",
                    repairability = BootRepairability.REBUILD_DERIVED_PROJECTION,
                )
            )
        }
    }

    private fun MutableList<BootIntegrityFinding>.scanCapabilitiesAndTools(snapshot: DurableBootSnapshot) {
        snapshot.capabilities
            .groupBy { it.capabilityId.value to it.providerId }
            .filterValues { it.size > 1 }
            .forEach { (key, duplicates) ->
                add(
                    BootIntegrityFinding(
                        code = "DUPLICATE_CAPABILITY_PROVIDER",
                        severity = BootIntegritySeverity.ERROR,
                        area = BootIntegrityArea.CAPABILITY,
                        entityId = "${key.first}:${key.second}",
                        message = "${duplicates.size} capability records share one provider identity",
                        repairability = BootRepairability.RELOAD_SOURCE,
                    )
                )
            }

        val capabilityIds = snapshot.capabilities.mapTo(mutableSetOf()) { it.capabilityId }
        snapshot.tools
            .groupBy { it.manifest.toolId }
            .filterValues { it.size > 1 }
            .forEach { (toolId, duplicates) ->
                add(
                    BootIntegrityFinding(
                        code = "DUPLICATE_TOOL_ID",
                        severity = BootIntegritySeverity.ERROR,
                        area = BootIntegrityArea.TOOL,
                        entityId = toolId,
                        message = "${duplicates.size} generated-tool records share one id",
                        repairability = BootRepairability.RELOAD_SOURCE,
                    )
                )
            }

        snapshot.tools.forEach { tool ->
            val toolId = tool.manifest.toolId
            if (tool.manifest.sourceCapability !in capabilityIds) {
                add(
                    BootIntegrityFinding(
                        code = "TOOL_SOURCE_CAPABILITY_MISSING",
                        severity = if (tool.state == GeneratedToolState.ACTIVE) {
                            BootIntegritySeverity.ERROR
                        } else {
                            BootIntegritySeverity.WARNING
                        },
                        area = BootIntegrityArea.TOOL,
                        entityId = toolId,
                        message = "Generated tool source capability ${tool.manifest.sourceCapability.value} is unavailable",
                        repairability = BootRepairability.USER_REVIEW,
                    )
                )
            }

            if (tool.state in statesRequiringBuildHash && tool.manifest.buildHash.isNullOrBlank()) {
                add(
                    BootIntegrityFinding(
                        code = "TOOL_BUILD_HASH_MISSING",
                        severity = if (tool.state in activeLikeToolStates) {
                            BootIntegritySeverity.ERROR
                        } else {
                            BootIntegritySeverity.WARNING
                        },
                        area = BootIntegrityArea.TOOL,
                        entityId = toolId,
                        message = "Generated tool state ${tool.state} has no verified build hash",
                        repairability = BootRepairability.RECOVERY_REQUIRED,
                    )
                )
            }
        }
    }

    private fun MutableList<BootIntegrityFinding>.scanFieldSnapshots(snapshot: DurableBootSnapshot) {
        snapshot.fieldSnapshots
            .groupBy { it.id }
            .filterValues { it.size > 1 }
            .forEach { (id, duplicates) ->
                add(
                    BootIntegrityFinding(
                        code = "DUPLICATE_FIELD_SNAPSHOT_ID",
                        severity = BootIntegritySeverity.ERROR,
                        area = BootIntegrityArea.FIELD,
                        entityId = id.value,
                        message = "${duplicates.size} field records share one content-addressed snapshot id",
                        repairability = BootRepairability.RECOVERY_REQUIRED,
                    )
                )
            }

        snapshot.fieldSnapshots
            .groupBy { it.domainId to it.runId }
            .filterValues { values -> values.map { it.id }.distinct().size > 1 }
            .forEach { (key, values) ->
                add(
                    BootIntegrityFinding(
                        code = "FIELD_RUN_SNAPSHOT_CONFLICT",
                        severity = BootIntegritySeverity.ERROR,
                        area = BootIntegrityArea.FIELD,
                        entityId = "${key.first.value}:${key.second.value}",
                        message = "One deterministic field run resolves to ${values.size} different snapshots",
                        repairability = BootRepairability.RECOVERY_REQUIRED,
                    )
                )
            }

        snapshot.fieldSnapshots.forEach { field ->
            val convergedHypotheses = field.hypotheses.count { it.state == HypothesisState.CONVERGED }
            when (field.status) {
                ConvergenceStatus.CONVERGED -> if (convergedHypotheses != 1) {
                    add(
                        BootIntegrityFinding(
                            code = "FIELD_CONVERGENCE_STATE_MISMATCH",
                            severity = BootIntegritySeverity.ERROR,
                            area = BootIntegrityArea.FIELD,
                            entityId = field.id.value,
                            message = "CONVERGED field snapshot must contain exactly one converged hypothesis",
                            repairability = BootRepairability.RECOVERY_REQUIRED,
                        )
                    )
                }

                ConvergenceStatus.UNRESOLVED,
                ConvergenceStatus.MAX_ITERATIONS -> if (convergedHypotheses != 0) {
                    add(
                        BootIntegrityFinding(
                            code = "FIELD_UNRESOLVED_STATE_MISMATCH",
                            severity = BootIntegritySeverity.ERROR,
                            area = BootIntegrityArea.FIELD,
                            entityId = field.id.value,
                            message = "Non-converged field snapshot cannot contain a converged hypothesis",
                            repairability = BootRepairability.RECOVERY_REQUIRED,
                        )
                    )
                }
            }
        }
    }

    private companion object {
        val statesRequiringBuildHash = setOf(
            GeneratedToolState.BUILT,
            GeneratedToolState.TESTED,
            GeneratedToolState.VERIFIED,
            GeneratedToolState.TRIAL,
            GeneratedToolState.ACTIVE,
            GeneratedToolState.QUARANTINED,
            GeneratedToolState.RETIRED,
        )
        val activeLikeToolStates = setOf(
            GeneratedToolState.VERIFIED,
            GeneratedToolState.TRIAL,
            GeneratedToolState.ACTIVE,
        )
    }
}

private val bootIntegrityFindingComparator = compareBy<BootIntegrityFinding>(
    { it.severity.ordinal },
    { it.area.name },
    { it.code },
    { it.entityId ?: "" },
    { it.message },
    { it.repairability.name },
)
