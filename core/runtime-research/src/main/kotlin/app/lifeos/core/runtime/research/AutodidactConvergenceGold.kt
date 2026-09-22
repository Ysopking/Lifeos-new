package app.lifeos.core.runtime.research

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

sealed interface AutodidactGoldProof {
    val proofKind: String
    val fingerprint: String
}

data class StudyLoopGoldProof private constructor(
    val studyPlanFingerprint: String,
    val knowledgeCandidateFingerprints: List<String>,
    override val fingerprint: String,
) : AutodidactGoldProof {
    override val proofKind: String = "study-loop"

    init {
        require(studyPlanFingerprint.matches(SHA_256_REGEX))
        require(knowledgeCandidateFingerprints.isNotEmpty())
        require(
            knowledgeCandidateFingerprints ==
                knowledgeCandidateFingerprints.distinct().sorted()
        )
        require(
            fingerprint == autodidactGoldFingerprint(
                "autodidact-gold-study/v1",
                studyPlanFingerprint,
                *knowledgeCandidateFingerprints.toTypedArray(),
            )
        )
    }

    companion object {
        fun from(
            plan: AutonomousStudyPlan,
            candidates: Collection<StudyKnowledgeCandidate>,
        ): StudyLoopGoldProof {
            require(!plan.executionAuthority)
            require(!plan.networkAuthority)
            require(!plan.permissionAuthority)
            require(!plan.worldMutationAuthority)
            require(!plan.ownerPolicyAuthority)
            val canonical = candidates.distinctBy { it.id }.sortedBy { it.id }
            require(canonical.isNotEmpty()) {
                "B390 study GOLD requires at least one resolved review candidate"
            }
            require(canonical.all { it.sourceStudyPlanFingerprint == plan.fingerprint })
            require(canonical.all { it.nextCycleValidationRequired })
            require(canonical.all {
                !it.truthAuthority &&
                    !it.executionAuthority &&
                    !it.worldMutationAuthority &&
                    !it.promotionAuthority
            })
            val fingerprints = canonical.map { it.fingerprint }.sorted()
            return StudyLoopGoldProof(
                studyPlanFingerprint = plan.fingerprint,
                knowledgeCandidateFingerprints = fingerprints,
                fingerprint = autodidactGoldFingerprint(
                    "autodidact-gold-study/v1",
                    plan.fingerprint,
                    *fingerprints.toTypedArray(),
                ),
            )
        }
    }
}

data class OwnerAlignmentGoldProof private constructor(
    val ownerUtilityProfileFingerprint: String,
    val completeDecisionEvaluationFingerprints: List<String>,
    override val fingerprint: String,
) : AutodidactGoldProof {
    override val proofKind: String = "owner-alignment"

    init {
        require(ownerUtilityProfileFingerprint.matches(SHA_256_REGEX))
        require(completeDecisionEvaluationFingerprints.isNotEmpty())
        require(
            completeDecisionEvaluationFingerprints ==
                completeDecisionEvaluationFingerprints.distinct().sorted()
        )
        require(
            fingerprint == autodidactGoldFingerprint(
                "autodidact-gold-owner-alignment/v1",
                ownerUtilityProfileFingerprint,
                *completeDecisionEvaluationFingerprints.toTypedArray(),
            )
        )
    }

    companion object {
        fun from(
            profile: OwnerUtilityProfile,
            evaluations: Collection<OwnerAlignedDecisionEvaluation>,
        ): OwnerAlignmentGoldProof {
            require(profile.sourceObservationIds.isNotEmpty()) {
                "B390 owner-alignment GOLD requires explicit B386 owner evidence"
            }
            require(!profile.decisionAuthority)
            require(!profile.executionAuthority)
            require(!profile.ownerPolicyAuthority)
            require(!profile.promotionAuthority)
            val complete = evaluations
                .filter {
                    it.ownerUtilityProfileFingerprint == profile.fingerprint &&
                        it.state == OwnerAlignedDecisionEvaluationState.COMPLETE
                }
                .distinctBy { it.fingerprint }
                .sortedBy { it.fingerprint }
            require(complete.isNotEmpty()) {
                "B390 owner-alignment GOLD requires a complete B387 evaluation"
            }
            require(complete.all {
                !it.epistemicTruthAuthority &&
                    !it.ownerPolicyAuthority &&
                    !it.executionAuthority &&
                    !it.selectionAuthority
            })
            val fingerprints = complete.map { it.fingerprint }
            return OwnerAlignmentGoldProof(
                ownerUtilityProfileFingerprint = profile.fingerprint,
                completeDecisionEvaluationFingerprints = fingerprints,
                fingerprint = autodidactGoldFingerprint(
                    "autodidact-gold-owner-alignment/v1",
                    profile.fingerprint,
                    *fingerprints.toTypedArray(),
                ),
            )
        }
    }
}

