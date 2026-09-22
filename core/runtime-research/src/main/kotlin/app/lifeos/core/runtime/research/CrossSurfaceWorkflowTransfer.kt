package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.level7.StructuralTransferCandidate
import app.lifeos.core.runtime.reasoning.GeneralizedSkillCandidate
import app.lifeos.core.runtime.reasoning.ProceduralSkillCandidate
import app.lifeos.core.runtime.reasoning.SkillGeneralizationEngine
import app.lifeos.core.runtime.reasoning.SkillGeneralizationRequest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class WorkflowSurfaceKind {
    WEB_SITE,
    ANDROID_APP,
}

data class WorkflowSurfaceIdentity(
    val kind: WorkflowSurfaceKind,
    val stableId: String,
) {
    init {
        require(stableId.isNotBlank())
        require(stableId.length <= MAX_SURFACE_ID_CHARS)
        require(stableId.none { it == '\u0000' || it == '\n' || it == '\r' })
    }

    val domainId: String
        get() = kind.name.lowercase() + ":" + stableId

    fun fingerprint(): String = b419Fingerprint(
        "workflow-surface-identity/v1",
        kind.name,
        stableId,
        domainId,
    )
}

data class CrossSurfaceWorkflowTransferRequest(
    val inductionReport: WorkflowSkillInductionReport,
    val sourceSkill: ProceduralSkillCandidate,
    val sourceSurface: WorkflowSurfaceIdentity,
    val targetSurface: WorkflowSurfaceIdentity,
    val transfer: StructuralTransferCandidate,
    val targetObjectivesByStepKey: Map<String, String>,
) {
    init {
        require(sourceSurface != targetSurface) {
            "B419 requires a distinct target site or app"
        }
        require(
            inductionReport.candidates.any {
                it.id == sourceSkill.id && it.fingerprint() == sourceSkill.fingerprint()
            }
        ) {
            "B419 source skill must be carried by the exact B418 induction report"
        }
        require(transfer.source.domainId == sourceSurface.domainId) {
            "Structural transfer source domain does not match the source surface"
        }
        require(transfer.target.domainId == targetSurface.domainId) {
            "Structural transfer target domain does not match the target surface"
        }
        require(transfer.source.topologyFingerprint == sourceSkill.shapeFingerprint) {
            "Structural transfer source topology does not match the B418 workflow skill"
        }
        require(targetObjectivesByStepKey.keys == sourceSkill.steps.mapTo(linkedSetOf()) { it.key }) {
            "Target objectives must cover the exact source workflow step set"
        }
        require(targetObjectivesByStepKey.values.none { it.isBlank() })
    }

    fun fingerprint(): String = b419Fingerprint(
        "cross-surface-workflow-transfer-request/v1",
        inductionReport.fingerprint,
        sourceSkill.id,
        sourceSkill.fingerprint(),
        sourceSurface.fingerprint(),
        targetSurface.fingerprint(),
        transfer.id,
        transfer.fingerprint(),
        *targetObjectivesByStepKey.toSortedMap()
            .flatMap { (key, objective) -> listOf("step:" + key, objective) }
            .toTypedArray(),
    )
}

data class CrossSurfaceWorkflowTransferCandidate(
    val requestFingerprint: String,
    val inductionReportFingerprint: String,
    val sourceSkillId: String,
    val sourceSkillFingerprint: String,
    val sourceSurface: WorkflowSurfaceIdentity,
    val targetSurface: WorkflowSurfaceIdentity,
    val generalizedSkill: GeneralizedSkillCandidate,
    val fingerprint: String,
) {
    init {
        require(requestFingerprint.matches(SHA_256_REGEX_B419))
        require(inductionReportFingerprint.matches(SHA_256_REGEX_B419))
        require(sourceSkillId == generalizedSkill.sourceSkillId)
        require(sourceSkillFingerprint == generalizedSkill.sourceSkillFingerprint)
        require(sourceSurface.domainId == generalizedSkill.sourceDomainId)
        require(targetSurface.domainId == generalizedSkill.targetDomainId)
        require(
            fingerprint == candidateFingerprint(
                requestFingerprint = requestFingerprint,
                inductionReportFingerprint = inductionReportFingerprint,
                sourceSkillId = sourceSkillId,
                sourceSkillFingerprint = sourceSkillFingerprint,
                sourceSurface = sourceSurface,
                targetSurface = targetSurface,
                generalizedSkill = generalizedSkill,
            )
        )
    }

    val semanticIdentityEstablished: Boolean get() = false
    val executionAuthority: Boolean get() = false
    val activationAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false
    val ownerPolicyAuthority: Boolean get() = false
    val requiresShadowValidation: Boolean get() = true
}

class CrossSurfaceWorkflowTransferEngine(
    private val generalizationEngine: SkillGeneralizationEngine = SkillGeneralizationEngine(),
) {
    fun propose(
        request: CrossSurfaceWorkflowTransferRequest,
    ): CrossSurfaceWorkflowTransferCandidate {
        val generalized = generalizationEngine.generalize(
            listOf(
                SkillGeneralizationRequest(
                    sourceSkill = request.sourceSkill,
                    transfer = request.transfer,
                    targetObjectivesByStepKey = request.targetObjectivesByStepKey,
                )
            )
        ).single()

        val requestFingerprint = request.fingerprint()
        val fingerprint = candidateFingerprint(
            requestFingerprint = requestFingerprint,
            inductionReportFingerprint = request.inductionReport.fingerprint,
            sourceSkillId = request.sourceSkill.id,
            sourceSkillFingerprint = request.sourceSkill.fingerprint(),
            sourceSurface = request.sourceSurface,
            targetSurface = request.targetSurface,
            generalizedSkill = generalized,
        )
        return CrossSurfaceWorkflowTransferCandidate(
            requestFingerprint = requestFingerprint,
            inductionReportFingerprint = request.inductionReport.fingerprint,
            sourceSkillId = request.sourceSkill.id,
            sourceSkillFingerprint = request.sourceSkill.fingerprint(),
            sourceSurface = request.sourceSurface,
            targetSurface = request.targetSurface,
            generalizedSkill = generalized,
            fingerprint = fingerprint,
        )
    }
}

private fun candidateFingerprint(
    requestFingerprint: String,
    inductionReportFingerprint: String,
    sourceSkillId: String,
    sourceSkillFingerprint: String,
    sourceSurface: WorkflowSurfaceIdentity,
    targetSurface: WorkflowSurfaceIdentity,
    generalizedSkill: GeneralizedSkillCandidate,
): String = b419Fingerprint(
    "cross-surface-workflow-transfer-candidate/v1",
    requestFingerprint,
    inductionReportFingerprint,
    sourceSkillId,
    sourceSkillFingerprint,
    sourceSurface.fingerprint(),
    targetSurface.fingerprint(),
    generalizedSkill.id,
    generalizedSkill.fingerprint,
)

private fun b419Fingerprint(domain: String, vararg parts: String): String {
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

private val SHA_256_REGEX_B419 = Regex("[0-9a-f]{64}")
private const val MAX_SURFACE_ID_CHARS = 512
