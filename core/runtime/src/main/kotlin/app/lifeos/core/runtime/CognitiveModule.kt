package app.lifeos.core.runtime

import app.lifeos.core.model.CognitiveBranchSemanticOutcome
import app.lifeos.core.model.DeterminismContext
import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.Photon

/**
 * Declarative attraction profile for one cognition-producing module.
 * Empty acceptedMimeTypes means the module can inspect any MIME type.
 */
data class CognitiveModuleDescriptor(
    val identity: ModuleIdentity,
    val acceptedMimeTypes: Set<String> = emptySet(),
    val preferredTags: Set<String> = emptySet(),
    val requiredTags: Set<String> = emptySet(),
    val semanticHints: Set<String> = emptySet(),
    val goalHints: Set<String> = emptySet(),
    val expectedInformationGain: Double = 0.0,
    val estimatedCost: Double = 0.0,
    val baseAttraction: Double = 0.1,
    val minimumAttraction: Double = 0.25,
    val maxOutputs: Int = 16,
) {
    init {
        require(acceptedMimeTypes.none { it.isBlank() }) { "Accepted MIME types must not be blank" }
        require(preferredTags.none { it.isBlank() }) { "Preferred tags must not be blank" }
        require(requiredTags.none { it.isBlank() }) { "Required tags must not be blank" }
        require(semanticHints.none { it.isBlank() }) { "Semantic hints must not be blank" }
        require(goalHints.none { it.isBlank() }) { "Goal hints must not be blank" }
        require(expectedInformationGain.isFinite() && expectedInformationGain in 0.0..1.0) {
            "expectedInformationGain must be in 0..1"
        }
        require(estimatedCost.isFinite() && estimatedCost in 0.0..1.0) {
            "estimatedCost must be in 0..1"
        }
        require(baseAttraction in 0.0..1.0) { "Base attraction must be in 0..1" }
        require(minimumAttraction in 0.0..1.0) { "Minimum attraction must be in 0..1" }
        require(maxOutputs in 0..256) { "maxOutputs must be in 0..256" }
    }
}

data class CognitiveModuleResult(
    val influences: List<FieldInfluence> = emptyList(),
    val outputPhotons: List<Photon> = emptyList(),
    val explanation: String = "",
    val semanticOutcome: CognitiveBranchSemanticOutcome = CognitiveBranchSemanticOutcome.UNSPECIFIED,
) {
    init {
        require(explanation.length <= 16_384) { "Module explanation is too large" }
        require(
            semanticOutcome != CognitiveBranchSemanticOutcome.IRRELEVANT || outputPhotons.isEmpty()
        ) { "An irrelevant branch must not emit derived photons" }
    }
}

fun interface CognitiveModuleProcessor {
    suspend fun process(photon: Photon, context: DeterminismContext): CognitiveModuleResult
}

data class CognitiveModule(
    val descriptor: CognitiveModuleDescriptor,
    val processor: CognitiveModuleProcessor,
) {
    companion object {
        /** Transitional adapter for existing ForceField implementations. */
        fun fromForceField(
            descriptor: CognitiveModuleDescriptor,
            field: ForceField,
        ): CognitiveModule = CognitiveModule(
            descriptor = descriptor,
            processor = CognitiveModuleProcessor { photon, _ ->
                val influence = field.influence(photon)
                CognitiveModuleResult(
                    influences = listOfNotNull(influence),
                    explanation = "legacy-force-field-adapter",
                )
            },
        )
    }
}
