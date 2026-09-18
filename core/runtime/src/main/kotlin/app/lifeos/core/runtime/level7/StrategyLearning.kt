package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

data class VerifiedWorldTransition(
    val beforeSnapshotId: String,
    val afterSnapshotId: String,
    val actionFingerprint: String,
    val outcomeEvidenceFingerprint: String,
    val independentVerification: Boolean,
) {
    init {
        require(beforeSnapshotId.isNotBlank())
        require(afterSnapshotId.isNotBlank())
        require(beforeSnapshotId != afterSnapshotId)
        require(actionFingerprint.isNotBlank())
        require(outcomeEvidenceFingerprint.isNotBlank())
    }
}

data class StrategyLearningCandidate private constructor(
    val id: String,
    val strategyId: String,
    val transitions: List<VerifiedWorldTransition>,
    val strategyFingerprint: String,
) {
    init {
        require(strategyId.isNotBlank())
        require(strategyFingerprint.isNotBlank())
        require(transitions.isNotEmpty())
        require(transitions.all { it.independentVerification }) {
            "Strategy learning requires independently verified outcomes"
        }
        require(id == expectedId())
    }

    val promotionAllowed: Boolean get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-strategy-learning-candidate/v1",
        strategyId,
        strategyFingerprint,
        *transitions.sortedBy { it.beforeSnapshotId }.flatMap {
            listOf(
                it.beforeSnapshotId,
                it.afterSnapshotId,
                it.actionFingerprint,
                it.outcomeEvidenceFingerprint,
                it.independentVerification.toString(),
            )
        }.toTypedArray(),
    )

    private fun expectedId(): String = "strategy-learning:${fingerprint()}"

    companion object {
        fun create(
            strategyId: String,
            strategyFingerprint: String,
            transitions: Collection<VerifiedWorldTransition>,
        ): StrategyLearningCandidate {
            val canonical = transitions.sortedWith(compareBy({ it.beforeSnapshotId }, { it.afterSnapshotId }))
            require(canonical.isNotEmpty())
            require(canonical.all { it.independentVerification })
            val fp = StableFieldIds.fingerprint(
                "level7-strategy-learning-candidate/v1",
                strategyId,
                strategyFingerprint,
                *canonical.flatMap {
                    listOf(
                        it.beforeSnapshotId,
                        it.afterSnapshotId,
                        it.actionFingerprint,
                        it.outcomeEvidenceFingerprint,
                        it.independentVerification.toString(),
                    )
                }.toTypedArray(),
            )
            return StrategyLearningCandidate(
                id = "strategy-learning:$fp",
                strategyId = strategyId,
                transitions = canonical,
                strategyFingerprint = strategyFingerprint,
            )
        }
    }
}
