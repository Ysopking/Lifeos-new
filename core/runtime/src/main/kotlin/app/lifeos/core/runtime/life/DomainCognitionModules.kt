package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.CognitiveModule
import app.lifeos.core.runtime.CognitiveModuleDescriptor
import app.lifeos.core.runtime.CognitiveModuleProcessor
import app.lifeos.core.runtime.CognitiveModuleResult
import app.lifeos.core.model.ModuleIdentity

/** Block E domain modules. They emit analysis Photons only; no external effect is executed here. */
object DomainCognitionModules {
    fun curiosity(): CognitiveModule = module(
        id = "curiosity",
        accepted = setOf("text/*"),
        requiredTags = setOf("question"),
        preferredTags = setOf("unknown", "research"),
        outputMime = "application/vnd.lifeos.research-proposal+text",
        outputTags = setOf("research-proposal", "non-activating", "deepsearch-candidate"),
        prefix = "Research proposal",
    )

    fun legal(): CognitiveModule = module(
        id = "legal",
        accepted = setOf("text/*", "application/vnd.lifeos.domain-note+text"),
        requiredTags = setOf("legal"),
        preferredTags = setOf("contract", "claim", "deadline"),
        outputMime = "application/vnd.lifeos.domain-note+text",
        outputTags = setOf("domain:legal", "needs-evidence"),
        prefix = "Legal analysis note",
    )

    fun debt(): CognitiveModule = module(
        id = "debt",
        accepted = setOf("text/*", "application/vnd.lifeos.domain-note+text"),
        requiredTags = setOf("debt"),
        preferredTags = setOf("finance", "invoice", "creditor"),
        outputMime = "application/vnd.lifeos.domain-note+text",
        outputTags = setOf("domain:debt", "finance", "business"),
        prefix = "Debt analysis note",
    )

    fun businessAdvisory(): CognitiveModule = module(
        id = "business-advisory",
        accepted = setOf("text/*", "application/vnd.lifeos.domain-note+text"),
        requiredTags = setOf("business"),
        preferredTags = setOf("finance", "strategy", "operations"),
        outputMime = "application/vnd.lifeos.domain-note+text",
        outputTags = setOf("domain:business-advisory", "decision-support"),
        prefix = "Business advisory note",
    )

    fun all(): List<CognitiveModule> = listOf(curiosity(), legal(), debt(), businessAdvisory())

    private fun module(
        id: String,
        accepted: Set<String>,
        requiredTags: Set<String>,
        preferredTags: Set<String>,
        outputMime: String,
        outputTags: Set<String>,
        prefix: String,
    ): CognitiveModule = CognitiveModule(
        descriptor = CognitiveModuleDescriptor(
            identity = ModuleIdentity("domain.$id", "1", "lifeos-domain-$id-v1"),
            acceptedMimeTypes = accepted,
            requiredTags = requiredTags,
            preferredTags = preferredTags,
            baseAttraction = 0.1,
            minimumAttraction = 0.25,
            maxOutputs = 2,
        ),
        processor = CognitiveModuleProcessor { photon, _ ->
            CognitiveModuleResult(
                outputPhotons = listOf(
                    Photon(
                        content = "$prefix\nsource=${photon.id.value}\ncontent=${photon.content.take(2048)}",
                        mimeType = outputMime,
                        semanticMass = photon.semanticMass,
                        energy = photon.energy,
                        confidence = photon.confidence,
                        provenance = Provenance(
                            source = "domain:$id",
                            actor = "lifeos",
                            createdAt = photon.provenance.createdAt,
                        ),
                        tags = photon.tags + outputTags,
                    )
                ),
                explanation = "deterministic-domain-analysis:$id",
            )
        },
    )
}
