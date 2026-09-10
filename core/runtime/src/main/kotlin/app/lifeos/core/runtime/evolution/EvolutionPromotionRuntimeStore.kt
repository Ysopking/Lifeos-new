package app.lifeos.core.runtime.evolution

import java.time.Instant

/**
 * Stronger J08 runtime boundary. Implementations must serialize canary budget reservations,
 * kill-switch state and promotion seals over one durable consistency domain.
 */
interface EvolutionPromotionRuntimeStore : EvolutionCanaryRuntimeStore {
    suspend fun sealForPromotion(
        adoptionEvidenceId: String,
        candidateToolId: String,
        readinessEvidenceId: String,
        expectedReservedInvocations: Int,
        sealedAt: Instant,
    ): EvolutionCanaryPromotionSealEvidence

    suspend fun promotionSeal(adoptionEvidenceId: String): EvolutionCanaryPromotionSealEvidence?
}
