package app.lifeos.core.runtime.research

import app.lifeos.core.creative.OwnerEditDiffLearningEvidence
import app.lifeos.core.creative.OwnerWritingStyleProfile
import app.lifeos.core.language.LanguageLearningEpisode
import app.lifeos.core.runtime.artifact.CrossArtifactReusePlan
import app.lifeos.core.runtime.artifact.LivingArtifactRefreshReport
import app.lifeos.core.runtime.artifact.LivingArtifactRefreshState
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class AutodidacticLanguageArtifactSemanticCheckpoint private constructor(
    val semanticVersion: Int,
    val languageEpisodeFingerprint: String,
    val ownerStyleProfileFingerprint: String,
    val editLearningEvidenceFingerprint: String,
    val editedStyleSampleFingerprint: String,
    val targetSemanticPlanFingerprint: String,
    val materializedAssetSha256: String,
    val materializedAssetMediaType: String,
    val livingRefreshCandidateFingerprint: String,
    val crossArtifactReusePlanFingerprint: String,
    val reusableClaimBindingFingerprints: List<String>,
    val semanticFingerprint: String,
    val processEpoch: String,
    val processCheckpointFingerprint: String,
) {
    init {
        require(semanticVersion > 0)
        require(languageEpisodeFingerprint.matches(SHA_256_REGEX_B450))
        require(ownerStyleProfileFingerprint.matches(SHA_256_REGEX_B450))
        require(editLearningEvidenceFingerprint.matches(SHA_256_REGEX_B450))
        require(editedStyleSampleFingerprint.matches(SHA_256_REGEX_B450))
        require(targetSemanticPlanFingerprint.matches(SHA_256_REGEX_B450))
        require(materializedAssetSha256.matches(SHA_256_REGEX_B450))
        require(materializedAssetMediaType.isNotBlank())
        require(livingRefreshCandidateFingerprint.matches(SHA_256_REGEX_B450))
        require(crossArtifactReusePlanFingerprint.matches(SHA_256_REGEX_B450))
        require(reusableClaimBindingFingerprints.isNotEmpty())
        require(
            reusableClaimBindingFingerprints ==
                reusableClaimBindingFingerprints.distinct().sorted()
        )
        require(reusableClaimBindingFingerprints.all { it.matches(SHA_256_REGEX_B450) })
        require(processEpoch.isNotBlank())
        require(
            semanticFingerprint == semanticCheckpointFingerprintB450(
                semanticVersion,
                languageEpisodeFingerprint,
                ownerStyleProfileFingerprint,
                editLearningEvidenceFingerprint,
                editedStyleSampleFingerprint,
                targetSemanticPlanFingerprint,
                materializedAssetSha256,
                materializedAssetMediaType,
                livingRefreshCandidateFingerprint,
                crossArtifactReusePlanFingerprint,
                reusableClaimBindingFingerprints,
            )
        )
        require(
            processCheckpointFingerprint ==
                processCheckpointFingerprintB450(semanticFingerprint, processEpoch)
        )
    }

    val truthAuthority: Boolean get() = false
    val factualAuthority: Boolean get() = false
    val preferenceAuthority: Boolean get() = false
    val rewriteAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false
    val publicationAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun from(
            languageEpisode: LanguageLearningEpisode,
            ownerStyleProfile: OwnerWritingStyleProfile,
            editLearningEvidence: OwnerEditDiffLearningEvidence,
            livingRefresh: LivingArtifactRefreshReport,
            reusePlan: CrossArtifactReusePlan,
            processEpoch: String,
        ): AutodidacticLanguageArtifactSemanticCheckpoint {
            require(languageEpisode.learningEligible) {
                "B450 requires a verified or owner-confirmed B421 language-learning episode"
            }
            require(ownerStyleProfile.languageTag == editLearningEvidence.languageTag) {
                "B450 owner style language must match the exact B442 edit evidence language"
            }
            require(
                editLearningEvidence.afterStyleSample.fingerprint in
                    ownerStyleProfile.sampleFingerprints
            ) {
                "B450 B441 profile must contain the exact B442 edited style sample"
            }
            require(
                editLearningEvidence.ownerConfirmationFingerprint in
                    ownerStyleProfile.ownerConfirmationFingerprints
            ) {
                "B450 B441 profile must retain the exact B442 owner confirmation"
            }
            require(livingRefresh.state == LivingArtifactRefreshState.UPDATE_CANDIDATE) {
                "B450 requires an exact B448 living-artifact refresh candidate"
            }
            val refreshCandidate = requireNotNull(livingRefresh.candidate)
            require(
                reusePlan.targetSemanticPlanFingerprint ==
                    refreshCandidate.nextSemanticPlanFingerprint
            ) {
                "B450 B449 reuse target must equal the exact B448 next semantic plan"
            }
            require(reusePlan.bindings.isNotEmpty()) {
                "B450 requires at least one exact B449 reusable knowledge binding"
            }
            require(
                reusePlan.bindings.all {
                    it.targetSemanticPlanFingerprint ==
                        refreshCandidate.nextSemanticPlanFingerprint
                }
            ) {
                "B450 cross-artifact bindings must target the exact refreshed semantic plan"
            }

            return fromSignals(
                semanticVersion = 1,
                languageEpisodeFingerprint = languageEpisode.fingerprint,
                ownerStyleProfileFingerprint = ownerStyleProfile.fingerprint,
                editLearningEvidenceFingerprint = editLearningEvidence.fingerprint,
                editedStyleSampleFingerprint = editLearningEvidence.afterStyleSample.fingerprint,
                targetSemanticPlanFingerprint = refreshCandidate.nextSemanticPlanFingerprint,
                materializedAssetSha256 = refreshCandidate.nextAssetSha256,
                materializedAssetMediaType = refreshCandidate.mediaType,
                livingRefreshCandidateFingerprint = refreshCandidate.fingerprint,
                crossArtifactReusePlanFingerprint = reusePlan.fingerprint,
                reusableClaimBindingFingerprints =
                    reusePlan.bindings.map { it.fingerprint },
                processEpoch = processEpoch,
            )
        }

        internal fun fromSignals(
            semanticVersion: Int = 1,
            languageEpisodeFingerprint: String,
            ownerStyleProfileFingerprint: String,
            editLearningEvidenceFingerprint: String,
            editedStyleSampleFingerprint: String,
            targetSemanticPlanFingerprint: String,
            materializedAssetSha256: String,
            materializedAssetMediaType: String,
            livingRefreshCandidateFingerprint: String,
            crossArtifactReusePlanFingerprint: String,
            reusableClaimBindingFingerprints: Collection<String>,
            processEpoch: String,
        ): AutodidacticLanguageArtifactSemanticCheckpoint {
            val bindings = reusableClaimBindingFingerprints.distinct().sorted()
            val semanticFingerprint = semanticCheckpointFingerprintB450(
                semanticVersion,
                languageEpisodeFingerprint,
                ownerStyleProfileFingerprint,
                editLearningEvidenceFingerprint,
                editedStyleSampleFingerprint,
                targetSemanticPlanFingerprint,
                materializedAssetSha256,
                materializedAssetMediaType,
                livingRefreshCandidateFingerprint,
                crossArtifactReusePlanFingerprint,
                bindings,
            )
            return AutodidacticLanguageArtifactSemanticCheckpoint(
                semanticVersion = semanticVersion,
                languageEpisodeFingerprint = languageEpisodeFingerprint,
                ownerStyleProfileFingerprint = ownerStyleProfileFingerprint,
                editLearningEvidenceFingerprint = editLearningEvidenceFingerprint,
                editedStyleSampleFingerprint = editedStyleSampleFingerprint,
                targetSemanticPlanFingerprint = targetSemanticPlanFingerprint,
                materializedAssetSha256 = materializedAssetSha256,
                materializedAssetMediaType = materializedAssetMediaType,
                livingRefreshCandidateFingerprint = livingRefreshCandidateFingerprint,
                crossArtifactReusePlanFingerprint = crossArtifactReusePlanFingerprint,
                reusableClaimBindingFingerprints = bindings,
                semanticFingerprint = semanticFingerprint,
                processEpoch = processEpoch,
                processCheckpointFingerprint =
                    processCheckpointFingerprintB450(semanticFingerprint, processEpoch),
            )
        }
    }
}

