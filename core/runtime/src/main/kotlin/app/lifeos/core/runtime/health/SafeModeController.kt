package app.lifeos.core.runtime.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stops automatic processing, never removes vault content or hides diagnostics. */
class SafeModeController {
    private val mutableReasons = MutableStateFlow<Set<String>>(emptySet())
    val reasons = mutableReasons.asStateFlow()
    val active: Boolean get() = mutableReasons.value.isNotEmpty()
    @Synchronized fun enter(reason: String) { mutableReasons.value = mutableReasons.value + reason }
    @Synchronized fun clearAfterVerifiedRecovery(reason: String) {
        mutableReasons.value = mutableReasons.value - reason
    }
}

class QuarantineRegistry {
    private val nodes = mutableSetOf<String>()
    @Synchronized fun contains(node: HealthNode): Boolean = node.id in nodes
    @Synchronized fun isolate(node: HealthNode) { nodes += node.id }
    // Deliberately no automatic release: invariant/security failures need an explicit repair path.
}
