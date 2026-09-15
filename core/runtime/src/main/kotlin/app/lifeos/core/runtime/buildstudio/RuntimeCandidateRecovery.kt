package app.lifeos.core.runtime.buildstudio

/**
 * Durable recovery input. Persistence may store only the immutable artifact + seal + policy. It may
 * never persist or deserialize a VerifiedRuntimeCandidate proof.
 */
data class StoredRuntimeCandidateEvidence(
    val artifact: CandidateArtifact,
    val seal: CandidateRuntimeSeal,
    val policy: RuntimeCandidatePolicy,
) {
    init {
        require(!artifact.activationAllowed)
        require(!seal.activationAllowed)
    }
}

/**
 * Restart path that always recomputes the artifact digest and re-verifies the signer/policy through
 * the same runtime verifier used before promotion. No boolean verified flag is accepted.
 */
class RuntimeCandidateRehydrator(
    private val verifier: RuntimeCandidateVerifier,
) {
    suspend fun rehydrate(evidence: StoredRuntimeCandidateEvidence): RuntimeCandidateVerificationResult =
        verifier.verify(
            artifact = evidence.artifact,
            seal = evidence.seal,
            policy = evidence.policy,
        )
}
