package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
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
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetDemand
import app.lifeos.core.runtime.resource.ResourceBudgetDomain
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetReservation
import app.lifeos.core.runtime.resource.ResourceBudgetReservationResult
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.resource.SharedResourceBudgetDecision
import app.lifeos.core.runtime.resource.SharedResourceBudgetGate
import app.lifeos.core.runtime.resource.SharedResourceBudgetRuntimeRegistry
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

/** Process-owned bridge to the same durable V16 budget coordinator used by private ToolWorkshop. */
object PrivateNovelCapabilityResourceRuntimeRegistry {
    @Volatile
    private var budgets: ResourceBudgetCoordinator? = null

    fun install(value: ResourceBudgetCoordinator) {
        budgets = value
    }

    fun currentOrNull(): ResourceBudgetCoordinator? = budgets

    internal fun clearForTests() {
        budgets = null
    }
}

private sealed interface NovelCanaryResourcePreparation {
    data class Ready(val reservation: ResourceBudgetReservation?) : NovelCanaryResourcePreparation
    data class Blocked(val reason: String) : NovelCanaryResourcePreparation
}

/**
 * Explicit private-owner workflow for one already-TRIAL bounded generated tool.
 *
 * Nothing calls this coordinator automatically. One invocation performs five distinct,
 * expectation-bearing and side-effect-free Novel Canary trials, evaluates readiness, durably seals
 * the exact canary state, records a distinct deterministic reviewer identity and private-owner
 * activation identity, then delegates to the guarded bounded promotion bridge.
 *
 * V16 resource intelligence is applied only to a canary invocation that has no durable trial result
 * yet. A World Formula EVOLUTION allocation is checked before the durable resource reservation and
 * before the bounded trial executes. The reservation is committed only after the canary has returned
 * an authoritative persisted outcome. Retries with an existing trial never execute or charge work a
 * second time; they only settle an already-open reservation when one exists.
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
    private val budgets: ResourceBudgetCoordinator? =
        PrivateNovelCapabilityResourceRuntimeRegistry.currentOrNull(),
    private val sharedBudgetsProvider: () -> SharedResourceBudgetGate? =
        { SharedResourceBudgetRuntimeRegistry.current() },
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
            val invocation = GeneratedToolTrialInvocation(
                toolId = toolId,
                invocationId = "$toolId/private-novel-canary-v1/${case.id}",
                input = case.input,
                expectedOutput = case.expected,
            )
            val existingTrial = trialLedger.evidence(toolId).results
                .firstOrNull { it.invocationId == invocation.invocationId }
            val prepared = if (existingTrial == null) {
                prepareResourceReservation(subject, invocation.invocationId)
            } else {
                NovelCanaryResourcePreparation.Ready(
                    existingResourceReservation(subject, invocation.invocationId)
                )
            }
            val resourceReservation = when (prepared) {
                is NovelCanaryResourcePreparation.Blocked ->
                    return blocked(toolId, prepared.reason)
                is NovelCanaryResourcePreparation.Ready -> prepared.reservation
            }

            val execution = canary.execute(
                subject = subject,
                admission = admission,
                invocation = invocation,
            )
            executions += execution
            when (execution) {
                is NovelCapabilityCanaryExecutionResult.Executed -> {
                    settleResourceReservation(subject, resourceReservation, execution.outcome)
                }
                is NovelCapabilityCanaryExecutionResult.Blocked -> {
                    releaseResourceReservation(subject, resourceReservation)
                    return PrivateNovelCapabilityActivationResult.Blocked(
                        toolId,
                        execution.reasons.ifEmpty { listOf("private-novel-canary-blocked") },
                    )
                }
                is NovelCapabilityCanaryExecutionResult.Exhausted -> {
                    releaseResourceReservation(subject, resourceReservation)
                    return blocked(toolId, "private-novel-canary-budget-exhausted:${execution.usedInvocations}")
                }
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

    private suspend fun prepareResourceReservation(
        subject: NovelCapabilityAdmissionSubject,
        invocationId: String,
    ): NovelCanaryResourcePreparation {
        val coordinator = budgets ?: return NovelCanaryResourcePreparation.Ready(null)
        val shared = sharedBudgetsProvider()
        if (shared != null) {
            val demand = ResourceBudgetDemand(
                domain = ResourceBudgetDomain.EVOLUTION,
                requested = CANARY_RESERVATION,
                goalRelevance = 0.85,
                priority = 0.85,
                expectedUtility = 0.80,
                confidence = 1.0,
            )
            when (val decision = shared.allocate(CANARY_SHARED_HARD_QUOTA, listOf(demand))) {
                is SharedResourceBudgetDecision.Blocked ->
                    return NovelCanaryResourcePreparation.Blocked(
                        "private-novel-canary-world-budget:${decision.reason}"
                    )
                is SharedResourceBudgetDecision.Ready -> {
                    val allocation = decision.allocation.allocation(ResourceBudgetDomain.EVOLUTION)
                        ?: return NovelCanaryResourcePreparation.Blocked(
                            "private-novel-canary-world-budget-missing-allocation"
                        )
                    if (!CANARY_RESERVATION.isWithin(allocation.allocated)) {
                        return NovelCanaryResourcePreparation.Blocked(
                            "private-novel-canary-world-budget-insufficient"
                        )
                    }
                }
            }
        }

        val accountId = resourceAccountId(subject)
        coordinator.createAccount(accountId, CANARY_ACCOUNT_QUOTA)
        return when (
            val reservation = coordinator.reserve(
                accountId = accountId,
                idempotencyKey = resourceIdempotencyKey(subject, invocationId),
                usage = CANARY_RESERVATION,
            )
        ) {
            is ResourceBudgetReservationResult.Denied ->
                NovelCanaryResourcePreparation.Blocked(
                    "private-novel-canary-resource:${reservation.reason}"
                )
            is ResourceBudgetReservationResult.Reserved ->
                NovelCanaryResourcePreparation.Ready(reservation.reservation)
            is ResourceBudgetReservationResult.Existing -> when (reservation.reservation.state) {
                ResourceBudgetReservationState.RESERVED ->
                    NovelCanaryResourcePreparation.Ready(reservation.reservation)
                ResourceBudgetReservationState.COMMITTED ->
                    NovelCanaryResourcePreparation.Blocked(
                        "private-novel-canary-resource-already-committed-without-trial"
                    )
                ResourceBudgetReservationState.RELEASED ->
                    NovelCanaryResourcePreparation.Blocked(
                        "private-novel-canary-resource-reservation-released"
                    )
            }
        }
    }

    private suspend fun existingResourceReservation(
        subject: NovelCapabilityAdmissionSubject,
        invocationId: String,
    ): ResourceBudgetReservation? {
        val coordinator = budgets ?: return null
        val account = coordinator.currentOrNull(resourceAccountId(subject)) ?: return null
        val reservation = account.reservations.firstOrNull {
            it.idempotencyKey == resourceIdempotencyKey(subject, invocationId)
        } ?: return null
        require(reservation.reserved == CANARY_RESERVATION) {
            "Private novel canary retry changed its V16 resource envelope"
        }
        require(reservation.state != ResourceBudgetReservationState.RELEASED) {
            "Private novel canary durable trial has a released V16 reservation"
        }
        return reservation
    }

    private suspend fun settleResourceReservation(
        subject: NovelCapabilityAdmissionSubject,
        reservation: ResourceBudgetReservation?,
        outcome: NovelCapabilityCanaryOutcome,
    ) {
        val coordinator = budgets ?: return
        reservation ?: return
        val actual = ResourceBudgetUsage(
            elapsedMillis = outcome.latencyMs,
            workUnits = 1,
            candidates = 1,
        )
        require(actual.isWithin(reservation.reserved)) {
            "Private novel canary measured usage exceeds its V16 reservation"
        }
        coordinator.commit(resourceAccountId(subject), reservation.id, actual)
    }

    private suspend fun releaseResourceReservation(
        subject: NovelCapabilityAdmissionSubject,
        reservation: ResourceBudgetReservation?,
    ) {
        val coordinator = budgets ?: return
        reservation ?: return
        when (reservation.state) {
            ResourceBudgetReservationState.RESERVED ->
                coordinator.release(resourceAccountId(subject), reservation.id)
            ResourceBudgetReservationState.RELEASED -> Unit
            ResourceBudgetReservationState.COMMITTED ->
                error("Committed private novel canary reservation cannot be released")
        }
    }

    private fun resourceAccountId(subject: NovelCapabilityAdmissionSubject): ResourceBudgetAccountId =
        ResourceBudgetAccountId(
            "private-novel-canary:" + StableFieldIds.fingerprint(
                "private-novel-canary-resource-account/v1",
                subject.id,
                subject.toolId,
            )
        )

    private fun resourceIdempotencyKey(
        subject: NovelCapabilityAdmissionSubject,
        invocationId: String,
    ): String = "private-novel-canary:${subject.id}:$invocationId"

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
        val CANARY_RESERVATION = ResourceBudgetUsage(
            elapsedMillis = 30_000,
            workUnits = 1,
            memoryBytes = 16L * 1024L * 1024L,
            ioBytes = 1L * 1024L * 1024L,
            networkBytes = 0,
            candidates = 1,
        )
        val CANARY_ACCOUNT_QUOTA = ResourceBudgetQuota(
            elapsedMillis = 150_000,
            workUnits = 5,
            memoryBytes = 80L * 1024L * 1024L,
            ioBytes = 5L * 1024L * 1024L,
            networkBytes = 0,
            candidates = 5,
        )
        val CANARY_SHARED_HARD_QUOTA = ResourceBudgetQuota(
            elapsedMillis = 60_000,
            workUnits = 8,
            memoryBytes = 128L * 1024L * 1024L,
            ioBytes = 16L * 1024L * 1024L,
            networkBytes = 0,
            candidates = 8,
        )
    }
}