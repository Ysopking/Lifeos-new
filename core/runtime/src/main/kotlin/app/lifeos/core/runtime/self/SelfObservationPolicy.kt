package app.lifeos.core.runtime.self

import java.time.Duration

data class SelfObservationPolicy(
    val stableInterval: Duration = Duration.ofSeconds(30),
    val activeInterval: Duration = Duration.ofSeconds(10),
    val degradedInterval: Duration = Duration.ofSeconds(1),
    val recoveringInterval: Duration = Duration.ofSeconds(1),
    val criticalInterval: Duration = Duration.ofSeconds(1),
) {
    init {
        listOf(stableInterval, activeInterval, degradedInterval, recoveringInterval, criticalInterval).forEach {
            require(!it.isNegative && !it.isZero) { "Self-observation intervals must be positive" }
        }
    }

    fun intervalFor(band: SelfObservationBand): Duration = when (band) {
        SelfObservationBand.STABLE -> stableInterval
        SelfObservationBand.ACTIVE -> activeInterval
        SelfObservationBand.DEGRADED -> degradedInterval
        SelfObservationBand.RECOVERING -> recoveringInterval
        SelfObservationBand.CRITICAL -> criticalInterval
    }
}
