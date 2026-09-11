package app.lifeos.core.runtime.evolution

import app.lifeos.core.runtime.buildstudio.BuildActorAction
import app.lifeos.core.runtime.buildstudio.BuildActorEvidence
import app.lifeos.core.runtime.buildstudio.BuildActorRole
import app.lifeos.core.runtime.capability.CapabilityGapDetector
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GeneratedToolArtifactRepository
import app.lifeos.core.runtime.capability.GeneratedToolOpcode
import app.lifeos.core.runtime.capability.GeneratedToolProgramCodec
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.GeneratedToolTrialInvocation
import app.lifeos.core.runtime.capability.GeneratedToolTrialLedger
import app.lifeos.core.runtime.capability.GeneratedToolTrialRunner
import java.time.Instant

sealed interface PrivateNovelCapabilityActivationResult {
    data class Activated(
        val promotion: BoundedNovelPromotionResult,
        val canaryExecutions: List<NovelCapabilityCanaryExecutionResult>,
    ) : PrivateNovelCapabilityActivationResult

    data class AlreadyActive(val record: GeneratedToolRecord) : PrivateNovelCapabilityActivationResult

    data class Blocked(
        val toolId: String,
        val reasons: List<String>,
    ) : PrivateNovelCapabilityActivationResult
}

/**
 * Explicit private-owner workflow for one already-TRIAL bounded generated tool.
 *
 * Nothing calls this coordinator automatically. One invocation performs five distinct,
 * expectation-bearing and side-effect-free Novel Canary trials, evaluates readiness, durably seals
 * the exact canary state, records a distinct deterministic reviewer identity and private-owner
 * activation identity, then delegates to the guarded bounded promotion bridge.
 */
