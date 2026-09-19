package app.lifeos.core.runtime.world

/**
 * Recovery-safe producer for immutable structural validation bundles.
 *
 * The coordinator has no activation dependency and no API that can mutate productive world state.
 * Repeated calls for the same exact candidate are idempotent; a conflicting durable bundle fails
 * closed through the repository contract.
 */
class WorldEquationPackStructuralValidationCoordinator(
    private val repository: WorldEquationPackStructuralValidationRepository,
    private val gate: WorldEquationPackStructuralValidationGate =
        WorldEquationPackStructuralValidationGate(),
) {
    suspend fun validateAndPersist(
        baseline: WorldEquationPack,
        candidate: WorldEquationPackCandidate,
        preflight: WorldEquationPackStructuralEvidence,
        evidenceRecord: WorldEquationPackEvidenceRecord,
    ): WorldEquationPackStructuralValidationBundle {
        val bundle = gate.validate(
            baseline = baseline,
            candidate = candidate,
            preflight = preflight,
            record = evidenceRecord,
        )
        repository.putIfAbsent(bundle)
        val durable = requireNotNull(repository.load(bundle.candidatePackFingerprint)) {
            "Structural validation bundle disappeared after persistence"
        }
        require(durable.fingerprint == bundle.fingerprint) {
            "Durable structural validation bundle fingerprint mismatch"
        }
        return durable
    }

    suspend fun recover(
        candidatePackFingerprint: String,
    ): WorldEquationPackStructuralValidationBundle? {
        require(candidatePackFingerprint.isNotBlank())
        return repository.load(candidatePackFingerprint)
    }
}
