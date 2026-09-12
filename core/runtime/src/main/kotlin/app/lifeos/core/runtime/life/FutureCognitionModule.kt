package app.lifeos.core.runtime.life

import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.CognitiveModule
import app.lifeos.core.runtime.CognitiveModuleDescriptor
import app.lifeos.core.runtime.CognitiveModuleProcessor
import app.lifeos.core.runtime.CognitiveModuleResult

object FutureCognitionModule {
    fun create(engine: FutureEvidenceEngine = FutureEvidenceEngine()): CognitiveModule = CognitiveModule(
        descriptor = CognitiveModuleDescriptor(
            identity = ModuleIdentity(
                moduleId = "future.evidence",
                version = "1",
                implementationHash = "future-evidence-v1-domain-fact-projection",
                capabilityIds = setOf("future.project", "lifeplan.candidate"),
            ),
            acceptedMimeTypes = setOf("application/vnd.lifeos.domain-fact+text"),
            preferredTags = setOf("structured-domain-evidence", "fact:deadline", "fact:debt", "fact:business_opportunity", "fact:knowledge_gap"),
            semanticHints = setOf("deadline", "debt", "risk", "opportunity", "knowledge", "gap", "question"),
            goalHints = setOf("goal", "plan", "future", "deadline"),
            expectedInformationGain = 0.9,
            estimatedCost = 0.2,
            baseAttraction = 0.05,
            minimumAttraction = 0.45,
            maxOutputs = 4,
        ),
        processor = CognitiveModuleProcessor { photon, _ ->
            val scenarios = engine.project(photon).take(4)
            CognitiveModuleResult(
                outputPhotons = scenarios.map { scenario -> scenario.toPhoton(photon) },
                explanation = "future-evidence-projection:${scenarios.size}",
            )
        },
    )

    private fun FutureEvidenceScenario.toPhoton(source: Photon): Photon = Photon(
        content = buildString {
            appendLine("scenario=$id")
            appendLine("type=${type.name}")
            appendLine("horizon=${horizon.name}")
            appendLine("probability=$probability")
            appendLine("resourceCost=$resourceCost")
            appendLine("allowed=$allowed")
            appendLine("delta=${stateDelta.entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name}:${it.value}" }}")
            append("explanation=$explanation")
        },
        mimeType = "application/vnd.lifeos.future-evidence+text",
        semanticMass = maxOf(source.semanticMass, probability),
        energy = source.energy,
        confidence = minOf(source.confidence, probability),
        provenance = Provenance(
            source = "future-evidence",
            actor = "lifeos",
            createdAt = source.provenance.createdAt,
            parentIds = sourcePhotonIds,
        ),
        tags = source.tags + setOf(
            "future-evidence",
            "future-horizon:${horizon.name.lowercase()}",
            "future-scenario:${type.name.lowercase()}",
            if (allowed) "future-actionable" else "future-observation",
        ),
    )
}
