package app.lifeos.next.kernel

import app.lifeos.core.runtime.life.SensorAttentionMode
import app.lifeos.core.runtime.life.SensorDescriptor
import app.lifeos.core.runtime.world.SensorAttentionCoverageProfile
import app.lifeos.core.runtime.world.WorldGap

/**
 * B485 process-local adapter from one concrete sensor bridge to the canonical B459 attention
 * scheduler. It carries scheduling/lifecycle callbacks only and cannot mint observation or effect
 * authority.
 */
internal class ProductiveSensorAttentionTarget(
    val descriptor: SensorDescriptor,
    val coverage: SensorAttentionCoverageProfile,
    val applyAttention: suspend (SensorAttentionMode) -> Unit,
    val updateWorldGaps: suspend (Collection<WorldGap>) -> Unit = {},
    val start: suspend () -> Unit = {},
    val stop: suspend () -> Unit = {},
) {
    init {
        require(coverage.sensorId == descriptor.sensorId) {
            "Productive sensor target coverage must bind the exact sensor descriptor"
        }
    }

    val observationGrantAuthority: Boolean
        get() = false

    val effectAuthority: Boolean
        get() = false
}
