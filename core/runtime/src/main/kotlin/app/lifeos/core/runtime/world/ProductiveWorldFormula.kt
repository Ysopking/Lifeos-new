package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds

@JvmInline
value class CognitiveCycleId(val value: String) {
    init {
        require(value.isNotBlank()) { "Cognitive cycle id must not be blank" }
    }

    override fun toString(): String = value
}

enum class WorldFormulaSnapshotNamespace {
    PRODUCTIVE,
    SHADOW,
    COUNTERFACTUAL,
}

data class WorldFormulaCycleContext(
    val cycleId: CognitiveCycleId,
    val previousWorldSnapshotId: String?,
    val representationSnapshotId: String,
    val strategySnapshotId: String,
    val equationVersion: String,
    val resourceSnapshotId: String,
) {
    init {
        require(previousWorldSnapshotId == null || previousWorldSnapshotId.isNotBlank())
        require(representationSnapshotId.isNotBlank()) {
            "World formula cycle requires a representation snapshot"
        }
        require(strategySnapshotId.isNotBlank()) {
            "World formula cycle requires a strategy snapshot"
        }
        require(equationVersion.isNotBlank()) {
            "World formula cycle requires an equation version"
        }
        require(resourceSnapshotId.isNotBlank()) {
            "World formula cycle requires a resource snapshot"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-formula-cycle-context/v1",
        cycleId.value,
        previousWorldSnapshotId.orEmpty(),
        representationSnapshotId,
        strategySnapshotId,
        equationVersion,
        resourceSnapshotId,
    )
}

data class ProductiveWorldFormulaRequest(
    val request: WorldFormulaRequest,
    val cycle: WorldFormulaCycleContext,
) {
    init {
        require(request.sourceTaskId != null) {
            "Productive WorldFormula request requires source task provenance"
        }
        require(request.photonId != null) {
            "Productive WorldFormula request requires source Photon provenance"
        }
        require(request.equationVersion == cycle.equationVersion) {
            "Productive WorldFormula request equation differs from frozen cycle physics"
        }
    }

    val id: String = StableFieldIds.fingerprint(
        "productive-world-formula-request/v1",
        request.id,
        cycle.fingerprint(),
        request.sourceTaskId!!.value,
        request.photonId!!.value,
    )
}

data class WorldFormulaSnapshotRef(
    val namespace: WorldFormulaSnapshotNamespace,
    val snapshotId: String,
    val equationVersion: String,
    val cycleId: CognitiveCycleId?,
) {
    init {
        require(snapshotId.isNotBlank())
        require(equationVersion.isNotBlank())
        if (namespace == WorldFormulaSnapshotNamespace.PRODUCTIVE) {
            require(cycleId != null) {
                "Productive world snapshot ref requires a cognitive cycle id"
            }
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-formula-snapshot-ref/v1",
        namespace.name,
        snapshotId,
        equationVersion,
        cycleId?.value.orEmpty(),
    )

    companion object {
        fun productive(
            snapshot: WorldFormulaSnapshot,
            cycle: WorldFormulaCycleContext,
        ): WorldFormulaSnapshotRef {
            require(snapshot.equationVersion == cycle.equationVersion) {
                "Productive snapshot equation differs from frozen cycle context"
            }
            return WorldFormulaSnapshotRef(
                namespace = WorldFormulaSnapshotNamespace.PRODUCTIVE,
                snapshotId = snapshot.id,
                equationVersion = snapshot.equationVersion,
                cycleId = cycle.cycleId,
            )
        }
    }
}

data class ProductiveWorldCandidate(
    val request: ProductiveWorldFormulaRequest,
    val snapshot: WorldFormulaSnapshot,
    val snapshotRef: WorldFormulaSnapshotRef,
) {
    init {
        require(snapshotRef.namespace == WorldFormulaSnapshotNamespace.PRODUCTIVE)
        require(snapshotRef.snapshotId == snapshot.id)
        require(snapshotRef.equationVersion == snapshot.equationVersion)
        require(snapshotRef.cycleId == request.cycle.cycleId)
        require(snapshot.requestId == request.request.id)
        require(snapshot.equationVersion == request.cycle.equationVersion)
    }

    companion object {
        fun from(
            request: ProductiveWorldFormulaRequest,
            execution: WorldFormulaExecution,
        ): ProductiveWorldCandidate {
            require(
                execution.scope == WorldFormulaExecutionScope.PRODUCTIVE_COGNITIVE
            ) {
                "Productive world candidate requires productive cognitive execution"
            }
            require(execution.state == WorldFormulaExecutionState.COMPLETED) {
                "Productive world candidate requires completed WorldFormula execution"
            }
            require(execution.persisted) {
                "Productive world candidate requires a persisted WorldFormula snapshot"
            }
            val snapshot = requireNotNull(execution.snapshot)
            return ProductiveWorldCandidate(
                request = request,
                snapshot = snapshot,
                snapshotRef = WorldFormulaSnapshotRef.productive(snapshot, request.cycle),
            )
        }
    }
}

data class ProductiveWorldHead private constructor(
    val revision: Long,
    val activeSnapshot: WorldFormulaSnapshotRef,
    val predecessorSnapshotId: String?,
    val equationVersion: String,
    val cycleId: CognitiveCycleId,
    val cycleContextFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(revision > 0L)
        require(activeSnapshot.namespace == WorldFormulaSnapshotNamespace.PRODUCTIVE)
        require(activeSnapshot.cycleId == cycleId)
        require(activeSnapshot.equationVersion == equationVersion)
        require(predecessorSnapshotId == null || predecessorSnapshotId.isNotBlank())
        require(cycleContextFingerprint.isNotBlank())
        require(fingerprint == expectedFingerprint()) {
            "Productive world head fingerprint does not match content"
        }
    }

    private fun expectedFingerprint(): String = StableFieldIds.fingerprint(
        "productive-world-head/v1",
        revision.toString(),
        activeSnapshot.fingerprint(),
        predecessorSnapshotId.orEmpty(),
        equationVersion,
        cycleId.value,
        cycleContextFingerprint,
    )

    companion object {
        fun create(
            revision: Long,
            candidate: ProductiveWorldCandidate,
        ): ProductiveWorldHead {
            val predecessor = candidate.request.cycle.previousWorldSnapshotId
            val fingerprint = StableFieldIds.fingerprint(
                "productive-world-head/v1",
                revision.toString(),
                candidate.snapshotRef.fingerprint(),
                predecessor.orEmpty(),
                candidate.snapshot.equationVersion,
                candidate.request.cycle.cycleId.value,
                candidate.request.cycle.fingerprint(),
            )
            return ProductiveWorldHead(
                revision = revision,
                activeSnapshot = candidate.snapshotRef,
                predecessorSnapshotId = predecessor,
                equationVersion = candidate.snapshot.equationVersion,
                cycleId = candidate.request.cycle.cycleId,
                cycleContextFingerprint = candidate.request.cycle.fingerprint(),
                fingerprint = fingerprint,
            )
        }

        fun restore(
            revision: Long,
            activeSnapshot: WorldFormulaSnapshotRef,
            predecessorSnapshotId: String?,
            equationVersion: String,
            cycleId: CognitiveCycleId,
            cycleContextFingerprint: String,
            fingerprint: String,
        ): ProductiveWorldHead = ProductiveWorldHead(
            revision = revision,
            activeSnapshot = activeSnapshot,
            predecessorSnapshotId = predecessorSnapshotId,
            equationVersion = equationVersion,
            cycleId = cycleId,
            cycleContextFingerprint = cycleContextFingerprint,
            fingerprint = fingerprint,
        )
    }
}

data class ProductiveWorldHeadLoadReport(
    val head: ProductiveWorldHead?,
    val corrupted: Boolean,
    val message: String?,
) {
    init {
        require(message == null || message.isNotBlank())
        require(!corrupted || message != null) {
            "Corrupted productive world head report requires a message"
        }
    }
}

interface ProductiveWorldHeadRepository {
    suspend fun load(): ProductiveWorldHead?

    suspend fun compareAndSet(
        expectedRevision: Long?,
        next: ProductiveWorldHead,
    ): Boolean

    suspend fun loadReport(): ProductiveWorldHeadLoadReport =
        ProductiveWorldHeadLoadReport(
            head = load(),
            corrupted = false,
            message = null,
        )
}

sealed interface ProductiveWorldCommitResult {
    data class Committed(
        val previousHead: ProductiveWorldHead?,
        val currentHead: ProductiveWorldHead,
    ) : ProductiveWorldCommitResult

    data object ConcurrentHeadChanged : ProductiveWorldCommitResult

    data class Blocked(val reason: String) : ProductiveWorldCommitResult {
        init {
            require(reason.isNotBlank())
        }
    }
}

/**
 * B161 explicit publication boundary.
 *
 * WorldFormulaSnapshot storage remains immutable/content-addressed. This committer can publish only
 * a persisted PRODUCTIVE snapshot and advances the separate ProductiveWorldHead with CAS. It owns
 * no lifecycle loop; B162 BootEngine is the caller that freezes and supplies one cycle context.
 */
class ProductiveWorldHeadCommitter(
    private val snapshots: WorldFormulaSnapshotRepository,
    private val heads: ProductiveWorldHeadRepository,
) {
    suspend fun commit(
        candidate: ProductiveWorldCandidate,
        expectedHead: ProductiveWorldHead?,
    ): ProductiveWorldCommitResult {
        val live = heads.load()
        if (live != expectedHead) {
            return ProductiveWorldCommitResult.ConcurrentHeadChanged
        }

        val expectedPredecessor = expectedHead?.activeSnapshot?.snapshotId
        if (candidate.request.cycle.previousWorldSnapshotId != expectedPredecessor) {
            return ProductiveWorldCommitResult.Blocked(
                "productive-world-predecessor-mismatch"
            )
        }

        val persisted = snapshots.load(candidate.snapshot.id)
            ?: return ProductiveWorldCommitResult.Blocked(
                "productive-world-snapshot-not-persisted"
            )
        require(persisted == candidate.snapshot) {
            "Persisted productive WorldFormula snapshot differs from candidate"
        }

        val next = ProductiveWorldHead.create(
            revision = (expectedHead?.revision ?: 0L) + 1L,
            candidate = candidate,
        )
        val committed = heads.compareAndSet(expectedHead?.revision, next)
        return if (committed) {
            ProductiveWorldCommitResult.Committed(expectedHead, next)
        } else {
            ProductiveWorldCommitResult.ConcurrentHeadChanged
        }
    }
}
