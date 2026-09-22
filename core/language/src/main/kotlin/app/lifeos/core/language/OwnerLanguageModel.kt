package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds

enum class OwnerLanguageFeatureKind {
    LEXICAL_FORM,
    PHRASE_PATTERN,
    DISCOURSE_TRANSITION,
    REFERENCE_HABIT,
    PRAGMATIC_CUE,
    SEMANTIC_MAPPING,
}

data class OwnerLanguageFeatureEvidence(
    val candidateFingerprint: String,
    val featureKind: OwnerLanguageFeatureKind,
    val featureKey: String,
    val valueFingerprint: String,
    val supportingEpisodeFingerprints: List<String>,
    val sourceEvidenceFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(candidateFingerprint.matches(SHA_256_B430))
        require(featureKey.isNotBlank() && featureKey.length <= MAX_FEATURE_KEY_CHARS)
        require(valueFingerprint.matches(SHA_256_B430))
        require(supportingEpisodeFingerprints.isNotEmpty())
        require(
            supportingEpisodeFingerprints ==
                supportingEpisodeFingerprints.distinct().sorted()
        )
        require(
            supportingEpisodeFingerprints.all { it.matches(SHA_256_B430) }
        )
        require(sourceEvidenceFingerprint.matches(SHA_256_B430))
        require(
            fingerprint == ownerFeatureEvidenceFingerprint(
                candidateFingerprint,
                featureKind,
                featureKey,
                valueFingerprint,
                supportingEpisodeFingerprints,
                sourceEvidenceFingerprint,
            )
        )
    }

    companion object {
        fun create(
            candidateFingerprint: String,
            featureKind: OwnerLanguageFeatureKind,
            featureKey: String,
            valueFingerprint: String,
            supportingEpisodeFingerprints: Collection<String>,
            sourceEvidenceFingerprint: String,
        ): OwnerLanguageFeatureEvidence {
            val episodes = supportingEpisodeFingerprints.distinct().sorted()
            return OwnerLanguageFeatureEvidence(
                candidateFingerprint = candidateFingerprint,
                featureKind = featureKind,
                featureKey = featureKey.trim(),
                valueFingerprint = valueFingerprint,
                supportingEpisodeFingerprints = episodes,
                sourceEvidenceFingerprint = sourceEvidenceFingerprint,
                fingerprint = ownerFeatureEvidenceFingerprint(
                    candidateFingerprint,
                    featureKind,
                    featureKey.trim(),
                    valueFingerprint,
                    episodes,
                    sourceEvidenceFingerprint,
                ),
            )
        }
    }
}

data class OwnerLanguageFeature(
    val candidateFingerprint: String,
    val promotedRuleFingerprint: String,
    val featureKind: OwnerLanguageFeatureKind,
    val featureKey: String,
    val valueFingerprint: String,
    val supportingEpisodeFingerprints: List<String>,
    val evidenceFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(candidateFingerprint.matches(SHA_256_B430))
        require(promotedRuleFingerprint.matches(SHA_256_B430))
        require(valueFingerprint.matches(SHA_256_B430))
        require(evidenceFingerprint.matches(SHA_256_B430))
        require(
            fingerprint == ownerFeatureFingerprint(
                candidateFingerprint,
                promotedRuleFingerprint,
                featureKind,
                featureKey,
                valueFingerprint,
                supportingEpisodeFingerprints,
                evidenceFingerprint,
            )
        )
    }

    val truthAuthority: Boolean get() = false
    val preferenceExecutionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

data class OwnerLanguageModel(
    val languageRuleSnapshotRevision: Long,
    val languageRuleSnapshotFingerprint: String,
    val ownerRuleFingerprints: List<String>,
    val features: List<OwnerLanguageFeature>,
    val supportingEpisodeFingerprints: List<String>,
    val fingerprint: String,
) {
    init {
        require(languageRuleSnapshotRevision > 0L)
        require(languageRuleSnapshotFingerprint.matches(SHA_256_B430))
        require(ownerRuleFingerprints == ownerRuleFingerprints.distinct().sorted())
        require(features == features.distinctBy { it.fingerprint }.sortedBy { it.fingerprint })
        require(
            supportingEpisodeFingerprints ==
                supportingEpisodeFingerprints.distinct().sorted()
        )
        require(
            fingerprint == ownerLanguageModelFingerprint(
                languageRuleSnapshotRevision,
                languageRuleSnapshotFingerprint,
                ownerRuleFingerprints,
                features,
                supportingEpisodeFingerprints,
            )
        )
    }

    val truthAuthority: Boolean get() = false
    val grammarPromotionAuthority: Boolean get() = false
    val ownerPolicyAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    fun featuresOf(kind: OwnerLanguageFeatureKind): List<OwnerLanguageFeature> =
        features.filter { it.featureKind == kind }
}

/**
 * B430 builds a descriptive owner-language profile exclusively from B429-promoted OWNER_LANGUAGE
 * rules and exact supporting episode evidence.
 *
 * VERIFIED_GENERAL_LANGUAGE and EXTERNAL_LANGUAGE_OBSERVATION never enter this model. The model is
 * personalized evidence, not truth, preference execution, Owner Policy or external-action authority.
 */