data class ExperimentLoopGoldProof private constructor(
    val experimentCycleFingerprints: List<String>,
    val learningCandidateFingerprints: List<String>,
    override val fingerprint: String,
) : AutodidactGoldProof {
    override val proofKind: String = "experiment-loop"

    init {
        require(experimentCycleFingerprints.isNotEmpty())
        require(learningCandidateFingerprints.isNotEmpty())
        require(experimentCycleFingerprints == experimentCycleFingerprints.distinct().sorted())
        require(learningCandidateFingerprints == learningCandidateFingerprints.distinct().sorted())
        require(
            fingerprint == autodidactGoldFingerprint(
                "autodidact-gold-experiment/v1",
                experimentCycleFingerprints.joinToString("\u001f"),
                *learningCandidateFingerprints.toTypedArray(),
            )
        )
    }

    companion object {
        fun from(
            assessments: Collection<AutonomousExperimentAssessment>,
        ): ExperimentLoopGoldProof {
            val canonical = assessments
                .distinctBy { it.cycle.fingerprint }
                .sortedBy { it.cycle.fingerprint }
            require(canonical.isNotEmpty())
            require(canonical.all {
                !it.cycle.executionAuthority &&
                    !it.cycle.externalEffectAuthority &&
                    !it.cycle.causalAuthority &&
                    !it.cycle.worldMutationAuthority &&
                    !it.cycle.promotionAuthority
            })
            val learning = canonical
                .flatMap { it.learningCandidates }
                .distinctBy { it.id }
                .sortedBy { it.id }
            require(learning.isNotEmpty()) {
                "B390 experiment GOLD requires verified next-cycle learning evidence"
            }
            require(learning.all {
                it.entersNextCycleOnly &&
                    !it.truthAuthority &&
                    !it.causalAuthority &&
                    !it.executionAuthority &&
                    !it.worldMutationAuthority &&
                    !it.promotionAuthority
            })
            val cycles = canonical.map { it.cycle.fingerprint }
            val learningFingerprints = learning.map { it.fingerprint }
            return ExperimentLoopGoldProof(
                experimentCycleFingerprints = cycles,
                learningCandidateFingerprints = learningFingerprints,
                fingerprint = autodidactGoldFingerprint(
                    "autodidact-gold-experiment/v1",
                    cycles.joinToString("\u001f"),
                    *learningFingerprints.toTypedArray(),
                ),
            )
        }
    }
}