data class AutodidacticLanguageArtifactRecoveryProof(
    val beforeSemanticFingerprint: String,
    val afterSemanticFingerprint: String,
    val beforeProcessCheckpointFingerprint: String,
    val afterProcessCheckpointFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(beforeSemanticFingerprint.matches(SHA_256_REGEX_B450))
        require(afterSemanticFingerprint.matches(SHA_256_REGEX_B450))
        require(beforeProcessCheckpointFingerprint.matches(SHA_256_REGEX_B450))
        require(afterProcessCheckpointFingerprint.matches(SHA_256_REGEX_B450))
        require(beforeSemanticFingerprint == afterSemanticFingerprint)
        require(beforeProcessCheckpointFingerprint != afterProcessCheckpointFingerprint)
        require(
            fingerprint == recoveryFingerprintB450(
                beforeSemanticFingerprint,
                afterSemanticFingerprint,
                beforeProcessCheckpointFingerprint,
                afterProcessCheckpointFingerprint,
            )
        )
    }

    companion object {
        fun from(
            before: AutodidacticLanguageArtifactSemanticCheckpoint,
            after: AutodidacticLanguageArtifactSemanticCheckpoint,
        ): AutodidacticLanguageArtifactRecoveryProof {
            require(before.processEpoch != after.processEpoch) {
                "B450 recovery proof requires distinct process epochs"
            }
            require(before.semanticVersion == after.semanticVersion)
            require(before.semanticFingerprint == after.semanticFingerprint) {
                "B450 process death changed semantic language/artifact state"
            }
            return AutodidacticLanguageArtifactRecoveryProof(
                beforeSemanticFingerprint = before.semanticFingerprint,
                afterSemanticFingerprint = after.semanticFingerprint,
                beforeProcessCheckpointFingerprint = before.processCheckpointFingerprint,
                afterProcessCheckpointFingerprint = after.processCheckpointFingerprint,
                fingerprint = recoveryFingerprintB450(
                    before.semanticFingerprint,
                    after.semanticFingerprint,
                    before.processCheckpointFingerprint,
                    after.processCheckpointFingerprint,
                ),
            )
        }
    }
}

