package app.lifeos.next

internal sealed interface AccessConvergenceAction {
    data class RuntimePermissions(
        val permissions: List<String>,
    ) : AccessConvergenceAction {
        init {
            require(permissions.isNotEmpty())
            require(permissions == permissions.distinct().sorted())
        }
    }

    data class SpecialAccess(
        val access: PermissionSpecialAccess,
    ) : AccessConvergenceAction

    data object Complete : AccessConvergenceAction
}

/**
 * Process-local convergence planner for Android owner-facing access.
 *
 * Missing access is always derived from current OS state. This coordinator only remembers what was
 * already presented during the current Activity/process session, so an explicit denial cannot cause
 * an infinite prompt loop while a later process start still re-evaluates the real missing access.
 */
internal class AccessConvergenceCoordinator(
    private val runtimePlan: () -> RuntimePermissionRequestPlan,
    private val specialPlan: () -> SpecialAccessRequestPlan,
) {
    private val attemptedRuntimePermissions = linkedSetOf<String>()
    private val attemptedSpecialAccess = linkedSetOf<PermissionSpecialAccess>()

    fun next(): AccessConvergenceAction {
        val runtime = runtimePlan()
            .permissions
            .filterNot(attemptedRuntimePermissions::contains)

        if (runtime.isNotEmpty()) {
            return AccessConvergenceAction.RuntimePermissions(runtime)
        }

        val special = specialPlan()
            .accesses
            .firstOrNull { it !in attemptedSpecialAccess }

        return if (special == null) {
            AccessConvergenceAction.Complete
        } else {
            AccessConvergenceAction.SpecialAccess(special)
        }
    }

    fun markLaunched(action: AccessConvergenceAction) {
        when (action) {
            is AccessConvergenceAction.RuntimePermissions ->
                attemptedRuntimePermissions += action.permissions

            is AccessConvergenceAction.SpecialAccess ->
                attemptedSpecialAccess += action.access

            AccessConvergenceAction.Complete -> Unit
        }
    }
}
