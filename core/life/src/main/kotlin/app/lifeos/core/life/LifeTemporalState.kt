package app.lifeos.core.life

import java.time.Instant

data class LifeTemporalState(
    val observedAt: Instant?,
    val effectiveFrom: Instant?,
    val effectiveUntil: Instant?,
    val dueAt: Instant?,
) {
    init {
        if (effectiveFrom != null && effectiveUntil != null) {
            require(!effectiveUntil.isBefore(effectiveFrom))
        }
    }
}
