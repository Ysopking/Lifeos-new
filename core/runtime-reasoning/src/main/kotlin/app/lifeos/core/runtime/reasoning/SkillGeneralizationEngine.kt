package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.level7.StructuralSignature
import app.lifeos.core.runtime.level7.StructuralTransferCandidate

data class SkillGeneralizationPolicy(
    val minimumStructuralSimilarity: Double = 0.70,
    val maximumSteps: Int = 32,
) {
    init {
        require(minimumStructuralSimilarity.isFinite() && minimumStructuralSimilarity in 0.0..1.0)
        require(maximumSteps in 1..128)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "skill-generalization-policy/v1",
        java.lang.Double.toHexString(minimumStructuralSimilarity),
        maximumSteps.toString(),
    )
}

data class SkillGeneralizationRequest(
    val sourceSkill: ProceduralSkillCandidate,
    val transfer: StructuralTransferCandidate,
    val targetObjectivesByStepKey: Map<String, String>,
) {
    init {
        require(targetObjectivesByStepKey.isNotEmpty())
        require(targetObjectivesByStepKey.keys.none { it.isBlank() })
        require(targetObjectivesByStepKey.values.none { it.isBlank() })
        require(!transfer.semanticIdentityEstablished)
        require(!transfer.directTransferActivationAllowed)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "skill-generalization-request/v1",
        sourceSkill.id,
        sourceSkill.fingerprint(),
        transfer.id,
        transfer.fingerprint(),
        *targetObjectivesByStepKey.toSortedMap().flatMap { (key, objective) ->
            listOf("step:" + key, objective)
        }.toTypedArray(),
    )
}

data class GeneralizedSkillStep(
    val sourceStepKey: String,
    val targetObjective: String,
    val dependencyKeys: List<String>,
    val priority: Int,
) {
    init {
        require(sourceStepKey.isNotBlank())
        require(targetObjective.isNotBlank())
        require(dependencyKeys == dependencyKeys.distinct().sorted())
        require(sourceStepKey !in dependencyKeys)
        require(priority in -1_000..1_000)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "generalized-skill-step/v1",
        sourceStepKey,
        targetObjective,
        priority.toString(),
        *dependencyKeys.map { "depends:" + it }.toTypedArray(),
    )
}

data class GeneralizedSkillCandidate(
    val id: String,
    val sourceSkillId: String,
    val sourceSkillFingerprint: String,
    val sourceDomainId: String,
    val targetDomainId: String,
    val sourceStructuralSignatureFingerprint: String,
    val targetStructuralSignatureFingerprint: String,
    val transferCandidateId: String,
    val transferFingerprint: String,
    val structuralSimilarity: Double,
    val validationFingerprint: String,
    val steps: List<GeneralizedSkillStep>,
    val policyFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(id.startsWith(ID_PREFIX))
        require(sourceSkillId.startsWith(ProceduralSkillCandidate.ID_PREFIX))
        require(sourceSkillFingerprint.isNotBlank())
        require(sourceDomainId.isNotBlank() && targetDomainId.isNotBlank())
        require(sourceDomainId != targetDomainId)
        require(sourceStructuralSignatureFingerprint.isNotBlank())
        require(targetStructuralSignatureFingerprint.isNotBlank())
        require(transferCandidateId.isNotBlank())
        require(transferFingerprint.isNotBlank())
        require(structuralSimilarity.isFinite() && structuralSimilarity in 0.0..1.0)
        require(validationFingerprint.isNotBlank())
        require(steps.isNotEmpty())
        require(steps == steps.sortedBy { it.sourceStepKey })
        require(steps.map { it.sourceStepKey }.distinct().size == steps.size)
        require(policyFingerprint.isNotBlank())
        require(
            fingerprint == generalizedSkillFingerprint(
                sourceSkillId = sourceSkillId,
                sourceSkillFingerprint = sourceSkillFingerprint,
                sourceDomainId = sourceDomainId,
                targetDomainId = targetDomainId,
                sourceStructuralSignatureFingerprint = sourceStructuralSignatureFingerprint,
                targetStructuralSignatureFingerprint = targetStructuralSignatureFingerprint,
                transferCandidateId = transferCandidateId,
                transferFingerprint = transferFingerprint,
                structuralSimilarity = structuralSimilarity,
                validationFingerprint = validationFingerprint,
                steps = steps,
                policyFingerprint = policyFingerprint,
            )
        )
        require(id == ID_PREFIX + fingerprint)
    }

    val semanticIdentityEstablished: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    val activationAllowed: Boolean
        get() = false

    val promotionAllowed: Boolean
        get() = false

    companion object {
        const val ID_PREFIX = "generalized-skill-candidate:"
    }
}

/**
 * B377 generalizes an inactive B376 skill through an already validated structural-transfer
 * candidate. Structural similarity is not semantic identity. The generalized candidate preserves
 * the source dependency topology and priority ordering while allowing target-domain objectives.
 * No generalized candidate may execute, activate, or promote itself.
 */
