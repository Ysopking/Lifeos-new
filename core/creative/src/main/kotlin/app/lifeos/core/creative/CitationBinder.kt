package app.lifeos.core.creative

import app.lifeos.core.model.DocumentArgumentPlan
import app.lifeos.core.model.SemanticArtifactPlan
import app.lifeos.core.model.StableCognitiveIds

data class ClaimCitationBinding(
    val claimId: String,
    val claimFingerprint: String,
    val sentenceFingerprint: String,
    val paragraphFingerprint: String,
    val evidenceStableKeys: List<String>,
    val fingerprint: String,
) {
    init {
        require(claimId.isNotBlank())
        require(claimFingerprint.matches(SHA_256_B437))
        require(sentenceFingerprint.matches(SHA_256_B437))
        require(paragraphFingerprint.matches(SHA_256_B437))
        require(evidenceStableKeys.isNotEmpty())
        require(evidenceStableKeys == evidenceStableKeys.distinct().sorted())
        require(
            fingerprint == citationBindingFingerprint(
                claimId,
                claimFingerprint,
                sentenceFingerprint,
                paragraphFingerprint,
                evidenceStableKeys,
            )
        )
    }

    val sourceSelectionAuthority: Boolean get() = false
    val evidenceMutationAuthority: Boolean get() = false
    val factualAuthority: Boolean get() = false
}

data class CitationBindingPlan(
    val semanticPlanFingerprint: String,
    val argumentPlanFingerprint: String,
    val coherentDraftFingerprint: String,
    val bindings: List<ClaimCitationBinding>,
    val coveredClaimIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(semanticPlanFingerprint.matches(SHA_256_B437))
        require(argumentPlanFingerprint.matches(SHA_256_B437))
        require(coherentDraftFingerprint.matches(SHA_256_B437))
        require(bindings.isNotEmpty())
        require(bindings == bindings.distinctBy { it.claimId }.sortedBy { it.claimId })
        require(coveredClaimIds == bindings.map { it.claimId }.sorted())
        require(
            fingerprint == citationPlanFingerprint(
                semanticPlanFingerprint,
                argumentPlanFingerprint,
                coherentDraftFingerprint,
                bindings,
                coveredClaimIds,
            )
        )
    }

    val citationFabricationAuthority: Boolean get() = false
    val claimMutationAuthority: Boolean get() = false
    val artifactFinalizationAuthority: Boolean get() = false
}

/**
 * B437 binds each realized claim to the exact Photon revision evidence already carried by the
 * SemanticArtifactClaim. It neither searches for nor invents sources.
 *
 * A binding is positional metadata: exact claim -> exact B434 sentence -> exact B435 paragraph ->
 * exact evidence stable keys. Later renderers may format these bindings as citations.
 */
class CitationBinder {
    fun bind(
        semanticPlan: SemanticArtifactPlan,
        argumentPlan: DocumentArgumentPlan,
        sentences: Collection<ClaimSentence>,
        paragraphs: ParagraphComposition,
        coherentDraft: CoherentDocumentDraft,
    ): CitationBindingPlan {
        require(argumentPlan.semanticPlanFingerprint == semanticPlan.fingerprint)
        require(paragraphs.argumentPlanFingerprint == argumentPlan.fingerprint)
        require(coherentDraft.argumentPlanFingerprint == argumentPlan.fingerprint)
        require(coherentDraft.paragraphCompositionFingerprint == paragraphs.fingerprint)

        val sentenceByStep = sentences.associateBy { it.argumentStepFingerprint }
        require(sentenceByStep.size == sentences.size)
        require(argumentPlan.steps.all { it.fingerprint in sentenceByStep })
        require(sentenceByStep.keys.all { key ->
            argumentPlan.steps.any { it.fingerprint == key }
        })

        val paragraphBySentence = buildMap<String, DocumentParagraph> {
            paragraphs.paragraphs.forEach { paragraph ->
                paragraph.sentenceFingerprints.forEach { sentenceFingerprint ->
                    require(put(sentenceFingerprint, paragraph) == null) {
                        "B437 sentence may belong to only one paragraph"
                    }
                }
            }
        }

        val bindings = argumentPlan.steps.map { step ->
            val sentence = sentenceByStep.getValue(step.fingerprint)
            require(sentence.claimId == step.claimId)
            require(sentence.claimFingerprint == step.claimFingerprint)
            val claim = requireNotNull(semanticPlan.claim(step.claimId))
            require(step.claimId !in semanticPlan.unresolvedClaimIds)
            require(claim.fingerprint == sentence.claimFingerprint)
            val exactEvidence = claim.evidence
                .map { it.stableKey }
                .distinct()
                .sorted()
            require(exactEvidence == sentence.evidenceStableKeys) {
                "B437 B434 sentence evidence differs from SemanticArtifactClaim evidence"
            }
            val paragraph = requireNotNull(paragraphBySentence[sentence.fingerprint]) {
                "B437 sentence missing from paragraph composition"
            }
            ClaimCitationBinding(
                claimId = claim.claimId,
                claimFingerprint = claim.fingerprint,
                sentenceFingerprint = sentence.fingerprint,
                paragraphFingerprint = paragraph.fingerprint,
                evidenceStableKeys = exactEvidence,
                fingerprint = citationBindingFingerprint(
                    claim.claimId,
                    claim.fingerprint,
                    sentence.fingerprint,
                    paragraph.fingerprint,
                    exactEvidence,
                ),
            )
        }.sortedBy { it.claimId }

        val covered = bindings.map { it.claimId }.sorted()
        require(covered == argumentPlan.steps.map { it.claimId }.sorted()) {
            "B437 must bind every B433 claim exactly once"
        }

        return CitationBindingPlan(
            semanticPlanFingerprint = semanticPlan.fingerprint,
            argumentPlanFingerprint = argumentPlan.fingerprint,
            coherentDraftFingerprint = coherentDraft.fingerprint,
            bindings = bindings,
            coveredClaimIds = covered,
            fingerprint = citationPlanFingerprint(
                semanticPlan.fingerprint,
                argumentPlan.fingerprint,
                coherentDraft.fingerprint,
                bindings,
                covered,
            ),
        )
    }
}

private fun citationBindingFingerprint(
    claimId: String,
    claimFingerprint: String,
    sentenceFingerprint: String,
    paragraphFingerprint: String,
    evidenceStableKeys: List<String>,
): String = StableCognitiveIds.fingerprint(
    "claim-citation-binding/v1",
    claimId,
    claimFingerprint,
    sentenceFingerprint,
    paragraphFingerprint,
    evidenceStableKeys.joinToString("\u001f"),
)

private fun citationPlanFingerprint(
    semanticPlanFingerprint: String,
    argumentPlanFingerprint: String,
    coherentDraftFingerprint: String,
    bindings: List<ClaimCitationBinding>,
    coveredClaimIds: List<String>,
): String = StableCognitiveIds.fingerprint(
    "citation-binding-plan/v1",
    semanticPlanFingerprint,
    argumentPlanFingerprint,
    coherentDraftFingerprint,
    *bindings.map { it.fingerprint }.toTypedArray(),
    coveredClaimIds.joinToString("\u001f"),
)

private val SHA_256_B437 = Regex("[0-9a-f]{64}")
