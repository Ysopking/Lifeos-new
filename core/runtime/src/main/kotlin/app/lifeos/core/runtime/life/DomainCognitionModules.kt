package app.lifeos.core.runtime.life

import app.lifeos.core.model.CognitiveBranchSemanticOutcome
import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.runtime.CognitiveModule
import app.lifeos.core.runtime.CognitiveModuleDescriptor
import app.lifeos.core.runtime.CognitiveModuleProcessor
import app.lifeos.core.runtime.CognitiveModuleResult

/** Domain modules emit evidence-linked analysis Photons only; no external effect is executed here. */
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
    ): CognitiveModule {
        val identity = ModuleIdentity(
            moduleId = "domain.$id",
            version = "3",
            implementationHash = "lifeos-domain-$id-v3-stable-evidence-assertions",
            capabilityIds = setOf("domain.analyze.$id"),
        )
        return CognitiveModule(
            descriptor = CognitiveModuleDescriptor(
                identity = identity,
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
                val assertions = extractor(photon)
                    .take(8)
                    .map { fact -> DomainEvidenceIdentity.assertion(photon, fact, identity) }
                val semanticOutcome = when {
                    assertions.isEmpty() -> CognitiveBranchSemanticOutcome.IRRELEVANT
                    assertions.any { it.stance == DomainEvidenceStance.UNCERTAIN } ->
                        CognitiveBranchSemanticOutcome.UNCERTAIN
                    assertions.map { it.stance }.distinct().size > 1 ->
                        CognitiveBranchSemanticOutcome.UNCERTAIN
                    assertions.all { it.stance == DomainEvidenceStance.CONTRADICTS } ->
                        CognitiveBranchSemanticOutcome.CONTRADICTED
                    assertions.all { it.stance == DomainEvidenceStance.SUPPORTS } ->
                        CognitiveBranchSemanticOutcome.SUPPORTED
                    else -> CognitiveBranchSemanticOutcome.UNCERTAIN
                }
                CognitiveModuleResult(
                    outputPhotons = assertions.map { assertion ->
                        Photon(
                            content = buildString {
                                appendLine("fact_id=${assertion.factId}")
                                appendLine("interpretation_id=${assertion.interpretationId}")
                                appendLine("evidence_fingerprint=${assertion.evidenceFingerprint}")
                                appendLine("source_state_hash=${assertion.sourceStateHash.value}")
                                appendLine("producer_module=${assertion.producerModuleId}")
                                appendLine("producer_version=${assertion.producerModuleVersion}")
                                appendLine("producer_fingerprint=${assertion.producerModuleFingerprint}")
                                appendLine("kind=${assertion.kind.name}")
                                appendLine("value=${assertion.normalizedValue}")
                                appendLine("stance=${assertion.stance.name}")
                                appendLine("confidence=${assertion.confidence}")
                                append("evidence_span=${assertion.evidenceSpan}")
                            },
                            mimeType = "application/vnd.lifeos.domain-fact+text",
                            semanticMass = maxOf(photon.semanticMass, assertion.confidence),
                            energy = photon.energy,
                            confidence = minOf(photon.confidence, assertion.confidence),
                            provenance = Provenance(
                                source = "domain:$id",
                                actor = "lifeos",
                                createdAt = photon.provenance.createdAt,
                                parentIds = setOf(photon.id),
                            ),
                            relations = setOf(
                                PhotonRelation(
                                    target = photon.id,
                                    type = when (assertion.stance) {
                                        DomainEvidenceStance.SUPPORTS -> RelationType.SUPPORTS
                                        DomainEvidenceStance.CONTRADICTS -> RelationType.CONTRADICTS
                                        DomainEvidenceStance.UNCERTAIN -> RelationType.REFERENCES
                                    },
                                )
                            ),
                            tags = photon.tags + outputTags + setOf(
                                "domain:$id",
                                "fact:${assertion.kind.name.lowercase()}",
                                "fact-id:${assertion.factId}",
                                "interpretation-id:${assertion.interpretationId}",
                                "evidence:${assertion.evidenceFingerprint}",
                                "stance:${assertion.stance.name.lowercase()}",
                                "module-version:${assertion.producerModuleVersion}",
                                "structured-domain-evidence",
                            ),
                        )
                    },
                    explanation = "stable-domain-evidence:$id:${assertions.size}",
                    semanticOutcome = semanticOutcome,
                )
            },
        )
    }
}
