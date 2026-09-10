package app.lifeos.core.runtime.capability

import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.buildstudio.CandidateArtifact
import app.lifeos.core.runtime.buildstudio.SourcePatchOperationType
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class GeneratedToolHealthSeverity {
    INFO,
    DEGRADED,
    UNHEALTHY,
    QUARANTINED,
}

data class GeneratedToolHealthIncident(
    val sourceNodeId: String,
    val severity: GeneratedToolHealthSeverity,
    val messageFingerprint: String,
    val occurredAt: Instant,
    val resolved: Boolean = false,
    val resolutionEvidenceRef: String? = null,
) {
    init {
        require(sourceNodeId.isNotBlank()) { "Health incident requires source node" }
        require(messageFingerprint.isNotBlank()) { "Health incident requires message fingerprint" }
        if (resolved) {
            require(!resolutionEvidenceRef.isNullOrBlank()) {
                "Resolved health incident requires resolution evidence"
            }
        } else {
            require(resolutionEvidenceRef == null) {
                "Unresolved health incident cannot carry resolution evidence"
            }
        }
    }

    val incidentKey: String = StableFieldIds.fingerprint(
        "generated-tool-health-incident-key/v1",
        sourceNodeId,
        severity.name,
        messageFingerprint,
        occurredAt.toString(),
    )

    val id: String = StableFieldIds.fingerprint(
        "generated-tool-health-incident/v1",
        incidentKey,
        resolved.toString(),
        resolutionEvidenceRef.orEmpty(),
    )
}

data class GeneratedToolRollbackEvidence(
    val sourceCandidateId: String,
    val reasonFingerprint: String,
    val occurredAt: Instant,
) {
    init {
        require(sourceCandidateId.isNotBlank()) { "Rollback evidence requires candidate id" }
        require(reasonFingerprint.isNotBlank()) { "Rollback evidence requires reason fingerprint" }
    }

    val id: String = StableFieldIds.fingerprint(
        "generated-tool-rollback-evidence/v1",
        sourceCandidateId,
        reasonFingerprint,
        occurredAt.toString(),
    )
}

