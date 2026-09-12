package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.field.EvidenceId
import app.lifeos.core.field.HypothesisId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.capability.CapabilityId
import java.time.Duration

@JvmInline
value class DeepSearchRequestId(val value: String) {
    init { require(value.isNotBlank()) { "DeepSearch request id must not be blank" } }
}

@JvmInline
value class DeepSearchBranchId(val value: String) {
    init { require(value.isNotBlank()) { "DeepSearch branch id must not be blank" } }
}

@JvmInline
value class DeepSearchEvidenceId(val value: String) {
    init { require(value.isNotBlank()) { "DeepSearch evidence id must not be blank" } }
}

@JvmInline
value class DeepSearchHypothesisId(val value: String) {
    init { require(value.isNotBlank()) { "DeepSearch hypothesis id must not be blank" } }
}

enum class DeepSearchSourceKind {
    LOCAL,
    EXTERNAL,
}

enum class DeepSearchPermissionState {
    NOT_REQUIRED,
    GRANTED,
    DENIED,
    UNKNOWN,
}

data class DeepSearchBudget(
    val maxDepth: Int = 4,
    val maxBreadth: Int = 8,
    val maxWorkUnits: Int = 64,
    val maxElapsed: Duration = Duration.ofSeconds(10),
) {
    init {
        require(maxDepth in 1..32) { "DeepSearch depth must be in 1..32" }
        require(maxBreadth in 1..64) { "DeepSearch breadth must be in 1..64" }
        require(maxWorkUnits in 1..10_000) { "DeepSearch work budget must be in 1..10000" }
        require(!maxElapsed.isZero && !maxElapsed.isNegative) { "DeepSearch elapsed budget must be positive" }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "deep-search-budget/v1",
        maxDepth.toString(),
        maxBreadth.toString(),
        maxWorkUnits.toString(),
        maxElapsed.seconds.toString(),
        maxElapsed.nano.toString(),
    )
}

data class DeepSearchRequest(
    val query: String,
    val contextTerms: Set<String> = emptySet(),
    val budget: DeepSearchBudget = DeepSearchBudget(),
    val minimumResolutionScore: Double = 0.68,
    val minimumWinnerMargin: Double = 0.08,
) {
    init {
        require(query.isNotBlank()) { "DeepSearch query must not be blank" }
        require(contextTerms.none { it.isBlank() }) { "DeepSearch context terms must not be blank" }
        require(minimumResolutionScore.isFinite() && minimumResolutionScore in 0.0..1.0)
        require(minimumWinnerMargin.isFinite() && minimumWinnerMargin in 0.0..1.0)
    }

    val id: DeepSearchRequestId = DeepSearchRequestId(
        StableFieldIds.fingerprint(
            "deep-search-request/v1",
            normalizeSearchText(query),
            budget.fingerprint(),
            java.lang.Double.toHexString(minimumResolutionScore),
            java.lang.Double.toHexString(minimumWinnerMargin),
            *contextTerms.map(::normalizeSearchText).filter { it.isNotBlank() }.sorted().toTypedArray(),
        )
    )

    val queryTerms: Set<String> = tokenizeSearchText(query)
}

data class DeepSearchSourceDescriptor(
    val sourceId: String,
    val kind: DeepSearchSourceKind,
    val capabilityId: CapabilityId? = null,
    val permissionState: DeepSearchPermissionState = if (kind == DeepSearchSourceKind.LOCAL) {
        DeepSearchPermissionState.NOT_REQUIRED
    } else {
        DeepSearchPermissionState.UNKNOWN
    },
    val reliability: Double = 1.0,
    val workUnitsPerExpansion: Int = 1,
) {
    init {
        require(sourceId.isNotBlank()) { "DeepSearch source id must not be blank" }
        require(reliability.isFinite() && reliability in 0.0..1.0)
        require(workUnitsPerExpansion in 1..1_000)
        if (kind == DeepSearchSourceKind.EXTERNAL) {
            require(capabilityId != null) { "External DeepSearch source requires an explicit capability" }
            require(permissionState != DeepSearchPermissionState.NOT_REQUIRED) {
                "External DeepSearch source cannot bypass permission state"
            }
        }
    }
}