data class CrossDomainTransferGoldProof private constructor(
    val hypothesisFingerprints: List<String>,
    val validationFingerprints: List<String>,
    override val fingerprint: String,
) : AutodidactGoldProof {
    override val proofKind: String = "cross-domain-transfer"

    init {
        require(hypothesisFingerprints.isNotEmpty())
        require(hypothesisFingerprints == hypothesisFingerprints.distinct().sorted())
        require(validationFingerprints == validationFingerprints.distinct().sorted())
        require(
            fingerprint == autodidactGoldFingerprint(
                "autodidact-gold-transfer/v1",
                hypothesisFingerprints.joinToString("\u001f"),
                *validationFingerprints.toTypedArray(),
            )
        )
    }

    companion object {
        fun from(
            hypotheses: Collection<CrossDomainReasoningTransferHypothesis>,
            validations: Collection<CrossDomainTransferValidation> = emptyList(),
        ): CrossDomainTransferGoldProof {
            val canonical = hypotheses
                .distinctBy { it.fingerprint }
                .sortedBy { it.fingerprint }
            require(canonical.isNotEmpty()) {
                "B390 transfer GOLD requires a B385 cross-domain hypothesis"
            }
            require(canonical.all {
                !it.semanticIdentityEstablished &&
                    !it.directActivationAllowed &&
                    !it.promotionAuthority &&
                    !it.selectionAuthority
            })
            val hypothesisFingerprints = canonical.map { it.fingerprint }
            val known = hypothesisFingerprints.toSet()
            require(validations.all { it.hypothesisFingerprint in known }) {
                "B390 transfer validation references an unknown hypothesis"
            }
            require(validations.all {
                !it.activationAuthority && !it.promotionAuthority
            })
            val validationFingerprints = validations
                .distinctBy { it.fingerprint }
                .map { it.fingerprint }
                .sorted()
            return CrossDomainTransferGoldProof(
                hypothesisFingerprints = hypothesisFingerprints,
                validationFingerprints = validationFingerprints,
                fingerprint = autodidactGoldFingerprint(
                    "autodidact-gold-transfer/v1",
                    hypothesisFingerprints.joinToString("\u001f"),
                    *validationFingerprints.toTypedArray(),
                ),
            )
        }
    }
}

data class AutodidactSemanticCheckpoint(
    val semanticVersion: String,
    val proofFingerprints: List<String>,
    val semanticFingerprint: String,
    val processEpoch: String,
    val fingerprint: String,
) {
    init {
        require(semanticVersion.isNotBlank())
        require(proofFingerprints.isNotEmpty())
        require(proofFingerprints == proofFingerprints.distinct().sorted())
        require(semanticFingerprint.matches(SHA_256_REGEX))
        require(processEpoch.isNotBlank())
        require(
            semanticFingerprint == autodidactGoldFingerprint(
                "autodidact-semantic-checkpoint/v1",
                semanticVersion,
                *proofFingerprints.toTypedArray(),
            )
        )
        require(
            fingerprint == autodidactGoldFingerprint(
                "autodidact-process-checkpoint/v1",
                semanticFingerprint,
                processEpoch,
            )
        )
    }

    companion object {
        fun create(
            proofs: Collection<AutodidactGoldProof>,
            processEpoch: String,
            semanticVersion: String = "autodidact-convergence-v1",
        ): AutodidactSemanticCheckpoint {
            val canonical = proofs
                .distinctBy { it.proofKind }
                .sortedBy { it.proofKind }
            require(canonical.size == proofs.map { it.proofKind }.distinct().size)
            val proofFingerprints = canonical.map { it.fingerprint }.sorted()
            val semanticFingerprint = autodidactGoldFingerprint(
                "autodidact-semantic-checkpoint/v1",
                semanticVersion,
                *proofFingerprints.toTypedArray(),
            )
            return AutodidactSemanticCheckpoint(
                semanticVersion = semanticVersion,
                proofFingerprints = proofFingerprints,
                semanticFingerprint = semanticFingerprint,
                processEpoch = processEpoch,
                fingerprint = autodidactGoldFingerprint(
                    "autodidact-process-checkpoint/v1",
                    semanticFingerprint,
                    processEpoch,
                ),
            )
        }
    }
}

