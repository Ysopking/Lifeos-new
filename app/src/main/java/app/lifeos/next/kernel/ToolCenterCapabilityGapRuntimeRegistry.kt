package app.lifeos.next.kernel

import app.lifeos.core.runtime.capability.CapabilityGap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-level read-only projection of the latest productive blocking capability gaps.
 *
 * Authority remains in the language/capability router and the kernel owner-action gates. This
 * registry only lets the Tool Center observe the exact gaps produced by that runtime path.
 */
object ToolCenterCapabilityGapRuntimeRegistry {
    private val mutableGaps = MutableStateFlow<List<CapabilityGap>>(emptyList())
    val gaps: StateFlow<List<CapabilityGap>> = mutableGaps.asStateFlow()

    fun publish(gaps: List<CapabilityGap>) {
        mutableGaps.value = gaps
            .distinctBy { gap -> gap.requirement.capabilityId.value to gap.type }
            .sortedWith(
                compareBy<CapabilityGap>({ it.requirement.capabilityId.value }, { it.type.name })
            )
    }
}