data class AutodidacticLanguageArtifactGoldEvidence(
    val semanticCheckpointFingerprint: String,
    val recovery: AutodidacticLanguageArtifactRecoveryProof,
    val fingerprint: String,
) {
    init {
        require(semanticCheckpointFingerprint.matches(SHA_256_REGEX_B450))
        require(recovery.beforeSemanticFingerprint == semanticCheckpointFingerprint)
        require(
            fingerprint == goldFingerprintB450(
                semanticCheckpointFingerprint,
                recovery.fingerprint,
            )
        )
    }

    val truthAuthority: Boolean get() = false
    val factualAuthority: Boolean get() = false
    val preferenceAuthority: Boolean get() = false
    val rewriteAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false
    val publicationAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun create(
            before: AutodidacticLanguageArtifactSemanticCheckpoint,
            after: AutodidacticLanguageArtifactSemanticCheckpoint,
        ): AutodidacticLanguageArtifactGoldEvidence {
            val recovery = AutodidacticLanguageArtifactRecoveryProof.from(before, after)
            return AutodidacticLanguageArtifactGoldEvidence(
                semanticCheckpointFingerprint = before.semanticFingerprint,
                recovery = recovery,
                fingerprint = goldFingerprintB450(
                    before.semanticFingerprint,
                    recovery.fingerprint,
                ),
            )
        }
    }
}

class AutodidacticLanguageArtifactGoldVerifier {
    fun verify(evidence: AutodidacticLanguageArtifactGoldEvidence): Boolean =
        !evidence.truthAuthority &&
            !evidence.factualAuthority &&
            !evidence.preferenceAuthority &&
            !evidence.rewriteAuthority &&
            !evidence.finalizationAuthority &&
            !evidence.publicationAuthority &&
            !evidence.executionAuthority &&
            evidence.recovery.beforeSemanticFingerprint ==
                evidence.recovery.afterSemanticFingerprint &&
            evidence.recovery.beforeProcessCheckpointFingerprint !=
                evidence.recovery.afterProcessCheckpointFingerprint
}

private fun semanticCheckpointFingerprintB450(
    semanticVersion: Int,
    languageEpisodeFingerprint: String,
    ownerStyleProfileFingerprint: String,
    editLearningEvidenceFingerprint: String,
    editedStyleSampleFingerprint: String,
    targetSemanticPlanFingerprint: String,
    materializedAssetSha256: String,
    materializedAssetMediaType: String,
    livingRefreshCandidateFingerprint: String,
    crossArtifactReusePlanFingerprint: String,
    reusableClaimBindingFingerprints: List<String>,
): String = b450Fingerprint(
    "autodidactic-language-artifact-semantic-checkpoint/v1",
    semanticVersion.toString(),
    languageEpisodeFingerprint,
    ownerStyleProfileFingerprint,
    editLearningEvidenceFingerprint,
    editedStyleSampleFingerprint,
    targetSemanticPlanFingerprint,
    materializedAssetSha256,
    materializedAssetMediaType,
    livingRefreshCandidateFingerprint,
    crossArtifactReusePlanFingerprint,
    *reusableClaimBindingFingerprints.toTypedArray(),
)

private fun processCheckpointFingerprintB450(
    semanticFingerprint: String,
    processEpoch: String,
): String = b450Fingerprint(
    "autodidactic-language-artifact-process-checkpoint/v1",
    semanticFingerprint,
    processEpoch,
)

private fun recoveryFingerprintB450(
    beforeSemanticFingerprint: String,
    afterSemanticFingerprint: String,
    beforeProcessCheckpointFingerprint: String,
    afterProcessCheckpointFingerprint: String,
): String = b450Fingerprint(
    "autodidactic-language-artifact-recovery-proof/v1",
    beforeSemanticFingerprint,
    afterSemanticFingerprint,
    beforeProcessCheckpointFingerprint,
    afterProcessCheckpointFingerprint,
)

private fun goldFingerprintB450(
    semanticCheckpointFingerprint: String,
    recoveryFingerprint: String,
): String = b450Fingerprint(
    "autodidactic-language-artifact-gold-evidence/v1",
    semanticCheckpointFingerprint,
    recoveryFingerprint,
)

private fun b450Fingerprint(domain: String, vararg parts: String): String {
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

private val SHA_256_REGEX_B450 = Regex("[0-9a-f]{64}")
