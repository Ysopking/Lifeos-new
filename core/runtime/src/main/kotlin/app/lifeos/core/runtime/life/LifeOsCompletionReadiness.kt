package app.lifeos.core.runtime.life

import app.lifeos.core.model.StableCognitiveIds

enum class LifeOsBlock { A, B, C, D, E, F, G, H }

enum class ReadinessState { READY, DEGRADED, BLOCKED }

data class BlockReadiness(
    val block: LifeOsBlock,
    val state: ReadinessState,
    val detail: String,
) {
    init { require(detail.isNotBlank()) }
}

data class LifeOsReadinessSnapshot(
    val blocks: List<BlockReadiness>,
    val fingerprint: String,
) {
    val complete: Boolean get() = blocks.size == LifeOsBlock.entries.size && blocks.all { it.state == ReadinessState.READY }
}

enum class ChaosScenario {
    COLD_RESTART,
    DUPLICATE_INPUT,
    MISSING_PARENT,
    PROVIDER_UNAVAILABLE,
    RESOURCE_PRESSURE,
    GENERATED_TOOL_QUARANTINE,
}

data class ChaosProbeResult(
    val scenario: ChaosScenario,
    val contained: Boolean,
    val detail: String,
)

data class ChaosVerificationReport(
    val probes: List<ChaosProbeResult>,
) {
    val passed: Boolean get() = probes.isNotEmpty() && probes.all { it.contained }
}

class LifeOsCompletionReadiness {
    fun snapshot(
        blockStates: Map<LifeOsBlock, ReadinessState>,
        details: Map<LifeOsBlock, String> = emptyMap(),
    ): LifeOsReadinessSnapshot {
        val blocks = LifeOsBlock.entries.map { block ->
            BlockReadiness(
                block = block,
                state = blockStates[block] ?: ReadinessState.BLOCKED,
                detail = details[block] ?: "no-evidence",
            )
        }
        return LifeOsReadinessSnapshot(
            blocks = blocks,
            fingerprint = StableCognitiveIds.fingerprint(
                "lifeos-a-h-readiness/v1",
                *blocks.flatMap { listOf(it.block.name, it.state.name, it.detail) }.toTypedArray(),
            ),
        )
    }
}

class LifeOsChaosVerifier {
    fun verify(results: Collection<ChaosProbeResult>): ChaosVerificationReport {
        val canonical = results
            .associateBy { it.scenario }
            .values
            .sortedBy { it.scenario.name }
        return ChaosVerificationReport(canonical)
    }
}
