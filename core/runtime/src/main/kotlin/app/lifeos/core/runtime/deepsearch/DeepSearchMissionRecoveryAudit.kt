package app.lifeos.core.runtime.deepsearch

/** One deterministic recovery-integrity finding. */
data class DeepSearchRecoveryIssue(
    val missionId: DeepSearchMissionId,
    val code: String,
) {
    init { require(code.isNotBlank()) }
}

data class DeepSearchRecoveryAuditReport(
    val missionsChecked: Int,
    val activeMissions: Int,
    val terminalMissions: Int,
    val issues: List<DeepSearchRecoveryIssue>,
) {
    val healthy: Boolean get() = issues.isEmpty()
}

/**
 * Boot/recovery integrity audit for V12. It never executes search sources or mutates state.
 * Any impossible cross-store combination is surfaced so application startup can fail closed.
 */
class DeepSearchMissionRecoveryAuditor(
    private val ledger: DeepSearchMissionLedger,
    private val checkpoints: DeepSearchCheckpointStore,
    private val resultPhotons: DeepSearchResultPhotonPersistence,
    private val projector: DeepSearchCheckpointResultProjector = DeepSearchCheckpointResultProjector(),
    private val verifier: DeepSearchMissionVerifier = DeepSearchMissionVerifier(projector),
) {
    suspend fun audit(): DeepSearchRecoveryAuditReport {
        val snapshots = ledger.all()
        val issues = mutableListOf<DeepSearchRecoveryIssue>()
        for (snapshot in snapshots) {
            runCatching { auditMission(snapshot) }
                .exceptionOrNull()
                ?.let { error ->
                    issues += DeepSearchRecoveryIssue(
                        missionId = snapshot.definition.id,
                        code = stableIssue(error),
                    )
                }
        }
        return DeepSearchRecoveryAuditReport(
            missionsChecked = snapshots.size,
            activeMissions = snapshots.count { !it.terminal },
            terminalMissions = snapshots.count { it.terminal },
            issues = issues.sortedWith(compareBy({ it.missionId.value }, { it.code })),
        )
    }

    suspend fun requireHealthy(): DeepSearchRecoveryAuditReport {
        val report = audit()
        check(report.healthy) {
            "DeepSearch recovery audit failed: " + report.issues.joinToString(";") {
                "${it.missionId.value}:${it.code}"
            }
        }
        return report
    }

    private suspend fun auditMission(snapshot: DeepSearchMissionSnapshot) {
        val stored = checkpoints.load(snapshot.definition.id)
        snapshot.checkpointFingerprint?.let { fingerprint ->
            requireNotNull(stored) { "checkpoint-missing" }
            require(stored.checkpoint.fingerprint() == fingerprint) { "checkpoint-fingerprint-mismatch" }
        }

        when (snapshot.state) {
            DeepSearchMissionState.PLANNED,
            DeepSearchMissionState.EXPLORING,
            -> Unit

            DeepSearchMissionState.SYNTHESIZING -> {
                val checkpoint = requireNotNull(stored) { "synthesis-checkpoint-missing" }.checkpoint
                require(projector.isTerminal(checkpoint)) { "synthesis-checkpoint-not-terminal" }
                resultPhotons.findForMission(snapshot.definition.id)?.let { photon ->
                    verifier.requireVerified(
                        snapshot.definition,
                        checkpoint,
                        product(snapshot.definition.id, checkpoint, photon),
                    )
                }
            }

            DeepSearchMissionState.VERIFYING -> {
                val checkpoint = requireNotNull(stored) { "verification-checkpoint-missing" }.checkpoint
                require(projector.isTerminal(checkpoint)) { "verification-checkpoint-not-terminal" }
                val photon = requireNotNull(resultPhotons.findForMission(snapshot.definition.id)) {
                    "verification-result-photon-missing"
                }
                verifier.requireVerified(
                    snapshot.definition,
                    checkpoint,
                    product(snapshot.definition.id, checkpoint, photon),
                )
            }

            DeepSearchMissionState.COMPLETED,
            DeepSearchMissionState.UNRESOLVED,
            -> {
                val checkpoint = requireNotNull(stored) { "terminal-checkpoint-missing" }.checkpoint
                require(projector.isTerminal(checkpoint)) { "terminal-checkpoint-not-terminal" }
                val resultId = requireNotNull(snapshot.resultPhotonId) { "terminal-result-id-missing" }
                val photon = requireNotNull(resultPhotons.load(resultId)) {
                    "terminal-deepsearch-result-photon-is-missing"
                }
                val product = product(snapshot.definition.id, checkpoint, photon)
                if (snapshot.state == DeepSearchMissionState.COMPLETED) {
                    require(product.result.status == DeepSearchStatus.RESOLVED) {
                        "completed-result-not-resolved"
                    }
                } else {
                    require(product.result.status != DeepSearchStatus.RESOLVED) {
                        "unresolved-result-is-resolved"
                    }
                }
                verifier.requireVerified(snapshot.definition, checkpoint, product)
            }

            DeepSearchMissionState.BLOCKED,
            DeepSearchMissionState.CANCELLED,
            -> Unit
        }
    }

    private fun product(
        missionId: DeepSearchMissionId,
        checkpoint: DeepSearchPlannerCheckpoint,
        photon: app.lifeos.core.model.Photon,
    ): DeepSearchMissionProduct {
        val result = projector.project(checkpoint)
        val evidenceIds = result.evidence
            .mapNotNull { it.sourcePhotonId }
            .distinct()
            .sortedBy { it.value }
        return DeepSearchMissionProduct(photon, result, evidenceIds, missionId)
    }

    private fun stableIssue(error: Throwable): String {
        val raw = error.message.orEmpty()
            .lowercase()
            .replace(Regex("[^a-z0-9._:-]+"), "-")
            .trim('-')
            .take(160)
        return raw.ifBlank { error::class.simpleName.orEmpty().ifBlank { "unknown-recovery-error" } }
    }
}
