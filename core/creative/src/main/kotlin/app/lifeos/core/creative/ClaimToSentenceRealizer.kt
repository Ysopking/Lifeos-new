package app.lifeos.core.creative

import app.lifeos.core.model.DocumentArgumentPlan
import app.lifeos.core.model.DocumentArgumentStep
import app.lifeos.core.model.DocumentGoal
import app.lifeos.core.model.SemanticArtifactClaim
import app.lifeos.core.model.SemanticArtifactPlan
import app.lifeos.core.model.StableCognitiveIds

enum class ClaimSentenceRealizationMode {
    DIRECT,
}

data class ClaimSentence(
    val argumentStepFingerprint: String,
    val claimId: String,
    val claimFingerprint: String,
    val evidenceStableKeys: List<String>,
    val languageTag: String,
    val mode: ClaimSentenceRealizationMode,
    val canonicalClaimContent: String,
    val text: String,
    val fingerprint: String,
) {
    init {
        require(argumentStepFingerprint.matches(SHA_256_B434))
        require(claimId.isNotBlank())
        require(claimFingerprint.matches(SHA_256_B434))
        require(evidenceStableKeys.isNotEmpty())
        require(evidenceStableKeys == evidenceStableKeys.distinct().sorted())
        require(languageTag.isNotBlank())
        require(canonicalClaimContent.isNotBlank())
        require(text.isNotBlank())
        require(
            text == directSentence(canonicalClaimContent)
        ) {
            "B434 DIRECT realization may normalize whitespace and terminal punctuation only"
        }
        require(
            fingerprint == claimSentenceFingerprint(
                argumentStepFingerprint,
                claimId,
                claimFingerprint,
                evidenceStableKeys,
                languageTag,
                mode,
                canonicalClaimContent,
                text,
            )
        )
    }

    val factualAdditionAuthority: Boolean get() = false
    val claimMutationAuthority: Boolean get() = false
    val citationAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

/**
 * B434 converts one exact, resolved SemanticArtifactClaim into a sentence carrier.
 *
 * The first productive realizer is deliberately conservative: DIRECT mode can normalize
 * whitespace and add terminal punctuation, but it cannot paraphrase or add semantic content.
 * Later style/revision blocks may change surface form only after factual validation while the
 * claim/evidence lineage remains explicit.
 */
class ClaimToSentenceRealizer {
    fun realize(
        goal: DocumentGoal,
        argumentPlan: DocumentArgumentPlan,
        semanticPlan: SemanticArtifactPlan,
        step: DocumentArgumentStep,
        mode: ClaimSentenceRealizationMode = ClaimSentenceRealizationMode.DIRECT,
    ): ClaimSentence {
        require(goal.fingerprint == argumentPlan.documentGoalFingerprint) {
            "B434 argument plan does not belong to DocumentGoal"
        }
        require(goal.semanticPlanFingerprint == semanticPlan.fingerprint)
        require(argumentPlan.semanticPlanFingerprint == semanticPlan.fingerprint)
        require(
            argumentPlan.steps.any {
                it.fingerprint == step.fingerprint &&
                    it.claimId == step.claimId &&
                    it.claimFingerprint == step.claimFingerprint
            }
        ) {
            "B434 argument step is not part of supplied argument plan"
        }

        val claim = requireNotNull(semanticPlan.claim(step.claimId)) {
            "B434 claim missing from SemanticArtifactPlan"
        }
        require(step.claimFingerprint == claim.fingerprint) {
            "B434 claim fingerprint was substituted"
        }
        require(step.claimId !in semanticPlan.unresolvedClaimIds) {
            "B434 refuses unresolved claims"
        }
        require(step.claimId in goal.requiredClaimIds) {
            "B434 refuses claims outside DocumentGoal"
        }
        require(claim.evidence.isNotEmpty())

        val evidence = claim.evidence
            .map { it.stableKey }
            .distinct()
            .sorted()
        val text = when (mode) {
            ClaimSentenceRealizationMode.DIRECT ->
                directSentence(claim.canonicalContent)
        }

        return ClaimSentence(
            argumentStepFingerprint = step.fingerprint,
            claimId = claim.claimId,
            claimFingerprint = claim.fingerprint,
            evidenceStableKeys = evidence,
            languageTag = goal.languageTag,
            mode = mode,
            canonicalClaimContent = claim.canonicalContent,
            text = text,
            fingerprint = claimSentenceFingerprint(
                step.fingerprint,
                claim.claimId,
                claim.fingerprint,
                evidence,
                goal.languageTag,
                mode,
                claim.canonicalContent,
                text,
            ),
        )
    }
}

private fun directSentence(content: String): String {
    val normalized = content
        .trim()
        .replace(Regex("\\s+"), " ")
    require(normalized.isNotBlank())
    return if (normalized.last() in TERMINAL_PUNCTUATION_B434) {
        normalized
    } else {
        normalized + "."
    }
}

private fun claimSentenceFingerprint(
    argumentStepFingerprint: String,
    claimId: String,
    claimFingerprint: String,
    evidenceStableKeys: List<String>,
    languageTag: String,
    mode: ClaimSentenceRealizationMode,
    canonicalClaimContent: String,
    text: String,
): String = StableCognitiveIds.fingerprint(
    "claim-sentence/v1",
    argumentStepFingerprint,
    claimId,
    claimFingerprint,
    evidenceStableKeys.joinToString("\u001f"),
    languageTag,
    mode.name,
    canonicalClaimContent,
    text,
)

private val SHA_256_B434 = Regex("[0-9a-f]{64}")
private val TERMINAL_PUNCTUATION_B434 = setOf('.', '!', '?', ':', ';')
