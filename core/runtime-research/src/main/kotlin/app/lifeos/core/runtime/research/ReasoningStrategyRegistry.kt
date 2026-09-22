package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.level7.EvidenceActionKind
import app.lifeos.core.runtime.reasoning.KnowledgeGap
import app.lifeos.core.runtime.reasoning.KnowledgeGapKind
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class ReasoningStrategyId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[a-z0-9][a-z0-9-]{1,63}")))
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "reasoning-strategy:"
    }
}

enum class ReasoningStrategyKind {
    STRUCTURAL_HYPOTHESIS_SEARCH,
    COUNTERFACTUAL_SIMULATION,
    SAFE_EXPERIMENT_DESIGN,
    RECURSIVE_RESEARCH,
    ACTIVE_EVIDENCE_SELECTION,
    EXPLICIT_ABSTENTION,
}

enum class ReasoningStrategyExecutionClass {
    PURE_REASONING,
    SIMULATION,
    EVIDENCE_PLANNING,
    EXPLICIT_NON_ACTION,
}

data class ReasoningStrategyDescriptor(
    val id: ReasoningStrategyId,
    val version: String,
    val kind: ReasoningStrategyKind,
    val executionClass: ReasoningStrategyExecutionClass,
    val applicableGapKinds: List<KnowledgeGapKind>,
    val requiredEvidenceKinds: List<EvidenceActionKind>,
    val requiresExternalObservation: Boolean,
    val fingerprint: String,
) {
    init {
        require(version.isNotBlank())
        require(applicableGapKinds.isNotEmpty())
        require(applicableGapKinds == applicableGapKinds.distinct().sortedBy { it.name })
        require(requiredEvidenceKinds == requiredEvidenceKinds.distinct().sortedBy { it.name })
        require(
            fingerprint == descriptorFingerprint(
                id = id,
                version = version,
                kind = kind,
                executionClass = executionClass,
                applicableGapKinds = applicableGapKinds,
                requiredEvidenceKinds = requiredEvidenceKinds,
                requiresExternalObservation = requiresExternalObservation,
            )
        ) {
            "Reasoning strategy descriptor fingerprint/content mismatch"
        }
    }

    val executionAuthority: Boolean
        get() = false

    val promotionAuthority: Boolean
        get() = false

    val worldMutationAuthority: Boolean
        get() = false

    fun compatibleWith(gap: KnowledgeGap): Boolean =
        gap.kind in applicableGapKinds &&
            requiredEvidenceKinds.all(gap.recommendedEvidenceKinds::contains)

    companion object {
        fun create(
            id: String,
            version: String,
            kind: ReasoningStrategyKind,
            executionClass: ReasoningStrategyExecutionClass,
            applicableGapKinds: Collection<KnowledgeGapKind>,
            requiredEvidenceKinds: Collection<EvidenceActionKind> = emptyList(),
            requiresExternalObservation: Boolean = false,
        ): ReasoningStrategyDescriptor {
            val strategyId = ReasoningStrategyId(ReasoningStrategyId.PREFIX + id)
            val gaps = applicableGapKinds.distinct().sortedBy { it.name }
            val evidence = requiredEvidenceKinds.distinct().sortedBy { it.name }
            return ReasoningStrategyDescriptor(
                id = strategyId,
                version = version,
                kind = kind,
                executionClass = executionClass,
                applicableGapKinds = gaps,
                requiredEvidenceKinds = evidence,
                requiresExternalObservation = requiresExternalObservation,
                fingerprint = descriptorFingerprint(
                    id = strategyId,
                    version = version,
                    kind = kind,
                    executionClass = executionClass,
                    applicableGapKinds = gaps,
                    requiredEvidenceKinds = evidence,
                    requiresExternalObservation = requiresExternalObservation,
                ),
            )
        }
    }
}

/**
 * B383 immutable catalog of reasoning methods.
 *
 * This registry does not learn scores, rank strategies, execute a strategy, activate a learned
 * Level7 action strategy, or mutate Controlled Evolution. B384 may bind verified performance to
 * the exact pair of strategy id + descriptor fingerprint.
 */
