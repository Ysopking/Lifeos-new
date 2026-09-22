package app.lifeos.core.creative

import app.lifeos.core.model.DocumentArgumentPlan
import app.lifeos.core.model.DocumentStructurePlan
import app.lifeos.core.model.StableCognitiveIds

data class DocumentParagraph(
    val sectionKey: String,
    val ordinal: Int,
    val sentenceFingerprints: List<String>,
    val claimIds: List<String>,
    val text: String,
    val fingerprint: String,
) {
    init {
        require(sectionKey.isNotBlank())
        require(ordinal >= 0)
        require(sentenceFingerprints.isNotEmpty())
        require(sentenceFingerprints == sentenceFingerprints.distinct())
        require(sentenceFingerprints.all { it.matches(SHA_256_B435) })
        require(claimIds.isNotEmpty())
        require(claimIds == claimIds.distinct())
        require(text.isNotBlank())
        require(
            fingerprint == paragraphFingerprint(
                sectionKey,
                ordinal,
                sentenceFingerprints,
                claimIds,
                text,
            )
        )
    }

    val sentenceMutationAuthority: Boolean get() = false
    val factualAdditionAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
    val citationAuthority: Boolean get() = false
}

data class ParagraphComposition(
    val argumentPlanFingerprint: String,
    val structureFingerprint: String,
    val paragraphs: List<DocumentParagraph>,
    val sentenceFingerprints: List<String>,
    val claimIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(argumentPlanFingerprint.matches(SHA_256_B435))
        require(structureFingerprint.matches(SHA_256_B435))
        require(paragraphs.isNotEmpty())
        require(paragraphs.map { it.ordinal } == paragraphs.indices.toList())
        require(sentenceFingerprints == paragraphs.flatMap { it.sentenceFingerprints })
        require(sentenceFingerprints == sentenceFingerprints.distinct())
        require(claimIds == paragraphs.flatMap { it.claimIds })
        require(claimIds == claimIds.distinct())
        require(
            fingerprint == compositionFingerprint(
                argumentPlanFingerprint,
                structureFingerprint,
                paragraphs,
                sentenceFingerprints,
                claimIds,
            )
        )
    }

    val sentenceMutationAuthority: Boolean get() = false
    val factualAdditionAuthority: Boolean get() = false
    val transitionAuthority: Boolean get() = false
    val artifactFinalizationAuthority: Boolean get() = false
}

/**
 * B435 composes exact B434 sentence carriers into section-bounded paragraphs.
 *
 * Composition is intentionally lossless: sentence text is never rewritten. Paragraph text is
 * exactly the ordered sentence texts joined by one ASCII space. B436 owns transitions/coherence.
 */
class ParagraphCompositionEngine {
    fun compose(
        structure: DocumentStructurePlan,
        argumentPlan: DocumentArgumentPlan,
        sentences: Collection<ClaimSentence>,
    ): ParagraphComposition {
        require(argumentPlan.structureFingerprint == structure.fingerprint) {
            "B435 argument plan does not match structure"
        }
        val canonicalByStep = sentences.associateBy { it.argumentStepFingerprint }
        require(canonicalByStep.size == sentences.size) {
            "B435 sentence input must be unique per argument step"
        }
        require(argumentPlan.steps.all { it.fingerprint in canonicalByStep }) {
            "B435 requires one B434 sentence for every argument step"
        }
        require(canonicalByStep.keys.all { key ->
            argumentPlan.steps.any { it.fingerprint == key }
        }) {
            "B435 refuses sentences outside the argument plan"
        }

        val paragraphs = structure.sections
            .sortedBy { it.ordinal }
            .mapIndexed { paragraphIndex, section ->
                val steps = argumentPlan.steps
                    .filter { it.sectionKey == section.sectionKey }
                    .sortedBy { it.ordinal }
                require(steps.isNotEmpty()) {
                    "B435 structure section has no argument steps"
                }
                val orderedSentences = steps.map { step ->
                    val sentence = canonicalByStep.getValue(step.fingerprint)
                    require(sentence.claimId == step.claimId)
                    require(sentence.claimFingerprint == step.claimFingerprint)
                    sentence
                }
                val sentenceFingerprints =
                    orderedSentences.map { it.fingerprint }
                val claims = orderedSentences.map { it.claimId }
                val text = orderedSentences.joinToString(" ") { it.text }
                DocumentParagraph(
                    sectionKey = section.sectionKey,
                    ordinal = paragraphIndex,
                    sentenceFingerprints = sentenceFingerprints,
                    claimIds = claims,
                    text = text,
                    fingerprint = paragraphFingerprint(
                        section.sectionKey,
                        paragraphIndex,
                        sentenceFingerprints,
                        claims,
                        text,
                    ),
                )
            }

        val sentenceFingerprints =
            paragraphs.flatMap { it.sentenceFingerprints }
        val claimIds = paragraphs.flatMap { it.claimIds }

        return ParagraphComposition(
            argumentPlanFingerprint = argumentPlan.fingerprint,
            structureFingerprint = structure.fingerprint,
            paragraphs = paragraphs,
            sentenceFingerprints = sentenceFingerprints,
            claimIds = claimIds,
            fingerprint = compositionFingerprint(
                argumentPlan.fingerprint,
                structure.fingerprint,
                paragraphs,
                sentenceFingerprints,
                claimIds,
            ),
        )
    }
}

private fun paragraphFingerprint(
    sectionKey: String,
    ordinal: Int,
    sentenceFingerprints: List<String>,
    claimIds: List<String>,
    text: String,
): String = StableCognitiveIds.fingerprint(
    "document-paragraph/v1",
    sectionKey,
    ordinal.toString(),
    sentenceFingerprints.joinToString("\u001f"),
    claimIds.joinToString("\u001f"),
    text,
)

private fun compositionFingerprint(
    argumentPlanFingerprint: String,
    structureFingerprint: String,
    paragraphs: List<DocumentParagraph>,
    sentenceFingerprints: List<String>,
    claimIds: List<String>,
): String = StableCognitiveIds.fingerprint(
    "paragraph-composition/v1",
    argumentPlanFingerprint,
    structureFingerprint,
    *paragraphs.map { it.fingerprint }.toTypedArray(),
    sentenceFingerprints.joinToString("\u001f"),
    claimIds.joinToString("\u001f"),
)

private val SHA_256_B435 = Regex("[0-9a-f]{64}")