class OwnerLanguageModelBuilder {
    fun build(
        snapshot: LanguageRuleSnapshot,
        evidence: Collection<OwnerLanguageFeatureEvidence>,
    ): OwnerLanguageModel {
        val ownerRules = snapshot.rules
            .filter { it.scope == LexicalLearningScope.OWNER_LANGUAGE }
            .sortedBy { it.candidateFingerprint }
        val ownerByCandidate = ownerRules.associateBy { it.candidateFingerprint }

        val canonicalEvidence = evidence
            .groupBy { it.fingerprint }
            .map { (_, same) ->
                require(same.all { it == same.first() }) {
                    "Conflicting B430 owner-language evidence identity"
                }
                same.first()
            }
            .sortedBy { it.fingerprint }

        require(
            canonicalEvidence.all { it.candidateFingerprint in ownerByCandidate }
        ) {
            "B430 accepts evidence only for promoted OWNER_LANGUAGE rules"
        }

        if (ownerRules.isNotEmpty()) {
            val covered = canonicalEvidence.map { it.candidateFingerprint }.toSet()
            require(ownerRules.all { it.candidateFingerprint in covered }) {
                "Every promoted owner-language rule requires exact feature evidence"
            }
        }

        val features = canonicalEvidence.map { item ->
            val rule = ownerByCandidate.getValue(item.candidateFingerprint)
            OwnerLanguageFeature(
                candidateFingerprint = item.candidateFingerprint,
                promotedRuleFingerprint = rule.fingerprint,
                featureKind = item.featureKind,
                featureKey = item.featureKey,
                valueFingerprint = item.valueFingerprint,
                supportingEpisodeFingerprints = item.supportingEpisodeFingerprints,
                evidenceFingerprint = item.fingerprint,
                fingerprint = ownerFeatureFingerprint(
                    item.candidateFingerprint,
                    rule.fingerprint,
                    item.featureKind,
                    item.featureKey,
                    item.valueFingerprint,
                    item.supportingEpisodeFingerprints,
                    item.fingerprint,
                ),
            )
        }.sortedBy { it.fingerprint }

        val ownerRuleFingerprints = ownerRules.map { it.fingerprint }.sorted()
        val supportingEpisodes = features
            .flatMap { it.supportingEpisodeFingerprints }
            .distinct()
            .sorted()

        return OwnerLanguageModel(
            languageRuleSnapshotRevision = snapshot.revision,
            languageRuleSnapshotFingerprint = snapshot.fingerprint,
            ownerRuleFingerprints = ownerRuleFingerprints,
            features = features,
            supportingEpisodeFingerprints = supportingEpisodes,
            fingerprint = ownerLanguageModelFingerprint(
                snapshot.revision,
                snapshot.fingerprint,
                ownerRuleFingerprints,
                features,
                supportingEpisodes,
            ),
        )
    }
}

private fun ownerFeatureEvidenceFingerprint(
    candidateFingerprint: String,
    featureKind: OwnerLanguageFeatureKind,
    featureKey: String,
    valueFingerprint: String,
    supportingEpisodeFingerprints: List<String>,
    sourceEvidenceFingerprint: String,
): String = StableCognitiveIds.fingerprint(
    "owner-language-feature-evidence/v1",
    candidateFingerprint,
    featureKind.name,
    featureKey,
    valueFingerprint,
    supportingEpisodeFingerprints.joinToString("\u001f"),
    sourceEvidenceFingerprint,
)

private fun ownerFeatureFingerprint(
    candidateFingerprint: String,
    promotedRuleFingerprint: String,
    featureKind: OwnerLanguageFeatureKind,
    featureKey: String,
    valueFingerprint: String,
    supportingEpisodeFingerprints: List<String>,
    evidenceFingerprint: String,
): String = StableCognitiveIds.fingerprint(
    "owner-language-feature/v1",
    candidateFingerprint,
    promotedRuleFingerprint,
    featureKind.name,
    featureKey,
    valueFingerprint,
    supportingEpisodeFingerprints.joinToString("\u001f"),
    evidenceFingerprint,
)

private fun ownerLanguageModelFingerprint(
    languageRuleSnapshotRevision: Long,
    languageRuleSnapshotFingerprint: String,
    ownerRuleFingerprints: List<String>,
    features: List<OwnerLanguageFeature>,
    supportingEpisodeFingerprints: List<String>,
): String = StableCognitiveIds.fingerprint(
    "owner-language-model/v1",
    languageRuleSnapshotRevision.toString(),
    languageRuleSnapshotFingerprint,
    ownerRuleFingerprints.joinToString("\u001f"),
    features.joinToString("\u001f") { it.fingerprint },
    supportingEpisodeFingerprints.joinToString("\u001f"),
)

private val SHA_256_B430 = Regex("[0-9a-f]{64}")
private const val MAX_FEATURE_KEY_CHARS = 160
