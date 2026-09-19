package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldCoefficientId
import app.lifeos.core.field.world.WorldEquationContribution
import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.field.world.WorldFieldEdge
import app.lifeos.core.field.world.WorldFieldGraph
import app.lifeos.core.field.world.WorldFieldNode
import app.lifeos.core.field.world.WorldFieldNodeId
import app.lifeos.core.field.world.WorldFieldState
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.runtime.cognition.CognitiveTrigger
import app.lifeos.core.runtime.cognition.CognitiveTriggerType
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WorldFormulaConfig(
    val maxIterations: Int = 12,
    val requiredStableRounds: Int = 2,
    val epsilon: Double = 0.0005,
    val opposingContributionThreshold: Double = 0.10,
) {
    init {
        require(maxIterations in 1..100) { "World formula max iterations must be in 1..100" }
        require(requiredStableRounds in 1..maxIterations) {
            "World formula stable rounds must fit max iterations"
        }
        require(epsilon.isFinite() && epsilon in 0.0..1.0) {
            "World formula epsilon must be finite and in 0..1"
        }
        require(opposingContributionThreshold.isFinite() && opposingContributionThreshold in 0.0..1.0) {
            "World formula opposing contribution threshold must be in 0..1"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-formula-config/v1",
        maxIterations.toString(),
        requiredStableRounds.toString(),
        java.lang.Double.toHexString(epsilon),
        java.lang.Double.toHexString(opposingContributionThreshold),
    )
}

data class WorldFormulaInputSnapshot(
    val target: WorldTargetRef,
    val vector: WorldFieldVector,
    val sourceSnapshotFingerprint: String,
) {
    init {
        require(sourceSnapshotFingerprint.isNotBlank()) {
            "World formula input snapshot fingerprint must not be blank"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-formula-input-snapshot/v1",
        target.fingerprint(),
        vector.fingerprint(),
        sourceSnapshotFingerprint,
    )

    fun toNode(): WorldFieldNode = WorldFieldNode.create(
        target = target,
        intrinsic = vector,
        attributes = mapOf("sourceSnapshotFingerprint" to sourceSnapshotFingerprint),
    )
}

data class WorldFormulaInteraction(
    val source: WorldTargetRef,
    val target: WorldTargetRef,
    val sourceDimension: WorldSignalDimension,
    val targetDimension: WorldSignalDimension,
    val coefficientId: WorldCoefficientId,
    val strength: Double,
    val explanation: String,
) {
    init {
        require(source != target) { "World formula interaction must connect distinct targets" }
        require(strength.isFinite() && strength in 0.0..1.0) {
            "World formula interaction strength must be finite and in 0..1"
        }
        require(explanation.isNotBlank()) { "World formula interaction explanation must not be blank" }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-formula-interaction/v1",
        source.fingerprint(),
        target.fingerprint(),
        sourceDimension.name,
        targetDimension.name,
        coefficientId.value,
        java.lang.Double.toHexString(strength),
        explanation,
    )
}

data class WorldFormulaRequest(
    val inputs: List<WorldFormulaInputSnapshot>,
    val interactions: List<WorldFormulaInteraction>,
    val equationVersion: String,
    val observedAt: Instant,
    val config: WorldFormulaConfig = WorldFormulaConfig(),
    val sourceTaskId: TaskId? = null,
    val photonId: PhotonId? = null,
) {
    init {
        require(inputs.isNotEmpty()) { "World formula requires at least one input snapshot" }
        require(inputs.map { it.target }.distinct().size == inputs.size) {
            "World formula input targets must be unique"
        }
        require(equationVersion.isNotBlank()) { "World formula equation version must not be blank" }
        val targets = inputs.mapTo(mutableSetOf()) { it.target }
        require(interactions.all { it.source in targets && it.target in targets }) {
            "World formula interactions must reference input targets"
        }
    }

    val id: String = StableFieldIds.fingerprint(
        "world-formula-request/v1",
        equationVersion,
        observedAt.toString(),
        config.fingerprint(),
        sourceTaskId?.value.orEmpty(),
        photonId?.value.orEmpty(),
        *inputs.map { it.fingerprint() }.sorted().toTypedArray(),
        *interactions.map { it.fingerprint() }.sorted().toTypedArray(),
    )

    fun buildGraph(): WorldFieldGraph {
        val nodes = inputs.map(WorldFormulaInputSnapshot::toNode)
        val byTarget = nodes.associateBy { it.target }
        val edges = interactions.map { interaction ->
            WorldFieldEdge.create(
                sourceNodeId = byTarget.getValue(interaction.source).id,
                targetNodeId = byTarget.getValue(interaction.target).id,
                sourceDimension = interaction.sourceDimension,
                targetDimension = interaction.targetDimension,
                coefficientId = interaction.coefficientId,
                strength = interaction.strength,
                explanation = interaction.explanation,
            )
        }
        return WorldFieldGraph(nodes = nodes, edges = edges)
    }
}

interface WorldEquationRegistry {
    suspend fun resolve(version: String): WorldEquationSpec?
}

class InMemoryWorldEquationRegistry(
    specs: List<WorldEquationSpec> = emptyList(),
) : WorldEquationRegistry {
    private val mutex = Mutex()
    private val byVersion = linkedMapOf<String, WorldEquationSpec>()

    init {
        specs.forEach { spec ->
            require(spec.version !in byVersion) { "Duplicate world equation version ${spec.version}" }
            byVersion[spec.version] = spec
        }
    }

    suspend fun register(spec: WorldEquationSpec) = mutex.withLock {
        val existing = byVersion[spec.version]
        require(existing == null || existing.fingerprint() == spec.fingerprint()) {
            "World equation version ${spec.version} already maps to different physics"
        }
        byVersion[spec.version] = spec
    }

    override suspend fun resolve(version: String): WorldEquationSpec? = mutex.withLock {
        byVersion[version]
    }
}

enum class WorldFormulaStatus {
    CONVERGED,
    UNRESOLVED,
    MAX_ITERATIONS,
    INVALID_EQUATION,
}

data class WorldFormulaIteration(
    val index: Int,
    val beforeStateFingerprint: String,
    val afterStateFingerprint: String,
    val maxDelta: Double,
    val stableRounds: Int,
    val contributions: List<WorldEquationContribution>,
) {
    init {
        require(index > 0) { "World formula iteration index must be positive" }
        require(beforeStateFingerprint.isNotBlank() && afterStateFingerprint.isNotBlank())
        require(maxDelta.isFinite() && maxDelta in 0.0..1.0)
        require(stableRounds >= 0)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-formula-iteration/v1",
        index.toString(),
        beforeStateFingerprint,
        afterStateFingerprint,
        java.lang.Double.toHexString(maxDelta),
        stableRounds.toString(),
        *contributions.sortedBy { it.edgeId.value }.flatMap { contribution ->
            listOf(
                contribution.edgeId.value,
                contribution.sourceNodeId.value,
                contribution.targetNodeId.value,
                contribution.sourceDimension.name,
                contribution.targetDimension.name,
                contribution.coefficientId.value,
                java.lang.Double.toHexString(contribution.signedDelta),
                java.lang.Double.toHexString(contribution.confidence),
                contribution.provenanceFingerprint,
            )
        }.toTypedArray(),
    )
}

data class WorldFormulaConflict(
    val key: String,
    val targetNodeId: WorldFieldNodeId,
    val dimension: WorldSignalDimension,
    val positiveEdgeIds: Set<String>,
    val negativeEdgeIds: Set<String>,
    val maxPositive: Double,
    val maxNegativeMagnitude: Double,
) {
    init {
        require(key.isNotBlank())
        require(positiveEdgeIds.isNotEmpty() && negativeEdgeIds.isNotEmpty())
        require(positiveEdgeIds.none { it.isBlank() } && negativeEdgeIds.none { it.isBlank() })
        require(maxPositive.isFinite() && maxPositive in 0.0..1.0)
        require(maxNegativeMagnitude.isFinite() && maxNegativeMagnitude in 0.0..1.0)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-formula-conflict/v1",
        key,
        targetNodeId.value,
        dimension.name,
        java.lang.Double.toHexString(maxPositive),
        java.lang.Double.toHexString(maxNegativeMagnitude),
        *positiveEdgeIds.sorted().toTypedArray(),
        *negativeEdgeIds.sorted().toTypedArray(),
    )
}

enum class WorldFormulaAnomalyType {
    INVALID_EQUATION,
    MAX_ITERATIONS,
    OPPOSING_INFLUENCES,
}

data class WorldFormulaAnomaly(
    val type: WorldFormulaAnomalyType,
    val key: String,
    val detail: String,
) {
    init {
        require(key.isNotBlank())
        require(detail.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-formula-anomaly/v1",
        type.name,
        key,
        detail,
    )
}

data class WorldFormulaSnapshot(
    val id: String,
    val runId: String,
    val requestId: String,
    val equationVersion: String,
    val equationFingerprint: String,
    val graphFingerprint: String,
    val configFingerprint: String,
    val status: WorldFormulaStatus,
    val finalState: WorldFieldState,
    val iterations: List<WorldFormulaIteration>,
    val conflicts: List<WorldFormulaConflict>,
    val anomalies: List<WorldFormulaAnomaly>,
    val inputSnapshotFingerprints: Set<String>,
) {
    init {
        require(id.isNotBlank() && runId.isNotBlank() && requestId.isNotBlank())
        require(equationVersion.isNotBlank())
        require(equationFingerprint.isNotBlank() && graphFingerprint.isNotBlank() && configFingerprint.isNotBlank())
        require(finalState.graphFingerprint == graphFingerprint)
        require(finalState.equationFingerprint == equationFingerprint)
        require(iterations.map { it.index } == (1..iterations.size).toList()) {
            "World formula snapshot iterations must be contiguous"
        }
        require(conflicts.map { it.key }.distinct().size == conflicts.size)
        require(anomalies.map { it.key }.distinct().size == anomalies.size)
        require(inputSnapshotFingerprints.isNotEmpty() && inputSnapshotFingerprints.none { it.isBlank() })
        require(id == expectedId()) { "World formula snapshot id does not match content" }
    }

    fun contentFingerprint(): String = StableFieldIds.fingerprint(*contentParts().toTypedArray())

    private fun expectedId(): String = "world-snapshot:${contentFingerprint()}"

    private fun contentParts(): List<String> = buildList {
        add("world-formula-snapshot/v1")
        add(runId)
        add(requestId)
        add(equationVersion)
        add(equationFingerprint)
        add(graphFingerprint)
        add(configFingerprint)
        add(status.name)
        add(finalState.fingerprint())
        iterations.forEach { add("iteration:${it.fingerprint()}") }
        conflicts.sortedBy { it.key }.forEach { add("conflict:${it.fingerprint()}") }
        anomalies.sortedBy { it.key }.forEach { add("anomaly:${it.fingerprint()}") }
        inputSnapshotFingerprints.sorted().forEach { add("input:$it") }
    }

    companion object {
        fun create(
            runId: String,
            requestId: String,
            equationVersion: String,
            equationFingerprint: String,
            graphFingerprint: String,
            configFingerprint: String,
            status: WorldFormulaStatus,
            finalState: WorldFieldState,
            iterations: List<WorldFormulaIteration>,
            conflicts: List<WorldFormulaConflict>,
            anomalies: List<WorldFormulaAnomaly>,
            inputSnapshotFingerprints: Set<String>,
        ): WorldFormulaSnapshot {
            val provisional = WorldFormulaSnapshotContent(
                runId,
                requestId,
                equationVersion,
                equationFingerprint,
                graphFingerprint,
                configFingerprint,
                status,
                finalState,
                iterations,
                conflicts,
                anomalies,
                inputSnapshotFingerprints,
            )
            return WorldFormulaSnapshot(
                id = "world-snapshot:${provisional.fingerprint()}",
                runId = runId,
                requestId = requestId,
                equationVersion = equationVersion,
                equationFingerprint = equationFingerprint,
                graphFingerprint = graphFingerprint,
                configFingerprint = configFingerprint,
                status = status,
                finalState = finalState,
                iterations = iterations.toList(),
                conflicts = conflicts.sortedBy { it.key },
                anomalies = anomalies.sortedBy { it.key },
                inputSnapshotFingerprints = inputSnapshotFingerprints.toSortedSet(),
            )
        }
    }
}

private data class WorldFormulaSnapshotContent(
    val runId: String,
    val requestId: String,
    val equationVersion: String,
    val equationFingerprint: String,
    val graphFingerprint: String,
    val configFingerprint: String,
    val status: WorldFormulaStatus,
    val finalState: WorldFieldState,
    val iterations: List<WorldFormulaIteration>,
    val conflicts: List<WorldFormulaConflict>,
    val anomalies: List<WorldFormulaAnomaly>,
    val inputSnapshotFingerprints: Set<String>,
) {
    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-formula-snapshot/v1",
        runId,
        requestId,
        equationVersion,
        equationFingerprint,
        graphFingerprint,
        configFingerprint,
        status.name,
        finalState.fingerprint(),
        *iterations.map { "iteration:${it.fingerprint()}" }.toTypedArray(),
        *conflicts.sortedBy { it.key }.map { "conflict:${it.fingerprint()}" }.toTypedArray(),
        *anomalies.sortedBy { it.key }.map { "anomaly:${it.fingerprint()}" }.toTypedArray(),
        *inputSnapshotFingerprints.sorted().map { "input:$it" }.toTypedArray(),
    )
}

data class WorldFormulaSnapshotLoadReport(
    val snapshots: List<WorldFormulaSnapshot>,
    val unreadableEntries: List<String>,
) {
    init {
        require(snapshots.map { it.id }.distinct().size == snapshots.size)
        require(unreadableEntries.distinct().size == unreadableEntries.size)
    }
}

interface WorldFormulaSnapshotRepository {
    suspend fun save(snapshot: WorldFormulaSnapshot)
    suspend fun load(id: String): WorldFormulaSnapshot?
    @Deprecated(
        message = "WorldFormula snapshots have no global chronological head; use ProductiveWorldHead or a domain-specific authority",
        level = DeprecationLevel.WARNING,
    )
    suspend fun loadLatest(): WorldFormulaSnapshot?
    suspend fun loadReport(): WorldFormulaSnapshotLoadReport
    suspend fun delete(id: String)
}

enum class WorldFormulaExecutionState {
    COMPLETED,
    INVALID,
    PERSISTENCE_FAILED,
}

data class WorldFormulaExecution(
    val state: WorldFormulaExecutionState,
    val status: WorldFormulaStatus,
    val snapshot: WorldFormulaSnapshot?,
    val persisted: Boolean,
    val message: String,
    val scope: WorldFormulaExecutionScope = WorldFormulaExecutionScope.PRODUCTIVE_COGNITIVE,
    val productiveCommitAllowed: Boolean =
        scope == WorldFormulaExecutionScope.PRODUCTIVE_COGNITIVE,
) {
    init {
        require(message.isNotBlank())
        require(!persisted || snapshot != null)
        if (state == WorldFormulaExecutionState.COMPLETED) {
            require(snapshot != null && persisted)
        }
        if (state == WorldFormulaExecutionState.INVALID) {
            require(status == WorldFormulaStatus.INVALID_EQUATION)
        }
    }
}

fun interface WorldFormulaTriggerPolicy {
    fun triggerFor(
        request: WorldFormulaRequest,
        snapshot: WorldFormulaSnapshot,
    ): CognitiveTrigger?
}

object DefaultWorldFormulaTriggerPolicy : WorldFormulaTriggerPolicy {
    override fun triggerFor(
        request: WorldFormulaRequest,
        snapshot: WorldFormulaSnapshot,
    ): CognitiveTrigger? {
        val taskId = request.sourceTaskId ?: return null
        if (
            snapshot.status != WorldFormulaStatus.UNRESOLVED &&
            snapshot.status != WorldFormulaStatus.MAX_ITERATIONS
        ) return null
        return CognitiveTrigger(
            id = "world-trigger:${StableFieldIds.fingerprint(snapshot.id, taskId.value)}",
            type = CognitiveTriggerType.REEVALUATE,
            sourceTaskId = taskId,
            photonId = request.photonId,
            reason = "world-formula-${snapshot.status.name.lowercase()}",
            createdAt = request.observedAt,
        )
    }
}
