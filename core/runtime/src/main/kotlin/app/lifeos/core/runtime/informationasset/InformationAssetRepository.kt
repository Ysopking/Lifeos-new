package app.lifeos.core.runtime.informationasset

enum class InformationAssetSaveResult {
    STORED,
    ALREADY_PRESENT,
}

data class InformationAssetRevisionLoadReport(
    val revision: InformationAssetRevision?,
    val unreadableEntries: List<String> = emptyList(),
) {
    init { require(unreadableEntries.none { it.isBlank() }) }
}

data class InformationAssetHistoryLoadReport(
    val revisions: List<InformationAssetRevision>,
    val unreadableEntries: List<String> = emptyList(),
) {
    init {
        require(revisions.map { it.manifest.id }.distinct().size == revisions.size) {
            "Information asset history must not contain duplicate revisions"
        }
        require(unreadableEntries.none { it.isBlank() })
    }
}

/**
 * Revision-addressed durable store for semantic information assets.
 *
 * Implementations must never silently replace an existing revision with different content.
 * Re-saving byte-identical/canonically-identical content is idempotent.
 */
interface InformationAssetRepository {
    suspend fun save(revision: InformationAssetRevision): InformationAssetSaveResult

    suspend fun loadRevision(
        assetId: InformationAssetId,
        revisionId: InformationAssetRevisionId,
    ): InformationAssetRevisionLoadReport

    suspend fun loadLatest(assetId: InformationAssetId): InformationAssetRevisionLoadReport

    suspend fun loadHistory(assetId: InformationAssetId): InformationAssetHistoryLoadReport
}
