package app.lifeos.core.model

/** Canonical subsystem roots for localized recovery, invalidation and shadow comparison. */
enum class WorldMerkleDomain {
    COGNITION_MODULE,
    COGNITION_FIELD,
    KNOWLEDGE_MEMORY_HOT,
    KNOWLEDGE_MEMORY_WARM,
    KNOWLEDGE_MEMORY_COLD,
    KNOWLEDGE_EVIDENCE,
    AGENCY_GOALS,
    AGENCY_TASKS,
    RUNTIME_HARDWARE,
    RUNTIME_HEALTH,
}

data class WorldMerkleLeaf(
    val domain: WorldMerkleDomain,
    val partitionKey: String,
    val revision: Long,
    val contentFingerprint: String,
) {
    init {
        require(partitionKey.isNotBlank())
        require(revision > 0)
        require(contentFingerprint.isNotBlank())
    }

    val stableFingerprint: String
        get() = StableCognitiveIds.fingerprint(
            domain.name,
            partitionKey,
            revision.toString(),
            contentFingerprint,
        )
}

data class WorldMerkleBranch(
    val branchKey: String,
    val childFingerprints: List<String>,
) {
    init {
        require(branchKey.isNotBlank())
        require(childFingerprints.none { it.isBlank() })
    }

    val stableFingerprint: String
        get() = StableCognitiveIds.fingerprint(
            branchKey,
            *childFingerprints.sorted().toTypedArray(),
        )
}

data class HierarchicalWorldMerkle(
    val leaves: List<WorldMerkleLeaf>,
) {
    init {
        require(leaves.map { it.domain to it.partitionKey }.distinct().size == leaves.size) {
            "Merkle leaves must have unique domain/partition identities"
        }
    }

    private fun domainRoot(domain: WorldMerkleDomain): String =
        WorldMerkleBranch(
            branchKey = domain.name,
            childFingerprints = leaves.filter { it.domain == domain }.map { it.stableFingerprint },
        ).stableFingerprint

    val cognitionRoot: String
        get() = WorldMerkleBranch("COGNITION", listOf(domainRoot(WorldMerkleDomain.COGNITION_MODULE), domainRoot(WorldMerkleDomain.COGNITION_FIELD))).stableFingerprint

    val knowledgeRoot: String
        get() = WorldMerkleBranch("KNOWLEDGE", listOf(
            domainRoot(WorldMerkleDomain.KNOWLEDGE_MEMORY_HOT),
            domainRoot(WorldMerkleDomain.KNOWLEDGE_MEMORY_WARM),
            domainRoot(WorldMerkleDomain.KNOWLEDGE_MEMORY_COLD),
            domainRoot(WorldMerkleDomain.KNOWLEDGE_EVIDENCE),
        )).stableFingerprint

    val agencyRoot: String
        get() = WorldMerkleBranch("AGENCY", listOf(domainRoot(WorldMerkleDomain.AGENCY_GOALS), domainRoot(WorldMerkleDomain.AGENCY_TASKS))).stableFingerprint

    val runtimeRoot: String
        get() = WorldMerkleBranch("RUNTIME", listOf(domainRoot(WorldMerkleDomain.RUNTIME_HARDWARE), domainRoot(WorldMerkleDomain.RUNTIME_HEALTH))).stableFingerprint

    val worldRoot: String
        get() = WorldMerkleBranch("WORLD", listOf(cognitionRoot, knowledgeRoot, agencyRoot, runtimeRoot)).stableFingerprint

    fun changedDomains(other: HierarchicalWorldMerkle): Set<WorldMerkleDomain> =
        WorldMerkleDomain.entries.filterTo(linkedSetOf()) { domain -> domainRoot(domain) != other.domainRoot(domain) }
}
