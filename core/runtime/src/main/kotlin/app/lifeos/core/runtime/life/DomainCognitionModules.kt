package app.lifeos.core.runtime.life

import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.CognitiveModule
import app.lifeos.core.runtime.CognitiveModuleDescriptor
import app.lifeos.core.runtime.CognitiveModuleProcessor
import app.lifeos.core.runtime.CognitiveModuleResult

/** Block E domain modules. They emit evidence-linked analysis Photons only; no external effect is executed here. */
object DomainCognitionModules {
    fun curiosity(): CognitiveModule = module(
        id = "curiosity",
        semanticHints = setOf("warum", "wieso", "wie", "unklar", "fehlt", "recherche", "wissen", "question", "unknown"),
        preferredTags = setOf("question", "unknown", "research"),
        outputTags = setOf("research-proposal", "non-activating", "deepsearch-candidate"),
        extractor = DomainEvidenceExtractor::curiosity,
        informationGain = 0.95,
        cost = 0.25,
    )

    fun legal(): CognitiveModule = module(
        id = "legal",
        semanticHints = setOf("vertrag", "recht", "gesetz", "kündigung", "mahnung", "anspruch", "klage", "gericht", "haftung", "frist"),
        preferredTags = setOf("legal", "contract", "claim", "deadline"),
        outputTags = setOf("domain:legal", "needs-evidence"),
        extractor = DomainEvidenceExtractor::legal,
        informationGain = 0.8,
        cost = 0.35,
    )

    fun debt(): CognitiveModule = module(
        id = "debt",
        semanticHints = setOf("schuld", "schulden", "forderung", "mahnung", "rechnung", "gläubiger", "inkasso", "zahlung", "rate", "kredit"),
        preferredTags = setOf("debt", "finance", "invoice", "creditor", "claim"),
        outputTags = setOf("domain:debt", "finance", "business", "needs-evidence"),
        extractor = DomainEvidenceExtractor::debt,
        informationGain = 0.85,
        cost = 0.3,
    )

    fun businessAdvisory(): CognitiveModule = module(
        id = "business-advisory",
        semanticHints = setOf("geschäft", "business", "umsatz", "marge", "gewinn", "cashflow", "kunde", "markt", "strategie", "kosten", "chance", "risiko"),
        preferredTags = setOf("business", "finance", "strategy", "operations", "opportunity"),
        outputTags = setOf("domain:business-advisory", "decision-support", "needs-evidence"),
        extractor = DomainEvidenceExtractor::business,
        informationGain = 0.8,
        cost = 0.4,
    )

    fun all(): List<CognitiveModule> = listOf(curiosity(), legal(), debt(), businessAdvisory())

    private fun module(
        id: String,
        semanticHints: Set<String>,
        preferredTags: Set<String>,
        outputTags: Set<String>,
        extractor: (Photon) -> List<DomainFact>,
        informationGain: Double,
        cost: Double,
    ): CognitiveModule = CognitiveModule(
        descriptor = CognitiveModuleDescriptor(
            identity = ModuleIdentity(
                moduleId = "domain.$id",
                version = "2",
                implementationHash = "lifeos-domain-$id-v2-structured-evidence",
                capabilityIds = setOf("domain.analyze.$id"),
            ),
            acceptedMimeTypes = setOf("text/*", "application/vnd.lifeos.domain-fact+text", "application/vnd.lifeos.domain-note+text"),
            preferredTags = preferredTags,
            semanticHints = semanticHints,
            expectedInformationGain = informationGain,
            estimatedCost = cost,
            baseAttraction = 0.03,
            minimumAttraction = 0.48,
            maxOutputs = 8,
        ),
        processor = CognitiveModuleProcessor { photon, _ ->
            val facts = extractor(photon).take(8)
            CognitiveModuleResult(
                outputPhotons = facts.map { fact ->
                    Photon(
                        content = buildString {
                            appendLine("kind=${fact.kind.name}")
                            appendLine("value=${fact.value}")
                            appendLine("confidence=${fact.confidence}")
                            append("evidence=${fact.evidence}")
                        },
                        mimeType = "application/vnd.lifeos.domain-fact+text",
                        semanticMass = maxOf(photon.semanticMass, fact.confidence),
                        energy = photon.energy,
                        confidence = minOf(photon.confidence, fact.confidence),
                        provenance = Provenance(
                            source = "domain:$id",
                            actor = "lifeos",
                            createdAt = photon.provenance.createdAt,
                        ),
                        tags = photon.tags + outputTags + setOf(
                            "domain:$id",
                            "fact:${fact.kind.name.lowercase()}",
                            "structured-domain-evidence",
                        ),
                    )
                },
                explanation = "structured-domain-evidence:$id:${facts.size}",
            )
        },
    )
}
