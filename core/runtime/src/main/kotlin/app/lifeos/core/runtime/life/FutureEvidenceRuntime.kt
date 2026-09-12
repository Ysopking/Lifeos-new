package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.StableCognitiveIds

enum class FutureHorizon { HOUR, DAY, WEEK, MONTH, YEAR }

enum class FutureScenarioType { INACTION_PRESSURE, PREEMPTIVE_ACTION, OPPORTUNITY_ACTION, KNOWLEDGE_ACQUISITION }

data class FutureEvidenceScenario(
    val id: String,
    val type: FutureScenarioType,
    val horizon: FutureHorizon,
    val probability: Double,
    val stateDelta: Map<SeinDimension, Double>,
    val resourceCost: Double,
    val allowed: Boolean,
    val sourcePhotonIds: Set<PhotonId>,
    val explanation: String,
) {
    init {
        require(id.isNotBlank())
        require(probability.isFinite() && probability in 0.0..1.0)
        require(resourceCost.isFinite() && resourceCost in 0.0..1.0)
        require(stateDelta.values.all { it.isFinite() && it in -1.0..1.0 })
        require(sourcePhotonIds.isNotEmpty())
        require(explanation.isNotBlank())
    }

    fun toPlannerCandidate(): FutureDeltaCandidate = FutureDeltaCandidate(
        id = id,
        stateDelta = stateDelta.mapValues { (_, value) -> value * probability },
        resourceCost = resourceCost,
        allowed = allowed,
        explanation = explanation,
    )
}

/**
 * Converts present evidence into explicit candidate future evidence. The mapping is intentionally
 * deterministic and conservative: it produces hypotheses with probabilities, never future facts.
 */
class FutureEvidenceEngine {
    fun project(photon: Photon): List<FutureEvidenceScenario> {
        val kind = parseKind(photon) ?: return emptyList()
        val source = setOf(photon.id)
        return when (kind) {
            DomainFactKind.DEADLINE -> pressurePair(photon, kind, FutureHorizon.DAY, 0.9, source)
            DomainFactKind.CLAIM, DomainFactKind.DEBT -> pressurePair(photon, kind, FutureHorizon.WEEK, 0.82, source)
            DomainFactKind.BUSINESS_RISK -> pressurePair(photon, kind, FutureHorizon.MONTH, 0.68, source)
            DomainFactKind.BUSINESS_OPPORTUNITY -> listOf(
                scenario(
                    photon,
                    kind,
                    FutureScenarioType.OPPORTUNITY_ACTION,
                    FutureHorizon.MONTH,
                    0.62,
                    mapOf(SeinDimension.AGENCY to 0.14, SeinDimension.CONTINUITY to 0.08),
                    0.35,
                    true,
                    source,
                    "Opportunity evidence suggests evaluating an admissible action before the window changes.",
                )
            )
            DomainFactKind.KNOWLEDGE_GAP, DomainFactKind.QUESTION -> listOf(
                scenario(
                    photon,
                    kind,
                    FutureScenarioType.KNOWLEDGE_ACQUISITION,
                    FutureHorizon.DAY,
                    0.78,
                    mapOf(SeinDimension.PRESENT_COHERENCE to 0.10, SeinDimension.AGENCY to 0.05),
                    0.18,
                    true,
                    source,
                    "Resolving the knowledge gap can improve decision confidence before downstream action.",
                )
            )
            else -> emptyList()
        }
    }

    fun plannerCandidates(photons: Collection<Photon>): List<FutureDeltaCandidate> = photons
        .flatMap(::project)
        .filter { it.allowed }
        .associateBy { it.id }
        .values
        .sortedBy { it.id }
        .map(FutureEvidenceScenario::toPlannerCandidate)

    private fun pressurePair(
        photon: Photon,
        kind: DomainFactKind,
        horizon: FutureHorizon,
        probability: Double,
        source: Set<PhotonId>,
    ): List<FutureEvidenceScenario> = listOf(
        scenario(
            photon,
            kind,
            FutureScenarioType.INACTION_PRESSURE,
            horizon,
            probability,
            mapOf(SeinDimension.FRICTION to 0.16, SeinDimension.AGENCY to -0.08),
            0.0,
            false,
            source,
            "If unresolved, the present evidence can accumulate future pressure; this is a hypothesis, not an asserted outcome.",
        ),
        scenario(
            photon,
            kind,
            FutureScenarioType.PREEMPTIVE_ACTION,
            horizon,
            (probability * 0.9).coerceIn(0.0, 1.0),
            mapOf(SeinDimension.FRICTION to -0.12, SeinDimension.AGENCY to 0.10, SeinDimension.CONTINUITY to 0.04),
            0.22,
            true,
            source,
            "Preparing or resolving the issue earlier is a candidate way to shift expected future load into a controlled present action.",
        ),
    )

    private fun scenario(
        photon: Photon,
        kind: DomainFactKind,
        type: FutureScenarioType,
        horizon: FutureHorizon,
        probability: Double,
        delta: Map<SeinDimension, Double>,
        cost: Double,
        allowed: Boolean,
        source: Set<PhotonId>,
        explanation: String,
    ): FutureEvidenceScenario {
        val id = "future-" + StableCognitiveIds.fingerprint(
            "future-evidence/v1",
            photon.id.value,
            photon.revision.toString(),
            kind.name,
            type.name,
            horizon.name,
        )
        return FutureEvidenceScenario(id, type, horizon, probability, delta, cost, allowed, source, explanation)
    }

    private fun parseKind(photon: Photon): DomainFactKind? {
        val raw = photon.content.lineSequence()
            .firstOrNull { it.startsWith("kind=") }
            ?.substringAfter("kind=")
            ?: photon.tags.firstOrNull { it.startsWith("fact:") }?.substringAfter("fact:")?.uppercase()
            ?: return null
        return runCatching { DomainFactKind.valueOf(raw.trim().uppercase()) }.getOrNull()
    }
}
