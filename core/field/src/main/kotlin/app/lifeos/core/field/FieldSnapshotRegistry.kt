package app.lifeos.core.field

data class RehydratedFieldState(
    val snapshotId: FieldSnapshotId,
    val runId: FieldRunId,
    val domainId: FieldDomainId,
    val status: ConvergenceStatus,
    val state: FieldState,
    val hypotheses: List<FieldSnapshotHypothesis>,
    val inputFingerprint: String,
    val fieldSetFingerprint: String,
    val traceFingerprint: String,
)

fun FieldSnapshot.rehydrate(): RehydratedFieldState = RehydratedFieldState(
    snapshotId = id,
    runId = runId,
    domainId = domainId,
    status = status,
    state = state,
    hypotheses = hypotheses.sortedBy { it.id.value },
    inputFingerprint = inputFingerprint,
    fieldSetFingerprint = fieldSetFingerprint,
    traceFingerprint = traceFingerprint,
)

interface FieldSnapshotRegistry {
    fun put(snapshot: FieldSnapshot): FieldSnapshotId
    fun get(id: FieldSnapshotId): FieldSnapshot?
    fun latest(domainId: FieldDomainId): FieldSnapshot?
    fun snapshots(domainId: FieldDomainId): List<FieldSnapshot>
    fun size(): Int
}

/**
 * Small dependency-free registry used by pure JVM tests and runtime adapters.
 *
 * Ordering is deterministic and does not rely on wall-clock timestamps: the newest numerical state
 * is the snapshot with the highest iteration index, then stable run/snapshot ids. A durable adapter
 * can persist the encoded snapshot while preserving the same registry semantics.
 */
class InMemoryFieldSnapshotRegistry : FieldSnapshotRegistry {
    private val byId = linkedMapOf<FieldSnapshotId, FieldSnapshot>()

    @Synchronized
    override fun put(snapshot: FieldSnapshot): FieldSnapshotId {
        val encoded = FieldSnapshotCodec.encode(snapshot)
        val verified = FieldSnapshotCodec.decode(encoded)
        require(verified == snapshot) { "Field snapshot failed deterministic round-trip verification" }
        val existing = byId[snapshot.id]
        require(existing == null || existing == snapshot) {
            "Snapshot id collision with different content: ${snapshot.id}"
        }
        byId[snapshot.id] = snapshot
        return snapshot.id
    }

    @Synchronized
    override fun get(id: FieldSnapshotId): FieldSnapshot? = byId[id]

    @Synchronized
    override fun latest(domainId: FieldDomainId): FieldSnapshot? = snapshots(domainId).lastOrNull()

    @Synchronized
    override fun snapshots(domainId: FieldDomainId): List<FieldSnapshot> = byId.values
        .asSequence()
        .filter { it.domainId == domainId }
        .sortedWith(
            compareBy<FieldSnapshot> { it.state.iteration.index }
                .thenBy { it.runId.value }
                .thenBy { it.id.value },
        )
        .toList()

    @Synchronized
    override fun size(): Int = byId.size
}
