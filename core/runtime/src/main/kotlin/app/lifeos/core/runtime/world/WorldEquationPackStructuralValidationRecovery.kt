package app.lifeos.core.runtime.world

enum class WorldEquationPackStructuralValidationRecoveryState {
    EMPTY,
    HEALTHY,
    CORRUPTED,
}

data class WorldEquationPackStructuralValidationRecoveryReport(
    val state: WorldEquationPackStructuralValidationRecoveryState,
    val bundles: List<WorldEquationPackStructuralValidationBundle>,
    val unreadableEntries: List<String>,
) {
    init {
        require(bundles.map { it.candidatePackFingerprint }.distinct().size == bundles.size) {
            "Structural validation recovery report contains duplicate candidates"
        }
        require(unreadableEntries.none { it.isBlank() })
        when (state) {
            WorldEquationPackStructuralValidationRecoveryState.EMPTY -> {
                require(bundles.isEmpty())
                require(unreadableEntries.isEmpty())
            }
            WorldEquationPackStructuralValidationRecoveryState.HEALTHY -> {
                require(bundles.isNotEmpty())
                require(unreadableEntries.isEmpty())
            }
            WorldEquationPackStructuralValidationRecoveryState.CORRUPTED -> {
                require(unreadableEntries.isNotEmpty())
            }
        }
    }

    val failClosed: Boolean
        get() = state == WorldEquationPackStructuralValidationRecoveryState.CORRUPTED

    val structuralReviewAllowed: Boolean
        get() = state != WorldEquationPackStructuralValidationRecoveryState.CORRUPTED

    val productiveActivationAllowed: Boolean
        get() = false

    val productiveWorldMutationAllowed: Boolean
        get() = false
}

/**
 * Read-only recovery inspection for the durable structural-validation vault.
 *
 * Any unreadable entry makes the whole validation lane fail closed. Recovery does not delete,
 * rewrite, promote or activate anything; it only reports exact durable state.
 */
class WorldEquationPackStructuralValidationRecovery(
    private val repository: WorldEquationPackStructuralValidationRepository,
) {
    suspend fun inspect(): WorldEquationPackStructuralValidationRecoveryReport {
        val load = repository.loadReport()
        val bundles = load.bundles
            .sortedBy { it.candidatePackFingerprint }
        val unreadable = load.unreadableEntries
            .distinct()
            .sorted()

        val state = when {
            unreadable.isNotEmpty() ->
                WorldEquationPackStructuralValidationRecoveryState.CORRUPTED
            bundles.isEmpty() ->
                WorldEquationPackStructuralValidationRecoveryState.EMPTY
            else ->
                WorldEquationPackStructuralValidationRecoveryState.HEALTHY
        }

        return WorldEquationPackStructuralValidationRecoveryReport(
            state = state,
            bundles = bundles,
            unreadableEntries = unreadable,
        )
    }

    suspend fun requireHealthyForReview(): List<WorldEquationPackStructuralValidationBundle> {
        val report = inspect()
        require(!report.failClosed) {
            "Structural validation vault is corrupted; review lane is fail-closed"
        }
        return report.bundles
    }
}