class ReasoningStrategyRegistry(
    descriptors: Collection<ReasoningStrategyDescriptor> = builtInDescriptors(),
) {
    private val canonical: List<ReasoningStrategyDescriptor>

    init {
        require(descriptors.isNotEmpty())
        val grouped = descriptors.groupBy { it.id }
        require(grouped.values.all { it.size == 1 }) {
            "Reasoning strategy ids must be unique"
        }
        canonical = descriptors.sortedBy { it.id.value }
    }

    fun all(): List<ReasoningStrategyDescriptor> = canonical

    fun descriptor(id: ReasoningStrategyId): ReasoningStrategyDescriptor? =
        canonical.firstOrNull { it.id == id }

    fun compatibleWith(gap: KnowledgeGap): List<ReasoningStrategyDescriptor> =
        canonical.filter { it.compatibleWith(gap) }

    fun fingerprint(): String = registryFingerprint(
        "reasoning-strategy-registry/v1",
        *canonical.flatMap {
            listOf(it.id.value, it.fingerprint)
        }.toTypedArray(),
    )

    val selectionAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun builtInDescriptors(): List<ReasoningStrategyDescriptor> = listOf(
            ReasoningStrategyDescriptor.create(
                id = "structural-hypothesis-search",
                version = "b368/v1",
                kind = ReasoningStrategyKind.STRUCTURAL_HYPOTHESIS_SEARCH,
                executionClass = ReasoningStrategyExecutionClass.PURE_REASONING,
                applicableGapKinds = listOf(
                    KnowledgeGapKind.SEARCH_TRUNCATED,
                    KnowledgeGapKind.NO_COMPLETE_REASONING_STATE,
                ),
            ),
            ReasoningStrategyDescriptor.create(
                id = "counterfactual-simulation",
                version = "b369/v1",
                kind = ReasoningStrategyKind.COUNTERFACTUAL_SIMULATION,
                executionClass = ReasoningStrategyExecutionClass.SIMULATION,
                applicableGapKinds = listOf(
                    KnowledgeGapKind.SEARCH_TRUNCATED,
                    KnowledgeGapKind.NO_COMPLETE_REASONING_STATE,
                    KnowledgeGapKind.VERIFIED_FAILURE_PATTERN,
                ),
                requiredEvidenceKinds = listOf(EvidenceActionKind.SIMULATION),
            ),
            ReasoningStrategyDescriptor.create(
                id = "safe-experiment-design",
                version = "b370/v1",
                kind = ReasoningStrategyKind.SAFE_EXPERIMENT_DESIGN,
                executionClass = ReasoningStrategyExecutionClass.EVIDENCE_PLANNING,
                applicableGapKinds = listOf(
                    KnowledgeGapKind.NO_COMPLETE_REASONING_STATE,
                    KnowledgeGapKind.VERIFIED_FAILURE_PATTERN,
                ),
                requiredEvidenceKinds = listOf(EvidenceActionKind.SAFE_SANDBOX_EXPERIMENT),
                requiresExternalObservation = true,
            ),
            ReasoningStrategyDescriptor.create(
                id = "recursive-research",
                version = "b399/v1",
                kind = ReasoningStrategyKind.RECURSIVE_RESEARCH,
                executionClass = ReasoningStrategyExecutionClass.EVIDENCE_PLANNING,
                applicableGapKinds = listOf(
                    KnowledgeGapKind.EXPLICIT_UNKNOWN,
                    KnowledgeGapKind.NO_COMPLETE_REASONING_STATE,
                ),
                requiredEvidenceKinds = listOf(EvidenceActionKind.DEEP_SEARCH),
                requiresExternalObservation = true,
            ),
            ReasoningStrategyDescriptor.create(
                id = "active-evidence-selection",
                version = "level7-active-evidence/v1",
                kind = ReasoningStrategyKind.ACTIVE_EVIDENCE_SELECTION,
                executionClass = ReasoningStrategyExecutionClass.EVIDENCE_PLANNING,
                applicableGapKinds = KnowledgeGapKind.entries,
            ),
            ReasoningStrategyDescriptor.create(
                id = "explicit-abstention",
                version = "b383/v1",
                kind = ReasoningStrategyKind.EXPLICIT_ABSTENTION,
                executionClass = ReasoningStrategyExecutionClass.EXPLICIT_NON_ACTION,
                applicableGapKinds = listOf(
                    KnowledgeGapKind.SEARCH_TRUNCATED,
                    KnowledgeGapKind.NO_COMPLETE_REASONING_STATE,
                ),
                requiredEvidenceKinds = listOf(EvidenceActionKind.ABSTAIN),
            ),
        ).sortedBy { it.id.value }
    }
}

private fun descriptorFingerprint(
    id: ReasoningStrategyId,
    version: String,
    kind: ReasoningStrategyKind,
    executionClass: ReasoningStrategyExecutionClass,
    applicableGapKinds: List<KnowledgeGapKind>,
    requiredEvidenceKinds: List<EvidenceActionKind>,
    requiresExternalObservation: Boolean,
): String = registryFingerprint(
    "reasoning-strategy-descriptor/v1",
    id.value,
    version,
    kind.name,
    executionClass.name,
    requiresExternalObservation.toString(),
    *applicableGapKinds.map { "gap:" + it.name }.toTypedArray(),
    *requiredEvidenceKinds.map { "evidence:" + it.name }.toTypedArray(),
)

private fun registryFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            )
        )
        digest.update(bytes)
    }
    update(domain)
    parts.forEach(::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
