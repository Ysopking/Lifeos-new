package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds

enum class WorldEquationPackStructuralIssue {
    PARAMETER_ONLY_NOT_STRUCTURAL,
    REGISTRY_SNAPSHOT_MISMATCH,
    MISSING_REQUIRED_PROVIDER,
    REQUIRED_DIMENSION_UNPROJECTED,
    REQUIRED_NODE_KIND_UNPROJECTED,
    INTERACTION_NODE_KIND_UNPROJECTED,
}

enum class WorldEquationPackStructuralStatus {
    SHADOW_ELIGIBLE,
    BLOCKED,
}

data class WorldEquationPackStructuralEvidence(
    val baselinePackFingerprint: String,
    val candidatePackFingerprint: String,
    val registrySnapshotId: String,
    val registryFingerprint: String,
    val status: WorldEquationPackStructuralStatus,
    val issues: Set<WorldEquationPackStructuralIssue>,
    val id: String,
) {
    init {
        require(baselinePackFingerprint.isNotBlank())
        require(candidatePackFingerprint.isNotBlank())
        require(baselinePackFingerprint != candidatePackFingerprint)
        require(registrySnapshotId.isNotBlank())
        require(registryFingerprint.isNotBlank())
        require((status == WorldEquationPackStructuralStatus.SHADOW_ELIGIBLE) == issues.isEmpty())
        require(id == expectedId())
    }

    val productiveActivationAllowed: Boolean
        get() = false

    val productiveWorldMutationAllowed: Boolean
        get() = false

    private fun expectedId(): String =
        "world-equation-pack-structural-evidence:" + StableFieldIds.fingerprint(
            "world-equation-pack-structural-evidence/v1",
            baselinePackFingerprint,
            candidatePackFingerprint,
            registrySnapshotId,
            registryFingerprint,
            status.name,
            *issues.map { it.name }.sorted().toTypedArray(),
        )

    companion object {
        fun create(
            baselinePackFingerprint: String,
            candidatePackFingerprint: String,
            registrySnapshotId: String,
            registryFingerprint: String,
            issues: Set<WorldEquationPackStructuralIssue>,
        ): WorldEquationPackStructuralEvidence {
            val status = if (issues.isEmpty()) {
                WorldEquationPackStructuralStatus.SHADOW_ELIGIBLE
            } else {
                WorldEquationPackStructuralStatus.BLOCKED
            }
            val id = "world-equation-pack-structural-evidence:" + StableFieldIds.fingerprint(
                "world-equation-pack-structural-evidence/v1",
                baselinePackFingerprint,
                candidatePackFingerprint,
                registrySnapshotId,
                registryFingerprint,
                status.name,
                *issues.map { it.name }.sorted().toTypedArray(),
            )
            return WorldEquationPackStructuralEvidence(
                baselinePackFingerprint = baselinePackFingerprint,
                candidatePackFingerprint = candidatePackFingerprint,
                registrySnapshotId = registrySnapshotId,
                registryFingerprint = registryFingerprint,
                status = status,
                issues = issues.toSet(),
                id = id,
            )
        }
    }
}

/**
 * Structural V1 preflight only. A passing report means the candidate may enter isolated shadow
 * evaluation. It never means PROMOTABLE or ACTIVE and carries no ProductiveWorldHead authority.
 */
class WorldEquationPackStructuralEvaluator {
    fun evaluate(
        baseline: WorldEquationPack,
        candidate: WorldEquationPackCandidate,
        registry: WorldProjectionRegistrySnapshot,
    ): WorldEquationPackStructuralEvidence {
        require(candidate.baselinePackFingerprint == baseline.fingerprint()) {
            "WorldEquationPack structural candidate targets another baseline"
        }
        val pack = candidate.candidate
        val issues = linkedSetOf<WorldEquationPackStructuralIssue>()

        if (candidate.changeKind != WorldEquationPackChangeKind.STRUCTURAL) {
            issues += WorldEquationPackStructuralIssue.PARAMETER_ONLY_NOT_STRUCTURAL
        }
        if (
            pack.projectionContract.registrySnapshotId != registry.id ||
            pack.projectionContract.registryFingerprint != registry.fingerprint()
        ) {
            issues += WorldEquationPackStructuralIssue.REGISTRY_SNAPSHOT_MISMATCH
        }

        val descriptorsById = registry.descriptors.associateBy { it.providerId }
        if (!pack.projectionContract.requiredProviderIds.all { it in descriptorsById }) {
            issues += WorldEquationPackStructuralIssue.MISSING_REQUIRED_PROVIDER
        }

        val selected = pack.projectionContract.requiredProviderIds
            .mapNotNull(descriptorsById::get)
        val projectedDimensions = selected.flatMapTo(linkedSetOf()) { it.signalDimensions }
        val projectedNodeKinds = selected.flatMapTo(linkedSetOf()) { it.nodeKinds }

        if (!projectedDimensions.containsAll(pack.requiredDimensions)) {
            issues += WorldEquationPackStructuralIssue.REQUIRED_DIMENSION_UNPROJECTED
        }
        if (!projectedNodeKinds.containsAll(pack.requiredNodeKinds)) {
            issues += WorldEquationPackStructuralIssue.REQUIRED_NODE_KIND_UNPROJECTED
        }
        if (
            pack.interactionSchema.stableEntries().any { entry ->
                !projectedNodeKinds.containsAll(entry.sourceNodeKinds) ||
                    !projectedNodeKinds.containsAll(entry.targetNodeKinds)
            }
        ) {
            issues += WorldEquationPackStructuralIssue.INTERACTION_NODE_KIND_UNPROJECTED
        }

        return WorldEquationPackStructuralEvidence.create(
            baselinePackFingerprint = baseline.fingerprint(),
            candidatePackFingerprint = pack.fingerprint(),
            registrySnapshotId = registry.id,
            registryFingerprint = registry.fingerprint(),
            issues = issues,
        )
    }
}