data class DeepSearchEvidenceDraft(
    val statement: String,
    val confidence: Double,
    val sourcePhotonId: PhotonId? = null,
    val fieldEvidenceId: EvidenceId? = null,
    val contradiction: Boolean = false,
) {
    init {
        require(statement.isNotBlank()) { "DeepSearch evidence statement must not be blank" }
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class DeepSearchFindingDraft(
    val statement: String,
    val semanticTerms: Set<String> = emptySet(),
    val confidence: Double,
    val evidence: List<DeepSearchEvidenceDraft>,
    val fieldHypothesisId: HypothesisId? = null,
) {
    init {
        require(statement.isNotBlank()) { "DeepSearch finding statement must not be blank" }
        require(semanticTerms.none { it.isBlank() })
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(evidence.isNotEmpty()) { "DeepSearch finding requires traceable evidence" }
    }
}

data class DeepSearchEvidence(
    val id: DeepSearchEvidenceId,
    val requestId: DeepSearchRequestId,
    val branchId: DeepSearchBranchId,
    val sourceId: String,
    val statement: String,
    val confidence: Double,
    val sourcePhotonId: PhotonId?,
    val fieldEvidenceId: EvidenceId?,
    val contradiction: Boolean,
) {
    init {
        require(sourceId.isNotBlank())
        require(statement.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class DeepSearchHypothesis(
    val id: DeepSearchHypothesisId,
    val requestId: DeepSearchRequestId,
    val statement: String,
    val semanticTerms: Set<String>,
    val confidence: Double,
    val evidenceIds: Set<DeepSearchEvidenceId>,
    val fieldHypothesisId: HypothesisId? = null,
) {
    init {
        require(statement.isNotBlank())
        require(semanticTerms.none { it.isBlank() })
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(evidenceIds.isNotEmpty()) { "DeepSearch hypothesis requires evidence" }
    }

    fun signature(): String = StableFieldIds.fingerprint(
        "deep-search-hypothesis-signature/v1",
        normalizeSearchText(statement),
        *semanticTerms.map(::normalizeSearchText).filter { it.isNotBlank() }.sorted().toTypedArray(),
    )
}

data class DeepSearchScore(
    val relevance: Double,
    val evidenceStrength: Double,
    val sourceReliability: Double,
    val novelty: Double,
    val depthCost: Double,
    val contradictionPenalty: Double,
    val total: Double,
) {
    init {
        require(
            listOf(
                relevance,
                evidenceStrength,
                sourceReliability,
                novelty,
                depthCost,
                contradictionPenalty,
                total,
            ).all { it.isFinite() && it in 0.0..1.0 }
        )
    }
}

data class DeepSearchBranch(
    val id: DeepSearchBranchId,
    val requestId: DeepSearchRequestId,
    val parentId: DeepSearchBranchId?,
    val sourceId: String,
    val depth: Int,
    val hypothesis: DeepSearchHypothesis,
    val score: DeepSearchScore,
) {
    init {
        require(sourceId.isNotBlank())
        require(depth >= 0)
        require(hypothesis.requestId == requestId)
        require(depth == 0 || parentId != null) { "Non-root DeepSearch branch requires a parent" }
    }
}

enum class DeepSearchTraceType {
    ROOT_CREATED,
    SOURCE_AUTHORIZED,
    SOURCE_BLOCKED,
    SOURCE_FAILED,
    EXPANSION_RESERVED,
    BRANCH_EXPANDED,
    CANDIDATE_ACCEPTED,
    CANDIDATE_DUPLICATE,
    CANDIDATE_BREADTH_REJECTED,
    DEPTH_LIMIT_REACHED,
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
    RESOLVED,
    UNRESOLVED,
}

data class DeepSearchTraceEvent(
    val sequence: Int,
    val type: DeepSearchTraceType,
    val branchId: DeepSearchBranchId? = null,
    val sourceId: String? = null,
    val hypothesisId: DeepSearchHypothesisId? = null,
    val evidenceIds: Set<DeepSearchEvidenceId> = emptySet(),
    val detail: String,
) {
    init {
        require(sequence >= 0)
        require(sourceId == null || sourceId.isNotBlank())
        require(detail.isNotBlank())
    }
}

enum class DeepSearchStatus {
    RESOLVED,
    UNRESOLVED,
    WORK_BUDGET_EXHAUSTED,
    TIME_BUDGET_EXHAUSTED,
    PERMISSION_BLOCKED,
    NO_USABLE_SOURCE,
}

data class DeepSearchResult(
    val requestId: DeepSearchRequestId,
    val status: DeepSearchStatus,
    val best: DeepSearchBranch?,
    val alternatives: List<DeepSearchBranch>,
    val evidence: List<DeepSearchEvidence>,
    val trace: List<DeepSearchTraceEvent>,
    val workUnitsUsed: Int,
    val blockedSourceIds: Set<String>,
    val failedSourceIds: Set<String>,
) {
    init {
        require(workUnitsUsed >= 0)
        require(alternatives.map { it.id }.distinct().size == alternatives.size)
        require(evidence.map { it.id }.distinct().size == evidence.size)
        require(trace.map { it.sequence } == trace.indices.toList()) {
            "DeepSearch trace sequence must be contiguous"
        }
        require(blockedSourceIds.none { it.isBlank() })
        require(failedSourceIds.none { it.isBlank() })
        if (status == DeepSearchStatus.RESOLVED) {
            require(best != null) { "Resolved DeepSearch result requires a best branch" }
        }
    }
}

interface DeepSearchSource {
    val descriptor: DeepSearchSourceDescriptor

    suspend fun expand(
        request: DeepSearchRequest,
        branch: DeepSearchBranch,
    ): List<DeepSearchFindingDraft>
}

internal fun normalizeSearchText(value: String): String = value
    .trim()
    .lowercase()
    .replace("ß", "ss")
    .replace(Regex("\\s+"), " ")

internal fun tokenizeSearchText(value: String): Set<String> = normalizeSearchText(value)
    .split(Regex("[^\\p{L}\\p{N}._-]+"))
    .asSequence()
    .map(String::trim)
    .filter { it.isNotBlank() }
    .take(128)
    .toSortedSet()