data class GeneratedToolBuildEvidence(
    val candidateArtifactId: String,
    val buildProvenanceId: String,
    val debugApkSha256: String,
) {
    init {
        require(candidateArtifactId.isNotBlank())
        require(buildProvenanceId.isNotBlank())
        require(debugApkSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Promotion build evidence requires lowercase APK SHA-256"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "generated-tool-build-evidence/v1",
        candidateArtifactId,
        buildProvenanceId,
        debugApkSha256,
    )
}

data class GeneratedToolPromotionEvidenceSnapshot(
    val toolId: String,
    val fieldSnapshotIds: Set<FieldSnapshotId>,
    val buildEvidence: GeneratedToolBuildEvidence?,
    val trialOutcomes: Map<String, String>,
    val healthIncidents: List<GeneratedToolHealthIncident>,
    val rollbacks: List<GeneratedToolRollbackEvidence>,
) {
    init {
        require(toolId.isNotBlank())
        require(trialOutcomes.keys.none { it.isBlank() })
        require(trialOutcomes.values.none { it.isBlank() })
        require(healthIncidents.map { it.id }.distinct().size == healthIncidents.size)
        require(rollbacks.map { it.id }.distinct().size == rollbacks.size)
    }

    val rollbackCount: Int get() = rollbacks.size

    val id: String = StableFieldIds.fingerprint(
        "generated-tool-promotion-evidence-snapshot/v1",
        toolId,
        buildEvidence?.fingerprint().orEmpty(),
        *fieldSnapshotIds.sortedBy { it.value }.map { "field:${it.value}" }.toTypedArray(),
        *trialOutcomes.toSortedMap().map { (invocationId, fingerprint) ->
            "trial:$invocationId:$fingerprint"
        }.toTypedArray(),
        *healthIncidents.sortedBy { it.id }.map { "health:${it.id}" }.toTypedArray(),
        *rollbacks.sortedBy { it.id }.map { "rollback:${it.id}" }.toTypedArray(),
    )

    /** Evidence never grants activation authority by itself. */
    val activationAllowed: Boolean = false
}

class GeneratedToolPromotionEvidenceLedger {
    private data class MutableEvidence(
        var fieldSnapshotIds: Set<FieldSnapshotId>? = null,
        var buildEvidence: GeneratedToolBuildEvidence? = null,
        val trialOutcomes: LinkedHashMap<String, String> = linkedMapOf(),
        val healthIncidents: LinkedHashMap<String, GeneratedToolHealthIncident> = linkedMapOf(),
        val rollbacks: LinkedHashMap<String, GeneratedToolRollbackEvidence> = linkedMapOf(),
        var frozenSnapshotId: String? = null,
    )

    private val mutex = Mutex()
    private val evidence = mutableMapOf<String, MutableEvidence>()

    suspend fun recordDesignFieldSnapshots(
        toolId: String,
        snapshotIds: Set<FieldSnapshotId>,
    ): Boolean = mutex.withLock {
        require(toolId.isNotBlank())
        require(snapshotIds.isNotEmpty()) { "Design evidence requires at least one field snapshot" }
        val state = evidence.getOrPut(toolId) { MutableEvidence() }
        requireMutable(toolId, state)
        val existing = state.fieldSnapshotIds
        if (existing != null) {
            require(existing == snapshotIds) { "Conflicting field snapshot evidence for $toolId" }
            return@withLock false
        }
        state.fieldSnapshotIds = snapshotIds.toSet()
        true
    }

    suspend fun bindBuildArtifact(
        toolId: String,
        record: GeneratedToolRecord,
        artifact: CandidateArtifact,
    ): Boolean = mutex.withLock {
        require(toolId == record.manifest.toolId) { "Build evidence tool id mismatch" }
        require(!artifact.activationAllowed)
        val requirement = artifact.provenance.sourceRequirement
        require(requirement.capabilityId == record.manifest.sourceCapability) {
            "Build artifact capability does not match generated tool"
        }
        require(requirement.requiredInputs == record.manifest.requiredInputs) {
            "Build artifact input contract does not match generated tool"
        }
        require(requirement.requiredOutputs == record.manifest.requiredOutputs) {
            "Build artifact output contract does not match generated tool"
        }
        require(artifact.provenance.permissionDelta.removed.isEmpty()) {
            "Generated tool build provenance cannot remove permissions"
        }
        require(artifact.provenance.permissionDelta.added == record.manifest.permissions) {
            "Build artifact permission delta does not match generated tool manifest"
        }
        val sourceContentFingerprint = requireNotNull(record.manifest.sourceContentFingerprint) {
            "Generated tool requires exact source content fingerprint before build provenance binding"
        }
        require(
            artifact.provenance.files.any { file ->
                file.operation != SourcePatchOperationType.DELETE &&
                    file.contentFingerprint == sourceContentFingerprint
            }
        ) {
            "Build artifact does not contain exact generated source content"
        }

        val bound = GeneratedToolBuildEvidence(
            candidateArtifactId = artifact.id,
            buildProvenanceId = artifact.provenance.id,
            debugApkSha256 = artifact.debugApkSha256,
        )
        val state = evidence.getOrPut(toolId) { MutableEvidence() }
        requireMutable(toolId, state)
        val existing = state.buildEvidence
        if (existing != null) {
            require(existing == bound) { "Conflicting build evidence for $toolId" }
            return@withLock false
        }
        state.buildEvidence = bound
        true
    }

    suspend fun recordTrial(toolId: String, result: GeneratedToolTrialResult): Boolean = mutex.withLock {
        require(toolId.isNotBlank())
        val state = evidence.getOrPut(toolId) { MutableEvidence() }
        requireMutable(toolId, state)
        val fingerprint = result.promotionEvidenceFingerprint()
        val existing = state.trialOutcomes[result.invocationId]
        if (existing != null) {
            require(existing == fingerprint) { "Conflicting trial evidence for ${result.invocationId}" }
            return@withLock false
        }
        state.trialOutcomes[result.invocationId] = fingerprint
        true
    }

    suspend fun recordHealthIncident(
        toolId: String,
        incident: GeneratedToolHealthIncident,
    ): Boolean = mutex.withLock {
        require(toolId.isNotBlank())
        val state = evidence.getOrPut(toolId) { MutableEvidence() }
        requireMutable(toolId, state)
        state.healthIncidents.putIfAbsent(incident.id, incident) == null
    }

    suspend fun resolveHealthIncident(
        toolId: String,
        incident: GeneratedToolHealthIncident,
        resolutionEvidenceRef: String,
    ): Boolean {
        require(!incident.resolved) { "Only unresolved incidents can be resolved" }
        require(resolutionEvidenceRef.isNotBlank())
        return recordHealthIncident(
            toolId,
            incident.copy(resolved = true, resolutionEvidenceRef = resolutionEvidenceRef),
        )
    }

    suspend fun recordRollback(
        toolId: String,
        rollback: GeneratedToolRollbackEvidence,
    ): Boolean = mutex.withLock {
        require(toolId.isNotBlank())
        val state = evidence.getOrPut(toolId) { MutableEvidence() }
        requireMutable(toolId, state)
        state.rollbacks.putIfAbsent(rollback.id, rollback) == null
    }

    suspend fun snapshot(toolId: String): GeneratedToolPromotionEvidenceSnapshot = mutex.withLock {
        require(toolId.isNotBlank())
        snapshotUnsafe(toolId, evidence[toolId])
    }

    suspend fun freezeForPromotion(
        toolId: String,
        expectedSnapshotId: String,
    ): GeneratedToolPromotionEvidenceSnapshot = mutex.withLock {
        require(toolId.isNotBlank())
        require(expectedSnapshotId.isNotBlank())
        val state = evidence.getOrPut(toolId) { MutableEvidence() }
        val existingFreeze = state.frozenSnapshotId
        if (existingFreeze != null) {
            require(existingFreeze == expectedSnapshotId) { "Promotion evidence already frozen differently" }
        }
        val snapshot = snapshotUnsafe(toolId, state)
        require(snapshot.id == expectedSnapshotId) {
            "Promotion evidence changed after eligibility evaluation"
        }
        state.frozenSnapshotId = expectedSnapshotId
        snapshot
    }

    private fun requireMutable(toolId: String, state: MutableEvidence) {
        require(state.frozenSnapshotId == null) {
            "Promotion evidence for $toolId is frozen"
        }
    }

    private fun snapshotUnsafe(
        toolId: String,
        state: MutableEvidence?,
    ) = GeneratedToolPromotionEvidenceSnapshot(
        toolId = toolId,
        fieldSnapshotIds = state?.fieldSnapshotIds.orEmpty(),
        buildEvidence = state?.buildEvidence,
        trialOutcomes = state?.trialOutcomes?.toMap().orEmpty(),
        healthIncidents = state?.healthIncidents?.values?.sortedBy { it.id }.orEmpty(),
        rollbacks = state?.rollbacks?.values?.sortedBy { it.id }.orEmpty(),
    )
}

data class GeneratedToolPromotionEvidencePolicy(
    val minimumFieldSnapshots: Int = 1,
    val maxRollbackCount: Int = 0,
    val blockingHealthSeverity: GeneratedToolHealthSeverity = GeneratedToolHealthSeverity.DEGRADED,
) {
    init {
        require(minimumFieldSnapshots > 0)
        require(maxRollbackCount >= 0)
    }

    fun reasons(
        stats: GeneratedToolTrialStats,
        evidence: GeneratedToolPromotionEvidenceSnapshot,
    ): List<String> = buildList {
        if (evidence.buildEvidence == null) add("missing-build-provenance")
        if (evidence.fieldSnapshotIds.size < minimumFieldSnapshots) {
            add("insufficient-field-snapshots:${evidence.fieldSnapshotIds.size}<$minimumFieldSnapshots")
        }
        if (evidence.trialOutcomes.size != stats.trials) {
            add("trial-evidence-count:${evidence.trialOutcomes.size}!=${stats.trials}")
        }
        val blockingIncidents = evidence.healthIncidents
            .groupBy { it.incidentKey }
            .values
            .count { events ->
                val severity = events.first().severity
                severity.ordinal >= blockingHealthSeverity.ordinal && events.none { it.resolved }
            }
        if (blockingIncidents > 0) add("unresolved-health-incidents:$blockingIncidents")
        if (evidence.rollbackCount > maxRollbackCount) {
            add("rollback-count:${evidence.rollbackCount}>$maxRollbackCount")
        }
    }.distinct().sorted()
}

internal fun GeneratedToolTrialResult.promotionEvidenceFingerprint(): String = StableFieldIds.fingerprint(
    "generated-tool-trial-outcome/v1",
    invocationId,
    success.toString(),
    producedExpectedOutput.toString(),
    safetyViolation.toString(),
    latencyMs.toString(),
    recordedAt.toString(),
)
