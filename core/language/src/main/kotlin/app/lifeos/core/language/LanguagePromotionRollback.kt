package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds
import java.util.concurrent.atomic.AtomicReference

data class PromotedLanguageRule(
    val candidateKind: LanguageShadowCandidateKind,
    val candidateFingerprint: String,
    val scope: LexicalLearningScope,
    val shadowReportFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(candidateFingerprint.matches(SHA_256_B429))
        require(shadowReportFingerprint.matches(SHA_256_B429))
        require(scope != LexicalLearningScope.EXTERNAL_LANGUAGE_OBSERVATION) {
            "External language observations cannot become productive rules"
        }
        require(
            fingerprint == promotedRuleFingerprint(
                candidateKind,
                candidateFingerprint,
                scope,
                shadowReportFingerprint,
            )
        )
    }

    val executionAuthority: Boolean get() = false
    val ownerPolicyAuthority: Boolean get() = false

    companion object {
        fun from(report: LanguageShadowEvaluationReport): PromotedLanguageRule {
            require(report.passed) {
                "Only B428-passed language candidates may be promoted"
            }
            require(report.noNewExternalEffects) {
                "Promotion cannot introduce an executable external side effect"
            }
            require(report.scope != LexicalLearningScope.EXTERNAL_LANGUAGE_OBSERVATION) {
                "External language observations remain non-productive"
            }
            return PromotedLanguageRule(
                candidateKind = report.candidateKind,
                candidateFingerprint = report.candidateFingerprint,
                scope = report.scope,
                shadowReportFingerprint = report.fingerprint,
                fingerprint = promotedRuleFingerprint(
                    report.candidateKind,
                    report.candidateFingerprint,
                    report.scope,
                    report.fingerprint,
                ),
            )
        }
    }
}

data class LanguageRuleSnapshot private constructor(
    val revision: Long,
    val rules: List<PromotedLanguageRule>,
    val predecessorFingerprint: String?,
    val promotionEvidenceFingerprint: String?,
    val fingerprint: String,
) {
    init {
        require(revision > 0L)
        require(rules == rules.distinctBy { it.candidateFingerprint }.sortedBy { it.candidateFingerprint })
        require(predecessorFingerprint == null || predecessorFingerprint.matches(SHA_256_B429))
        require(
            promotionEvidenceFingerprint == null ||
                promotionEvidenceFingerprint.matches(SHA_256_B429)
        )
        require(
            fingerprint == languageRuleSnapshotFingerprint(
                revision,
                rules,
                predecessorFingerprint,
                promotionEvidenceFingerprint,
            )
        )
    }

    fun contains(candidateFingerprint: String): Boolean =
        rules.any { it.candidateFingerprint == candidateFingerprint }

    companion object {
        fun initial(): LanguageRuleSnapshot =
            create(
                revision = 1L,
                rules = emptyList(),
                predecessorFingerprint = null,
                promotionEvidenceFingerprint = null,
            )

        fun create(
            revision: Long,
            rules: Collection<PromotedLanguageRule>,
            predecessorFingerprint: String?,
            promotionEvidenceFingerprint: String?,
        ): LanguageRuleSnapshot {
            val canonical = rules
                .distinctBy { it.candidateFingerprint }
                .sortedBy { it.candidateFingerprint }
            return LanguageRuleSnapshot(
                revision = revision,
                rules = canonical,
                predecessorFingerprint = predecessorFingerprint,
                promotionEvidenceFingerprint = promotionEvidenceFingerprint,
                fingerprint = languageRuleSnapshotFingerprint(
                    revision,
                    canonical,
                    predecessorFingerprint,
                    promotionEvidenceFingerprint,
                ),
            )
        }
    }
}

sealed interface LanguageRulePromotionResult {
    data class Promoted(
        val snapshot: LanguageRuleSnapshot,
        val rule: PromotedLanguageRule,
    ) : LanguageRulePromotionResult

    data class AlreadyActive(
        val snapshot: LanguageRuleSnapshot,
        val candidateFingerprint: String,
    ) : LanguageRulePromotionResult