class PrivateNovelCapabilityActivationCoordinator(
    private val capabilities: CapabilityRegistry,
    private val tools: GeneratedToolRegistry,
    private val artifacts: GeneratedToolArtifactRepository,
    private val trialLedger: GeneratedToolTrialLedger,
    private val canary: NovelCapabilityCanaryCoordinator,
    private val admissionGate: NovelCapabilityAdmissionGate,
    private val readinessGate: NovelCapabilityCanaryReadinessGate,
    private val promotionStore: NovelCapabilityPromotionStore,
    private val promotion: BoundedNovelPromotionCoordinator,
    private val now: () -> Instant = Instant::now,
) {
    private val gapDetector = CapabilityGapDetector(capabilities)

    suspend fun reviewAndActivate(
        toolId: String,
        ownerActorId: String,
    ): PrivateNovelCapabilityActivationResult {
        require(toolId.isNotBlank())
        require(ownerActorId.isNotBlank())
        require(ownerActorId != REVIEWER_ACTOR_ID) {
            "Private owner activation identity must differ from the readiness reviewer identity"
        }

        val record = requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        if (record.state == GeneratedToolState.ACTIVE) {
            return PrivateNovelCapabilityActivationResult.AlreadyActive(record)
        }
        if (record.state != GeneratedToolState.TRIAL) {
            return blocked(toolId, "tool-not-in-trial:${record.state.name}")
        }

        val artifact = artifacts.load(toolId)
            ?: return blocked(toolId, "generated-tool-artifact-missing")
        if (!artifact.matches(record)) return blocked(toolId, "generated-tool-artifact-mismatch")

        val program = runCatching { GeneratedToolProgramCodec.decode(artifact.canonicalProgram) }
            .getOrElse { return blocked(toolId, "generated-tool-program-invalid") }
        if (
            program.toolId != toolId ||
            program.capabilityId != record.manifest.sourceCapability ||
            program.requiredInputs != record.manifest.requiredInputs ||
            program.requiredOutputs != record.manifest.requiredOutputs
        ) {
            return blocked(toolId, "generated-tool-program-contract-mismatch")
        }
        if (program.instructions.size != 1) {
            return blocked(toolId, "private-novel-canary-requires-single-bounded-opcode")
        }
        val cases = casesFor(program.instructions.single().opcode)
        if (cases.size != REQUIRED_CASES) {
            return blocked(toolId, "private-novel-canary-cases-unavailable")
        }

        val requirement = CapabilityRequirement(
            capabilityId = record.manifest.sourceCapability,
            requiredInputs = record.manifest.requiredInputs,
            requiredOutputs = record.manifest.requiredOutputs,
        )
        val gap = gapDetector.detect(requirement)
            ?: return blocked(toolId, "capability-gap-disappeared")
        if (gap.type != CapabilityGapType.CAPABILITY_MISSING || gap.candidateProviderIds.isNotEmpty()) {
            return blocked(toolId, "capability-no-longer-completely-missing:${gap.type.name}")
        }

        val subject = NovelCapabilityAdmissionSubject.create(gap, record, artifact)
        val admission = admissionGate.evaluate(subject)
        val executions = mutableListOf<NovelCapabilityCanaryExecutionResult>()
        for (case in cases) {
            val execution = canary.execute(
                subject = subject,
                admission = admission,
                invocation = GeneratedToolTrialInvocation(
                    toolId = toolId,
                    invocationId = "$toolId/private-novel-canary-v1/${case.id}",
                    input = case.input,
                    expectedOutput = case.expected,
                ),
            )
            executions += execution
            when (execution) {
                is NovelCapabilityCanaryExecutionResult.Executed -> Unit
                is NovelCapabilityCanaryExecutionResult.Blocked ->
                    return PrivateNovelCapabilityActivationResult.Blocked(
                        toolId,
                        execution.reasons.ifEmpty { listOf("private-novel-canary-blocked") },
                    )
                is NovelCapabilityCanaryExecutionResult.Exhausted ->
                    return blocked(toolId, "private-novel-canary-budget-exhausted:${execution.usedInvocations}")
            }
        }

        val readiness = readinessGate.evaluate(subject, admission)
        if (readiness.decision != NovelCapabilityCanaryReadinessDecision.READY_FOR_REVIEW) {
            return PrivateNovelCapabilityActivationResult.Blocked(toolId, readiness.reasons)
        }
        require(readiness.completedOutcomes == REQUIRED_CASES) {
            "Private novel readiness must bind exactly $REQUIRED_CASES canary outcomes"
        }
        require(trialLedger.evidence(toolId).results.count {
            it.invocationId.startsWith("$toolId/private-novel-canary-v1/")
        } == REQUIRED_CASES) {
            "Private novel readiness must be backed by exactly $REQUIRED_CASES dedicated trial results"
        }

        val seal = promotionStore.sealNovelForPromotion(
            admissionEvidenceId = admission.id,
            subjectId = subject.id,
            toolId = subject.toolId,
            candidateRecordFingerprint = subject.candidateRecordFingerprint,
            artifactId = subject.artifactId,
            readinessEvidenceId = readiness.id,
            expectedReservedInvocations = readiness.reservedInvocations,
            sealedAt = now(),
        )
        val reviewAt = maxOf(now(), seal.sealedAt)
        val activationAt = maxOf(now(), reviewAt)
        val actors = listOf(
            BuildActorEvidence(
                actorId = REVIEWER_ACTOR_ID,
                role = BuildActorRole.REVIEWER,
                action = BuildActorAction.APPROVED,
                occurredAt = reviewAt,
                evidenceRef = readiness.id,
            ),
            BuildActorEvidence(
                actorId = ownerActorId,
                role = BuildActorRole.PROMOTION_ACTOR,
                action = BuildActorAction.PROMOTED,
                occurredAt = activationAt,
                evidenceRef = seal.id,
            ),
        )
        val promoted = promotion.promote(subject, admission, actors)
        require(promoted.seal.id == seal.id)
        require(promoted.readiness.id == readiness.id)
        require(promoted.activeRecord.state == GeneratedToolState.ACTIVE)
        return PrivateNovelCapabilityActivationResult.Activated(promoted, executions)
    }

    private fun blocked(toolId: String, reason: String) =
        PrivateNovelCapabilityActivationResult.Blocked(toolId, listOf(reason))

    private data class CanaryCase(val id: String, val input: String, val expected: String)

    private fun casesFor(opcode: GeneratedToolOpcode): List<CanaryCase> = when (opcode) {
        GeneratedToolOpcode.TRIM -> listOf(
            CanaryCase("trim-leading", "   alpha", "alpha"),
            CanaryCase("trim-trailing", "beta   ", "beta"),
            CanaryCase("trim-both", "  gamma  ", "gamma"),
            CanaryCase("trim-lines", "\ndelta\n", "delta"),
            CanaryCase("trim-stable", "epsilon", "epsilon"),
        )
        GeneratedToolOpcode.NORMALIZE_WHITESPACE -> listOf(
            CanaryCase("normalize-leading", "  alpha beta", "alpha beta"),
            CanaryCase("normalize-runs", "alpha    beta", "alpha beta"),
            CanaryCase("normalize-lines", "alpha\nbeta", "alpha beta"),
            CanaryCase("normalize-tabs", "alpha\t\tbeta", "alpha beta"),
            CanaryCase("normalize-stable", "alpha beta gamma", "alpha beta gamma"),
        )
        GeneratedToolOpcode.UPPERCASE -> listOf(
            CanaryCase("uppercase-lower", "alpha beta", "ALPHA BETA"),
            CanaryCase("uppercase-mixed", "LifeOS 51", "LIFEOS 51"),
            CanaryCase("uppercase-stable", "ALREADY", "ALREADY"),
            CanaryCase("uppercase-symbols", "a-b_c", "A-B_C"),
            CanaryCase("uppercase-empty-space", " x ", " X "),
        )
        GeneratedToolOpcode.LOWERCASE -> listOf(
            CanaryCase("lowercase-upper", "ALPHA BETA", "alpha beta"),
            CanaryCase("lowercase-mixed", "LifeOS 51", "lifeos 51"),
            CanaryCase("lowercase-stable", "already", "already"),
            CanaryCase("lowercase-symbols", "A-B_C", "a-b_c"),
            CanaryCase("lowercase-empty-space", " X ", " x "),
        )
        GeneratedToolOpcode.UNSUPPORTED -> emptyList()
    }

    private companion object {
        const val REQUIRED_CASES = 5
        const val REVIEWER_ACTOR_ID = "lifeos-private-novel-readiness-reviewer"
    }
}
