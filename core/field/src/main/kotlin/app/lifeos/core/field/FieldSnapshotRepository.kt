package app.lifeos.core.field

data class FieldSnapshotLoadReport(
    val snapshots: List<FieldSnapshot>,
    val unreadableEntries: List<String>,
) {
    init {
        require(snapshots.map { it.id }.distinct().size == snapshots.size) {
            "Field snapshot load report must not contain duplicate ids"
        }
        require(unreadableEntries.distinct().size == unreadableEntries.size) {
            "Unreadable field snapshot entries must be unique"
        }
    }
}

/** Durable persistence boundary for content-addressed field convergence snapshots. */
interface FieldSnapshotRepository {
    suspend fun save(snapshot: FieldSnapshot)
    suspend fun load(id: FieldSnapshotId): FieldSnapshot?
    suspend fun loadLatest(domainId: FieldDomainId): FieldSnapshot?
    suspend fun loadReport(domainId: FieldDomainId? = null): FieldSnapshotLoadReport

    suspend fun loadAll(domainId: FieldDomainId? = null): List<FieldSnapshot> {
        val report = loadReport(domainId)
        check(report.unreadableEntries.isEmpty()) {
            "Unreadable field snapshots: ${report.unreadableEntries.size}"
        }
        return report.snapshots
    }

    suspend fun delete(id: FieldSnapshotId)
}