    data class Rejected(
        val reason: String,
    ) : LanguageRulePromotionResult {
        init {
            require(reason.isNotBlank())
        }
    }
}

data class LanguageRuleRollbackResult(
    val fromFingerprint: String,
    val targetFingerprint: String,
    val snapshot: LanguageRuleSnapshot,
) {
    init {
        require(fromFingerprint.matches(SHA_256_B429))
        require(targetFingerprint == snapshot.fingerprint)
    }

    val executionAuthority: Boolean get() = false
    val ownerPolicyAuthority: Boolean get() = false
}

/**
 * B429 owns the versioned promotion/rollback head for learned language rules.
 *
 * Promotion requires an exact passed B428 report and rejects EXTERNAL_LANGUAGE_OBSERVATION.
 * The registry is separate from external-effect authority: activating a language rule does not
 * grant Owner Policy, capability, permission or execution authority. Every previous snapshot
 * remains addressable for exact rollback.
 */
class VersionedLanguageRuleRuntime(
    initial: LanguageRuleSnapshot = LanguageRuleSnapshot.initial(),
) {
    private val active = AtomicReference(initial)
    private val history = linkedMapOf(initial.fingerprint to initial)

    fun current(): LanguageRuleSnapshot = active.get()

    @Synchronized
    fun promote(
        report: LanguageShadowEvaluationReport,
    ): LanguageRulePromotionResult {
        if (!report.passed) {
            return LanguageRulePromotionResult.Rejected("shadow-not-passed")
        }
        if (!report.noNewExternalEffects) {
            return LanguageRulePromotionResult.Rejected("new-external-effect")
        }
        if (report.scope == LexicalLearningScope.EXTERNAL_LANGUAGE_OBSERVATION) {
            return LanguageRulePromotionResult.Rejected("external-observation-not-promotable")
        }

        val current = active.get()
        if (current.contains(report.candidateFingerprint)) {
            return LanguageRulePromotionResult.AlreadyActive(
                snapshot = current,
                candidateFingerprint = report.candidateFingerprint,
            )
        }

        val rule = PromotedLanguageRule.from(report)
        val next = LanguageRuleSnapshot.create(
            revision = current.revision + 1L,
            rules = current.rules + rule,
            predecessorFingerprint = current.fingerprint,
            promotionEvidenceFingerprint = report.fingerprint,
        )
        history[next.fingerprint] = next
        active.set(next)
        return LanguageRulePromotionResult.Promoted(next, rule)
    }

    @Synchronized
    fun rollback(
        targetFingerprint: String,
    ): LanguageRuleRollbackResult {
        val current = active.get()
        val target = requireNotNull(history[targetFingerprint]) {
            "Unknown language-rule snapshot: $targetFingerprint"
        }
        active.set(target)
        return LanguageRuleRollbackResult(
            fromFingerprint = current.fingerprint,
            targetFingerprint = target.fingerprint,
            snapshot = target,
        )
    }

    fun knownSnapshot(fingerprint: String): LanguageRuleSnapshot? =
        synchronized(this) { history[fingerprint] }
}

private fun promotedRuleFingerprint(
    candidateKind: LanguageShadowCandidateKind,
    candidateFingerprint: String,
    scope: LexicalLearningScope,
    shadowReportFingerprint: String,
): String = StableCognitiveIds.fingerprint(
    "promoted-language-rule/v1",
    candidateKind.name,
    candidateFingerprint,
    scope.name,
    shadowReportFingerprint,
)

private fun languageRuleSnapshotFingerprint(
    revision: Long,
    rules: List<PromotedLanguageRule>,
    predecessorFingerprint: String?,
    promotionEvidenceFingerprint: String?,
): String = StableCognitiveIds.fingerprint(
    "language-rule-snapshot/v1",
    revision.toString(),
    predecessorFingerprint.orEmpty(),
    promotionEvidenceFingerprint.orEmpty(),
    *rules.map { it.fingerprint }.toTypedArray(),
)

private val SHA_256_B429 = Regex("[0-9a-f]{64}")