data class AutodidactRecoveryGoldProof private constructor(
    val beforeSemanticFingerprint: String,
    val afterSemanticFingerprint: String,
    val beforeProcessEpoch: String,
    val afterProcessEpoch: String,
    override val fingerprint: String,
) : AutodidactGoldProof {
    override val proofKind: String = "process-recovery"

    init {
        require(beforeSemanticFingerprint.matches(SHA_256_REGEX))
        require(afterSemanticFingerprint.matches(SHA_256_REGEX))
        require(beforeSemanticFingerprint == afterSemanticFingerprint) {
            "Process death changed autodidact decision semantics"
        }
        require(beforeProcessEpoch.isNotBlank() && afterProcessEpoch.isNotBlank())
        require(beforeProcessEpoch != afterProcessEpoch) {
            "Recovery GOLD requires distinct process epochs"
        }
        require(
            fingerprint == autodidactGoldFingerprint(
                "autodidact-gold-recovery/v1",
                beforeSemanticFingerprint,
                afterSemanticFingerprint,
                beforeProcessEpoch,
                afterProcessEpoch,
            )
        )
    }

    companion object {
        fun from(
            before: AutodidactSemanticCheckpoint,
            after: AutodidactSemanticCheckpoint,
        ): AutodidactRecoveryGoldProof {
            require(before.semanticVersion == after.semanticVersion)
            require(before.semanticFingerprint == after.semanticFingerprint) {
                "Process death changed autodidact semantic checkpoint"
            }
            require(before.processEpoch != after.processEpoch)
            return AutodidactRecoveryGoldProof(
                beforeSemanticFingerprint = before.semanticFingerprint,
                afterSemanticFingerprint = after.semanticFingerprint,
                beforeProcessEpoch = before.processEpoch,
                afterProcessEpoch = after.processEpoch,
                fingerprint = autodidactGoldFingerprint(
                    "autodidact-gold-recovery/v1",
                    before.semanticFingerprint,
                    after.semanticFingerprint,
                    before.processEpoch,
                    after.processEpoch,
                ),
            )
        }
    }
}

data class AutodidactConvergenceGoldEvidence(
    val study: StudyLoopGoldProof,
    val ownerAlignment: OwnerAlignmentGoldProof,
    val experiment: ExperimentLoopGoldProof,
    val transfer: CrossDomainTransferGoldProof,
    val recovery: AutodidactRecoveryGoldProof,
    val fingerprint: String,
) {
    init {
        val proofs = proofs()
        require(proofs.map { it.proofKind }.toSet() == REQUIRED_PROOF_KINDS)
        require(
            fingerprint == autodidactGoldFingerprint(
                "autodidact-convergence-gold/v1",
                *proofs.sortedBy { it.proofKind }.map { it.fingerprint }.toTypedArray(),
            )
        )
    }

    fun proofs(): List<AutodidactGoldProof> =
        listOf(study, ownerAlignment, experiment, transfer, recovery)

    companion object {
        val REQUIRED_PROOF_KINDS: Set<String> = linkedSetOf(
            "study-loop",
            "owner-alignment",
            "experiment-loop",
            "cross-domain-transfer",
            "process-recovery",
        )

        fun create(
            study: StudyLoopGoldProof,
            ownerAlignment: OwnerAlignmentGoldProof,
            experiment: ExperimentLoopGoldProof,
            transfer: CrossDomainTransferGoldProof,
            recovery: AutodidactRecoveryGoldProof,
        ): AutodidactConvergenceGoldEvidence {
            val proofs: List<AutodidactGoldProof> =
                listOf(study, ownerAlignment, experiment, transfer, recovery)
            return AutodidactConvergenceGoldEvidence(
                study = study,
                ownerAlignment = ownerAlignment,
                experiment = experiment,
                transfer = transfer,
                recovery = recovery,
                fingerprint = autodidactGoldFingerprint(
                    "autodidact-convergence-gold/v1",
                    *proofs.sortedBy { it.proofKind }
                        .map { it.fingerprint }
                        .toTypedArray(),
                ),
            )
        }
    }
}

object AutodidactConvergenceGoldVerifier {
    fun verify(
        evidence: AutodidactConvergenceGoldEvidence,
    ): String {
        require(
            evidence.proofs().map { it.proofKind }.toSet() ==
                AutodidactConvergenceGoldEvidence.REQUIRED_PROOF_KINDS
        )
        return "AUTODIDACT-CONVERGENCE-GOLD:" + evidence.fingerprint
    }
}

private fun autodidactGoldFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            )
        )
        digest.update(bytes)
    }
    update(domain)
    parts.forEach(::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private val SHA_256_REGEX = Regex("[0-9a-f]{64}")