class SkillGeneralizationEngine(
    private val policy: SkillGeneralizationPolicy = SkillGeneralizationPolicy(),
) {
    fun generalize(
        requests: Collection<SkillGeneralizationRequest>,
    ): List<GeneralizedSkillCandidate> {
        require(requests.isNotEmpty()) { "Skill generalization requires requests" }
        val canonical = requests
            .distinctBy { it.fingerprint() }
            .sortedBy { it.fingerprint() }
        require(canonical.size == requests.size) {
            "Duplicate skill generalization requests are not allowed"
        }

        return canonical
            .map(::generalizeOne)
            .sortedWith(
                compareByDescending<GeneralizedSkillCandidate> { it.structuralSimilarity }
                    .thenBy { it.id }
            )
    }

    private fun generalizeOne(
        request: SkillGeneralizationRequest,
    ): GeneralizedSkillCandidate {
        val sourceSkill = request.sourceSkill
        val transfer = request.transfer
        require(!sourceSkill.executionAuthority)
        require(!sourceSkill.activationAllowed)
        require(!sourceSkill.promotionAllowed)
        require(!transfer.semanticIdentityEstablished)
        require(!transfer.directTransferActivationAllowed)
        require(transfer.structuralSimilarity >= policy.minimumStructuralSimilarity) {
            "Structural transfer is below the skill generalization threshold"
        }
        require(sourceSkill.steps.size <= policy.maximumSteps) {
            "Source skill exceeds the bounded generalization step budget"
        }
        require(transfer.source.topologyFingerprint == sourceSkill.shapeFingerprint) {
            "Structural transfer source topology does not match the B376 skill shape"
        }

        val sourceKeys = sourceSkill.steps.mapTo(linkedSetOf()) { it.key }
        require(request.targetObjectivesByStepKey.keys == sourceKeys) {
            val missing = sourceKeys - request.targetObjectivesByStepKey.keys
            val unknown = request.targetObjectivesByStepKey.keys - sourceKeys
            "Target objectives must map every source step exactly; " +
                "missing=" + missing.sorted().joinToString(",") +
                ";unknown=" + unknown.sorted().joinToString(",")
        }

        val steps = sourceSkill.steps
            .map { source ->
                GeneralizedSkillStep(
                    sourceStepKey = source.key,
                    targetObjective = request.targetObjectivesByStepKey.getValue(source.key),
                    dependencyKeys = source.dependencyKeys,
                    priority = source.priority,
                )
            }
            .sortedBy { it.sourceStepKey }
        val policyFingerprint = policy.fingerprint()
        val fingerprint = generalizedSkillFingerprint(
            sourceSkillId = sourceSkill.id,
            sourceSkillFingerprint = sourceSkill.fingerprint(),
            sourceDomainId = transfer.source.domainId,
            targetDomainId = transfer.target.domainId,
            sourceStructuralSignatureFingerprint = transfer.source.fingerprint(),
            targetStructuralSignatureFingerprint = transfer.target.fingerprint(),
            transferCandidateId = transfer.id,
            transferFingerprint = transfer.fingerprint(),
            structuralSimilarity = transfer.structuralSimilarity,
            validationFingerprint = transfer.validationFingerprint,
            steps = steps,
            policyFingerprint = policyFingerprint,
        )
        return GeneralizedSkillCandidate(
            id = GeneralizedSkillCandidate.ID_PREFIX + fingerprint,
            sourceSkillId = sourceSkill.id,
            sourceSkillFingerprint = sourceSkill.fingerprint(),
            sourceDomainId = transfer.source.domainId,
            targetDomainId = transfer.target.domainId,
            sourceStructuralSignatureFingerprint = transfer.source.fingerprint(),
            targetStructuralSignatureFingerprint = transfer.target.fingerprint(),
            transferCandidateId = transfer.id,
            transferFingerprint = transfer.fingerprint(),
            structuralSimilarity = transfer.structuralSimilarity,
            validationFingerprint = transfer.validationFingerprint,
            steps = steps,
            policyFingerprint = policyFingerprint,
            fingerprint = fingerprint,
        )
    }
}

private fun generalizedSkillFingerprint(
    sourceSkillId: String,
    sourceSkillFingerprint: String,
    sourceDomainId: String,
    targetDomainId: String,
    sourceStructuralSignatureFingerprint: String,
    targetStructuralSignatureFingerprint: String,
    transferCandidateId: String,
    transferFingerprint: String,
    structuralSimilarity: Double,
    validationFingerprint: String,
    steps: List<GeneralizedSkillStep>,
    policyFingerprint: String,
): String = StableFieldIds.fingerprint(
    "generalized-skill-candidate/v1",
    sourceSkillId,
    sourceSkillFingerprint,
    sourceDomainId,
    targetDomainId,
    sourceStructuralSignatureFingerprint,
    targetStructuralSignatureFingerprint,
    transferCandidateId,
    transferFingerprint,
    java.lang.Double.toHexString(structuralSimilarity),
    validationFingerprint,
    policyFingerprint,
    *steps.sortedBy { it.sourceStepKey }
        .map { "step:" + it.fingerprint() }
        .toTypedArray(),
)
