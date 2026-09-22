package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.reasoning.ProceduralSkillCandidate
import app.lifeos.core.runtime.reasoning.ProceduralSkillInductionEngine
import app.lifeos.core.runtime.reasoning.ProceduralSkillTrace
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class WorkflowSkillSupport(
    val proceduralTrace: ProceduralSkillTrace,
    val outcomeLearningCandidate: ActionOutcomeLearningCandidate,
    val fingerprint: String,
) {
    init {
        require(outcomeLearningCandidate.direction == ActionOutcomeLearningDirection.POSITIVE) {
            "Workflow skill support requires confirmed positive action outcome evidence"
        }
        require(outcomeLearningCandidate.nextCycleEligible) {
            "Workflow skill support must be next-cycle eligible"
        }
        require(
            fingerprint == supportFingerprint(
                proceduralTraceFingerprint = proceduralTrace.fingerprint,
                outcomeCandidateFingerprint = outcomeLearningCandidate.fingerprint,
                dispatchPlanFingerprint = outcomeLearningCandidate.dispatchPlanFingerprint,
                resourceIdentity = outcomeLearningCandidate.resourceIdentity,
            )
        )
    }

    val truthAuthority: Boolean get() = false
    val causalAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
    val activationAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false

    companion object {
        fun bind(
            proceduralTrace: ProceduralSkillTrace,
            outcomeLearningCandidate: ActionOutcomeLearningCandidate,
        ): WorkflowSkillSupport =
            WorkflowSkillSupport(
                proceduralTrace = proceduralTrace,
                outcomeLearningCandidate = outcomeLearningCandidate,
                fingerprint = supportFingerprint(
                    proceduralTraceFingerprint = proceduralTrace.fingerprint,
                    outcomeCandidateFingerprint = outcomeLearningCandidate.fingerprint,
                    dispatchPlanFingerprint = outcomeLearningCandidate.dispatchPlanFingerprint,
                    resourceIdentity = outcomeLearningCandidate.resourceIdentity,
                ),
            )
    }
}

data class WorkflowSkillInductionReport(
    val supportFingerprints: List<String>,
    val candidates: List<ProceduralSkillCandidate>,
    val fingerprint: String,
) {
    init {
        require(supportFingerprints == supportFingerprints.distinct().sorted())
        require(candidates == candidates.distinctBy { it.id }.sortedBy { it.id })
        require(
            fingerprint == reportFingerprint(
                supportFingerprints = supportFingerprints,
                candidateFingerprints = candidates.map { it.fingerprint() }.sorted(),
            )
        )
    }

    val executionAuthority: Boolean get() = false
    val activationAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false
}

/**
 * B418 bridges verified productive action outcomes back into the existing B376 procedural-skill
 * induction path instead of creating a second skill system.
 *
 * Every support item binds an existing B376 ProceduralSkillTrace to one B416 positive next-cycle
 * outcome candidate. The B376 engine still owns independent-cycle/shape induction and its resulting
 * candidates remain inactive, non-executable and non-promotional. B418 never turns an observation
 * into causal proof and never promotes a skill.
 */
class WorkflowSkillInductionEngine(
    private val proceduralInduction: ProceduralSkillInductionEngine =
        ProceduralSkillInductionEngine(),
) {
    fun induce(
        supports: Collection<WorkflowSkillSupport>,
        semanticKeysByShape: Map<String, String> = emptyMap(),
    ): WorkflowSkillInductionReport {
        require(supports.isNotEmpty()) {
            "Workflow skill induction requires positive verified supports"
        }
        val canonical = supports.sortedBy { it.fingerprint }
        require(canonical.map { it.fingerprint }.distinct().size == canonical.size) {
            "Duplicate workflow skill support is not allowed"
        }
        require(
            canonical.map { it.proceduralTrace.fingerprint }.distinct().size == canonical.size
        ) {
            "One procedural trace cannot count as multiple workflow supports"
        }
        require(
            canonical.map { it.outcomeLearningCandidate.fingerprint }.distinct().size ==
                canonical.size
        ) {
            "One action outcome cannot count as multiple workflow supports"
        }

        val candidates = proceduralInduction.induce(
            traces = canonical.map { it.proceduralTrace },
            semanticKeysByShape = semanticKeysByShape,
        ).sortedBy { it.id }

        val supportFingerprints = canonical.map { it.fingerprint }
        return WorkflowSkillInductionReport(
            supportFingerprints = supportFingerprints,
            candidates = candidates,
            fingerprint = reportFingerprint(
                supportFingerprints = supportFingerprints,
                candidateFingerprints = candidates.map { it.fingerprint() }.sorted(),
            ),
        )
    }
}

private fun supportFingerprint(
    proceduralTraceFingerprint: String,
    outcomeCandidateFingerprint: String,
    dispatchPlanFingerprint: String,
    resourceIdentity: String,
): String = b418Fingerprint(
    "workflow-skill-support/v1",
    proceduralTraceFingerprint,
    outcomeCandidateFingerprint,
    dispatchPlanFingerprint,
    resourceIdentity,
)

private fun reportFingerprint(
    supportFingerprints: List<String>,
    candidateFingerprints: List<String>,
): String = b418Fingerprint(
    "workflow-skill-induction-report/v1",
    *supportFingerprints.sorted().map { "support:$it" }.toTypedArray(),
    *candidateFingerprints.sorted().map { "candidate:$it" }.toTypedArray(),
)

private fun b418Fingerprint(domain: String, vararg parts: String): String {
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
