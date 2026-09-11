package app.lifeos.core.runtime.health

import app.lifeos.core.field.StableFieldIds

sealed interface SelfHealingGenerationResolution {
    data class Ready(
        val incidentFingerprint: String,
        val generation: Int,
        val resumedExisting: Boolean,
    ) : SelfHealingGenerationResolution

    data class Suppressed(val reason: String) : SelfHealingGenerationResolution {
        init { require(reason.isNotBlank()) }
    }
}

class SelfHealingIncidentGenerationResolver(
    private val ledger: SelfHealingLedger,
) {
    suspend fun resolve(
        plan: RecoveryPlan,
        familyFingerprint: String,
        healthyObservedSinceStartup: Boolean,
    ): SelfHealingGenerationResolution {
        require(familyFingerprint.isNotBlank())
        for (generation in 1..MAX_GENERATIONS) {
            val fingerprint = generatedIncidentFingerprint(familyFingerprint, generation)
            val incidentId = SelfHealingIncidentId.create(
                plan.nodeId,
                fingerprint,
                plan.selfHealingFingerprint(),
            )
            val snapshot = ledger.snapshot(incidentId)
                ?: return SelfHealingGenerationResolution.Ready(
                    incidentFingerprint = fingerprint,
                    generation = generation,
                    resumedExisting = false,
                )
            if (!snapshot.terminal) {
                return SelfHealingGenerationResolution.Ready(
                    incidentFingerprint = fingerprint,
                    generation = generation,
                    resumedExisting = true,
                )
            }
            when (snapshot.state) {
                SelfHealingIncidentState.EXHAUSTED,
                SelfHealingIncidentState.QUARANTINED -> if (!healthyObservedSinceStartup) {
                    return SelfHealingGenerationResolution.Suppressed(
                        "terminal-self-healing-family:${snapshot.state.name.lowercase()}",
                    )
                }
                SelfHealingIncidentState.RECOVERED,
                SelfHealingIncidentState.BLOCKED -> Unit
                SelfHealingIncidentState.OPEN,
                SelfHealingIncidentState.ACTION_IN_FLIGHT -> error("Non-terminal incident reported as terminal")
            }
        }
        error("Self-healing incident generation limit exceeded")
    }

    private fun generatedIncidentFingerprint(familyFingerprint: String, generation: Int): String =
        StableFieldIds.fingerprint(
            "automatic-self-healing-generation/v1",
            familyFingerprint,
            generation.toString(),
        )

    private companion object {
        const val MAX_GENERATIONS = 10_000
    }
}
