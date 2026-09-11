package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyDecision
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
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

interface ToolWorkshopBuildStateRehydrator {
    suspend fun restore(
        source: GeneratedSource,
        build: ToolBuildResult,
        tests: ToolTestResult? = null,
    )
}

data class ToolWorkshopExecutionProfile(
    val hardQuota: ResourceBudgetQuota,
    val stageRequests: Map<ToolWorkshopJobState, ResourceBudgetUsage>,
    val goalRelevance: Double = 1.0,
    val priority: Double = 0.8,
    val expectedUtility: Double = 0.9,
    val confidence: Double = 0.9,
) {
    init {
        require(goalRelevance in 0.0..1.0)
        require(priority in 0.0..1.0)
        require(expectedUtility in 0.0..1.0)
        require(confidence in 0.0..1.0)
        EXECUTABLE_STAGES.forEach { stage ->
            val requested = requireNotNull(stageRequests[stage]) { "Missing resource profile for $stage" }
            require(!requested.isZero()) { "$stage must reserve non-zero resources" }
            require(requested.networkBytes == 0L) {
                "Private ToolWorkshop stages cannot request network access"
            }
        }
    }

    fun requested(stage: ToolWorkshopJobState): ResourceBudgetUsage = requireNotNull(stageRequests[stage])

    companion object {
        val EXECUTABLE_STAGES = setOf(
            ToolWorkshopJobState.SPECIFIED,
            ToolWorkshopJobState.DESIGNED,
            ToolWorkshopJobState.IMPLEMENTED,
            ToolWorkshopJobState.BUILT,
            ToolWorkshopJobState.TESTED,
            ToolWorkshopJobState.SECURITY_VALIDATED,
            ToolWorkshopJobState.VERIFIED,
            ToolWorkshopJobState.TRIAL_READY,
        )
    }
}

sealed interface ToolWorkshopAdmissionResult {
    data class Ready(val snapshot: ToolWorkshopJobSnapshot) : ToolWorkshopAdmissionResult
    data class Blocked(val definition: ToolWorkshopJobDefinition, val reason: String) : ToolWorkshopAdmissionResult
}

sealed interface ToolWorkshopStageResult {
    data class Advanced(val snapshot: ToolWorkshopJobSnapshot) : ToolWorkshopStageResult
    data class TrialReady(
        val snapshot: ToolWorkshopJobSnapshot,
        val record: GeneratedToolRecord,
    ) : ToolWorkshopStageResult

    data class Rejected(
        val snapshot: ToolWorkshopJobSnapshot,
        val reason: String,
    ) : ToolWorkshopStageResult

    data class Blocked(
        val snapshot: ToolWorkshopJobSnapshot,
        val reason: String,
    ) : ToolWorkshopStageResult

    data class AlreadyTerminal(val snapshot: ToolWorkshopJobSnapshot) : ToolWorkshopStageResult
}

/**
 * Restart-safe V11 stage executor.
 *
 * A completed stage is made authoritative in this order:
 * 1. persist immutable stage artifact,
 * 2. settle the durable V16 reservation from that artifact,
 * 3. append the job-ledger transition.
 *
 * Therefore a crash can only leave an artifact/reservation that must be rebound; it cannot cause a
 * completed stage to be executed again. Generated-tool lifecycle changes are reconstructed from the
 * same persisted stage evidence before the ledger advances.
 */
class DurableToolWorkshopCoordinator(
    private val jobs: ToolWorkshopJobLedger,
    private val stageArtifacts: ToolWorkshopStageArtifactRepository,
    private val specificationBuilder: ToolSpecificationBuilder,
    private val designer: ToolDesigner,
    private val implementationEngine: ToolImplementationEngine,
    private val buildRunner: ToolBuildRunner,
    private val testRunner: ToolTestRunner,
    private val securityValidator: ToolSecurityValidator,
    private val capabilityVerifier: GeneratedCapabilityVerifier,
    private val tools: GeneratedToolRegistry,
    private val lifecycle: GeneratedToolLifecycleCoordinator,
    private val ownerPolicy: OwnerPolicyLedger,
    private val budgets: ResourceBudgetCoordinator,
    private val actorId: OwnerActorId,
    private val ownerScope: String,
    private val generatedArtifacts: GeneratedToolArtifactRepository? = null,
    private val buildStateRehydrator: ToolWorkshopBuildStateRehydrator? = null,
    private val sharedBudgets: () -> SharedResourceBudgetGate? =
        { SharedResourceBudgetRuntimeRegistry.current() },
    private val now: () -> Instant = Instant::now,
) {
    init { require(ownerScope.isNotBlank()) }

    suspend fun admit(
        request: GeneratedToolRequest,
        sourceRevision: Long,
        policyVersion: String,
        workshopVersion: String,
    ): ToolWorkshopAdmissionResult {
        val definition = ToolWorkshopJobDefinition.fromRequest(
            request = request,
            sourceRevision = sourceRevision,
            policyVersion = policyVersion,
            workshopVersion = workshopVersion,
        )
        jobs.snapshot(definition.id)?.let { return ToolWorkshopAdmissionResult.Ready(it) }

        val decision = ownerPolicy.evaluate(
            OwnerEffectRequest(
                actorId = actorId,
                effect = OwnerEffectType.TOOL_REQUEST,
                resource = "tool-workshop:${definition.id.value}:request",
                scope = ownerScope,
                capabilityId = definition.capabilityId,
                providerVersion = definition.workshopVersion,
            )
        )
        if (decision is OwnerPolicyDecision.Blocked) {
            return ToolWorkshopAdmissionResult.Blocked(
                definition,
                "owner-policy:${decision.reasons.joinToString("|")}",
            )
        }
        return ToolWorkshopAdmissionResult.Ready(jobs.create(definition))
    }

    suspend fun runNext(
        jobId: ToolWorkshopJobId,
        profile: ToolWorkshopExecutionProfile,
    ): ToolWorkshopStageResult {
        var snapshot = requireNotNull(jobs.snapshot(jobId)) { "Unknown ToolWorkshop job $jobId" }

        // Compatibility/recovery for a crash from a previously appended stage with an open budget.
        settleDurableCurrentStageIfNeeded(snapshot)
        if (snapshot.terminal) return ToolWorkshopStageResult.AlreadyTerminal(snapshot)

        val target = nextStage(snapshot.state)
        val existingArtifact = stageArtifacts.load(jobId, target)
        if (existingArtifact != null) {
            settlePersistedTargetStage(snapshot, target, existingArtifact, profile)
            return bindPersistedStage(snapshot, target, existingArtifact)
        }

        val requested = profile.requested(target)
        val allocation = allocate(snapshot, target, requested, profile)
            ?: return ToolWorkshopStageResult.Blocked(snapshot, "tool-workshop-world-budget-unavailable")
        if (!requested.isWithin(allocation)) {
            return ToolWorkshopStageResult.Blocked(snapshot, "tool-workshop-world-budget-insufficient")
        }

        val accountId = accountId(jobId, target)
        budgets.createAccount(accountId, profile.hardQuota)
        val reservation = reserve(accountId, jobId, target, requested)
            ?: return ToolWorkshopStageResult.Blocked(snapshot, "tool-workshop-resource-budget-exhausted")

        when (reservation.state) {
            ResourceBudgetReservationState.RESERVED -> Unit
            ResourceBudgetReservationState.COMMITTED -> error(
                "ToolWorkshop stage has committed budget but no durable stage artifact"
            )
            ResourceBudgetReservationState.RELEASED -> return ToolWorkshopStageResult.Blocked(
                snapshot,
                "tool-workshop-stage-reservation-released",
            )
        }

        val owner = ownerPolicy.evaluate(
            OwnerEffectRequest(
                actorId = actorId,
                effect = OwnerEffectType.TOOL_EXECUTION,
                resource = stageResource(jobId, target),
                scope = ownerScope,
                capabilityId = snapshot.definition.capabilityId,
                providerVersion = snapshot.definition.workshopVersion,
                budgetAccountId = accountId,
                budgetReservationId = reservation.id,
            )
        )
        if (owner is OwnerPolicyDecision.Blocked) {
            budgets.release(accountId, reservation.id)
            snapshot = jobs.interrupt(
                snapshot,
                "owner-policy:${owner.reasons.joinToString("|")}",
            )
            return ToolWorkshopStageResult.Rejected(snapshot, requireNotNull(snapshot.lastDetail))
        }

        val artifact = try {
            executeStage(snapshot, target)
        } catch (error: Exception) {
            budgets.release(accountId, reservation.id)
            return ToolWorkshopStageResult.Blocked(
                snapshot,
                "tool-workshop-stage-failed:${error::class.simpleName}:${error.message.orEmpty().take(160)}",
            )
        }

        try {
            stageArtifacts.persist(artifact)
        } catch (error: Exception) {
            budgets.release(accountId, reservation.id)
            return ToolWorkshopStageResult.Blocked(
                snapshot,
                "tool-workshop-stage-persist-failed:${error::class.simpleName}:${error.message.orEmpty().take(160)}",
            )
        }

        // The persisted artifact is now the authoritative proof that this stage completed.
        settle(accountId, reservation, requested)
        return bindPersistedStage(snapshot, target, artifact)
    }

    private suspend fun allocate(
        snapshot: ToolWorkshopJobSnapshot,
        target: ToolWorkshopJobState,
        requested: ResourceBudgetUsage,
        profile: ToolWorkshopExecutionProfile,
    ): ResourceBudgetUsage? {
        val gate = sharedBudgets() ?: return null
        return when (
            val decision = gate.allocate(
                hardQuota = profile.hardQuota,
                demands = listOf(
                    ResourceBudgetDemand(
                        domain = ResourceBudgetDomain.TOOL_WORKSHOP,
                        requested = requested,
                        goalRelevance = profile.goalRelevance,
                        priority = profile.priority,
                        expectedUtility = profile.expectedUtility,
                        confidence = profile.confidence,
                    )
                ),
            )
        ) {
            is SharedResourceBudgetDecision.Ready ->
                decision.allocation.allocation(ResourceBudgetDomain.TOOL_WORKSHOP)?.allocated
            is SharedResourceBudgetDecision.Blocked -> null
        }
    }

    private suspend fun executeStage(
        snapshot: ToolWorkshopJobSnapshot,
        target: ToolWorkshopJobState,
    ): ToolWorkshopStageArtifact {
        val definition = snapshot.definition
        val payload = when (target) {
            ToolWorkshopJobState.SPECIFIED ->
                ToolWorkshopStagePayloadCodec.encode(specificationBuilder.build(definition.toGap()))

            ToolWorkshopJobState.DESIGNED -> {
                val specification = loadSpecification(definition.id)
                val design = designer.design(snapshot.toolId, specification)
                require(design.toolId == snapshot.toolId) {
                    "Tool designer changed deterministic V11 tool id"
                }
                ToolWorkshopStagePayloadCodec.encode(design)
            }

            ToolWorkshopJobState.IMPLEMENTED -> {
                val design = loadDesign(definition.id)
                val source = implementationEngine.implement(design)
                require(source.toolId == snapshot.toolId) {
                    "Tool implementation changed deterministic V11 tool id"
                }
                require(source.source.toByteArray(Charsets.UTF_8).size <= design.specification.maxSourceBytes)
                ToolWorkshopStagePayloadCodec.encode(source)
            }

            ToolWorkshopJobState.BUILT -> {
                val source = loadSource(definition.id)
                val build = buildRunner.build(source)
                require(build.toolId == snapshot.toolId) {
                    "Build runner changed deterministic V11 tool id"
                }
                ToolWorkshopStagePayloadCodec.encode(build)
            }

            ToolWorkshopJobState.TESTED -> {
                val source = loadSource(definition.id)
                val build = loadBuild(definition.id)
                buildStateRehydrator?.restore(source, build, tests = null)
                ToolWorkshopStagePayloadCodec.encode(testRunner.test(build))
            }

            ToolWorkshopJobState.SECURITY_VALIDATED -> {
                val specification = loadSpecification(definition.id)
                val source = loadSource(definition.id)
                ToolWorkshopStagePayloadCodec.encode(securityValidator.validate(specification, source))
            }

            ToolWorkshopJobState.VERIFIED -> {
                val specification = loadSpecification(definition.id)
                val source = loadSource(definition.id)
                val build = loadBuild(definition.id)
                val tests = loadTests(definition.id)
                buildStateRehydrator?.restore(source, build, tests)
                ToolWorkshopStagePayloadCodec.encode(capabilityVerifier.verify(specification, build))
            }

            ToolWorkshopJobState.TRIAL_READY -> {
                val record = requireNotNull(tools.get(snapshot.toolId)) {
                    "Verified generated tool missing before trial"
                }
                val trial = when (record.state) {
                    GeneratedToolState.VERIFIED -> lifecycle.admitToTrial(snapshot.toolId)
                    GeneratedToolState.TRIAL -> null
                    else -> error("ToolWorkshop trial handoff found unexpected state ${record.state}")
                }
                when (trial) {
                    null,
                    is GeneratedToolTrialAdmissionResult.TrialStarted -> Unit
                    is GeneratedToolTrialAdmissionResult.Rejected -> error(
                        "sandbox-trial-rejected:${trial.reasons.joinToString("|")}"
                    )
                }
                val updated = requireNotNull(tools.get(snapshot.toolId))
                require(updated.state == GeneratedToolState.TRIAL)
                "TRIAL|${updated.manifest.toolId}|${updated.lastMessage.orEmpty()}"
            }

            ToolWorkshopJobState.REQUESTED,
            ToolWorkshopJobState.REJECTED,
            ToolWorkshopJobState.INTERRUPTED -> error("$target is not an executable ToolWorkshop stage")
        }
        return ToolWorkshopStageArtifact(
            jobId = definition.id,
            stage = target,
            payload = payload,
            createdAt = now(),
        )
    }

    private suspend fun bindPersistedStage(
        snapshot: ToolWorkshopJobSnapshot,
        target: ToolWorkshopJobState,
        artifact: ToolWorkshopStageArtifact,
    ): ToolWorkshopStageResult {
        require(artifact.jobId == snapshot.definition.id && artifact.stage == target)
        return when (target) {
            ToolWorkshopJobState.SPECIFIED,
            ToolWorkshopJobState.DESIGNED,
            ToolWorkshopJobState.IMPLEMENTED ->
                ToolWorkshopStageResult.Advanced(jobs.advance(snapshot, target, artifact.fingerprint))

            ToolWorkshopJobState.BUILT -> bindBuild(snapshot, artifact)
            ToolWorkshopJobState.TESTED -> bindTests(snapshot, artifact)
            ToolWorkshopJobState.SECURITY_VALIDATED -> bindSecurity(snapshot, artifact)
            ToolWorkshopJobState.VERIFIED -> bindVerification(snapshot, artifact)

            ToolWorkshopJobState.TRIAL_READY -> {
                val record = requireNotNull(tools.get(snapshot.toolId)) { "Trial-ready tool missing" }
                require(record.state == GeneratedToolState.TRIAL) {
                    "Trial-ready artifact requires TRIAL tool state"
                }
                val advanced = jobs.advance(snapshot, target, artifact.fingerprint)
                ToolWorkshopStageResult.TrialReady(advanced, record)
            }

            ToolWorkshopJobState.REQUESTED,
            ToolWorkshopJobState.REJECTED,
            ToolWorkshopJobState.INTERRUPTED -> error("Cannot bind non-stage artifact $target")
        }
    }

    private suspend fun bindBuild(
        snapshot: ToolWorkshopJobSnapshot,
        artifact: ToolWorkshopStageArtifact,
    ): ToolWorkshopStageResult {
        val build = ToolWorkshopStagePayloadCodec.decodeBuild(artifact.payload)
        if (!build.success || build.artifactRef.isNullOrBlank() || build.buildHash.isNullOrBlank()) {
            val reason = build.diagnostics.joinToString(";").ifBlank { "build-failed" }
            rejectGeneratedToolIfPossible(snapshot.toolId, reason)
            val rejected = jobs.reject(snapshot, reason)
            return ToolWorkshopStageResult.Rejected(rejected, reason)
        }
        ensureBuiltTool(snapshot, build)
        return ToolWorkshopStageResult.Advanced(
            jobs.advance(snapshot, ToolWorkshopJobState.BUILT, artifact.fingerprint)
        )
    }

    private suspend fun bindTests(
        snapshot: ToolWorkshopJobSnapshot,
        artifact: ToolWorkshopStageArtifact,
    ): ToolWorkshopStageResult {
        val tests = ToolWorkshopStagePayloadCodec.decodeTest(artifact.payload)
        if (!tests.success || tests.failed > 0) {
            val reason = tests.diagnostics.joinToString(";").ifBlank { "tests-failed:${tests.failed}" }
            rejectGeneratedToolIfPossible(snapshot.toolId, reason)
            val rejected = jobs.reject(snapshot, reason)
            return ToolWorkshopStageResult.Rejected(rejected, reason)
        }
        ensureToolState(snapshot.toolId, GeneratedToolState.TESTED, "tests-passed:${tests.passed}")
        return ToolWorkshopStageResult.Advanced(
            jobs.advance(snapshot, ToolWorkshopJobState.TESTED, artifact.fingerprint)
        )
    }

    private suspend fun bindSecurity(
        snapshot: ToolWorkshopJobSnapshot,
        artifact: ToolWorkshopStageArtifact,
    ): ToolWorkshopStageResult {
        val security = ToolWorkshopStagePayloadCodec.decodeSecurity(artifact.payload)
        if (!security.accepted) {
            val reason = security.violations.joinToString(";").ifBlank { "security-rejected" }
            rejectGeneratedToolIfPossible(snapshot.toolId, reason)
            val rejected = jobs.reject(snapshot, reason)
            return ToolWorkshopStageResult.Rejected(rejected, reason)
        }
        return ToolWorkshopStageResult.Advanced(
            jobs.advance(snapshot, ToolWorkshopJobState.SECURITY_VALIDATED, artifact.fingerprint)
        )
    }

    private suspend fun bindVerification(
        snapshot: ToolWorkshopJobSnapshot,
        artifact: ToolWorkshopStageArtifact,
    ): ToolWorkshopStageResult {
        val verification = ToolWorkshopStagePayloadCodec.decodeVerification(artifact.payload)
        if (!verification.verified) {
            val reason = verification.diagnostics.joinToString(";").ifBlank {
                "capability-verification-failed"
            }
            rejectGeneratedToolIfPossible(snapshot.toolId, reason)
            val rejected = jobs.reject(snapshot, reason)
            return ToolWorkshopStageResult.Rejected(rejected, reason)
        }
        ensureToolState(
            snapshot.toolId,
            GeneratedToolState.VERIFIED,
            "capability-verified",
            verification.confidence,
        )
        return ToolWorkshopStageResult.Advanced(
            jobs.advance(snapshot, ToolWorkshopJobState.VERIFIED, artifact.fingerprint)
        )
    }

    private suspend fun ensureBuiltTool(
        snapshot: ToolWorkshopJobSnapshot,
        build: ToolBuildResult,
    ) {
        val source = loadSource(snapshot.definition.id)
        val buildHash = requireNotNull(build.buildHash)
        generatedArtifacts?.let { repository ->
            val artifact = GeneratedToolArtifact.create(
                toolId = snapshot.toolId,
                canonicalProgram = source.source,
                createdAt = snapshot.definition.createdAt,
            )
            require(artifact.sourceHash == build.sourceHash) {
                "V11 generated artifact source hash mismatch"
            }
            require(artifact.buildHash == buildHash) {
                "V11 generated artifact build hash mismatch"
            }
            repository.persist(artifact)
        }

        if (tools.get(snapshot.toolId) == null) {
            val specification = loadSpecification(snapshot.definition.id)
            tools.register(
                GeneratedToolRecord(
                    manifest = GeneratedToolManifest(
                        toolId = snapshot.toolId,
                        sourceCapability = specification.requiredCapability.capabilityId,
                        sourceHash = build.sourceHash,
                        buildHash = buildHash,
                        permissions = specification.allowedPermissions,
                        generatedAt = snapshot.definition.createdAt,
                        requiredInputs = specification.requiredCapability.requiredInputs,
                        requiredOutputs = specification.requiredCapability.requiredOutputs,
                    ),
                    state = GeneratedToolState.GENERATED,
                )
            )
        }
        ensureToolState(snapshot.toolId, GeneratedToolState.BUILT, "build-succeeded")
    }

    private suspend fun ensureToolState(
        toolId: String,
        target: GeneratedToolState,
        message: String,
        confidence: Double? = null,
    ) {
        val current = requireNotNull(tools.get(toolId)) { "Generated tool $toolId missing" }
        if (current.state == target) {
            if (confidence != null) require(current.verificationConfidence == confidence)
            return
        }
        val order = listOf(
            GeneratedToolState.GENERATED,
            GeneratedToolState.BUILT,
            GeneratedToolState.TESTED,
            GeneratedToolState.VERIFIED,
        )
        val currentIndex = order.indexOf(current.state)
        val targetIndex = order.indexOf(target)
        require(currentIndex >= 0 && targetIndex >= 0 && currentIndex < targetIndex) {
            "Cannot recover generated tool state ${current.state} to $target"
        }
        var state = current
        for (index in currentIndex + 1..targetIndex) {
            val next = order[index]
            state = tools.transition(
                toolId,
                next,
                confidence = if (next == target) confidence else null,
                message = if (next == target) message else "v11-recovered-${next.name.lowercase()}",
            )
        }
        require(state.state == target)
    }

    private suspend fun rejectGeneratedToolIfPossible(toolId: String, reason: String) {
        val record = tools.get(toolId) ?: return
        if (record.state == GeneratedToolState.REJECTED) return
        if (
            record.state in setOf(
                GeneratedToolState.GENERATED,
                GeneratedToolState.BUILT,
                GeneratedToolState.TESTED,
                GeneratedToolState.VERIFIED,
                GeneratedToolState.TRIAL,
            )
        ) {
            tools.transition(toolId, GeneratedToolState.REJECTED, message = reason)
        }
    }

    /** Reconciles the old crash window: ledger advanced but its stage reservation stayed RESERVED. */
    private suspend fun settleDurableCurrentStageIfNeeded(snapshot: ToolWorkshopJobSnapshot) {
        if (snapshot.state == ToolWorkshopJobState.REQUESTED) return
        if (snapshot.state == ToolWorkshopJobState.REJECTED || snapshot.state == ToolWorkshopJobState.INTERRUPTED) return
        val accountId = accountId(snapshot.definition.id, snapshot.state)
        val account = budgets.currentOrNull(accountId) ?: return
        val reservation = account.reservations.singleOrNull {
            it.idempotencyKey == idempotencyKey(snapshot.definition.id, snapshot.state)
        } ?: return
        if (reservation.state == ResourceBudgetReservationState.RESERVED) {
            val artifact = requireNotNull(stageArtifacts.load(snapshot.definition.id, snapshot.state)) {
                "Durable ToolWorkshop stage has reserved budget but no stage artifact"
            }
            require(snapshot.stageFingerprint == artifact.fingerprint) {
                "ToolWorkshop ledger/artifact fingerprint mismatch during settlement recovery"
            }
            budgets.commit(accountId, reservation.id, reservation.reserved)
        }
    }

    /** Reconciles the new crash window: artifact persisted, but settlement/ledger binding did not finish. */
    private suspend fun settlePersistedTargetStage(
        snapshot: ToolWorkshopJobSnapshot,
        target: ToolWorkshopJobState,
        artifact: ToolWorkshopStageArtifact,
        profile: ToolWorkshopExecutionProfile,
    ) {
        require(artifact.jobId == snapshot.definition.id && artifact.stage == target)
        val accountId = accountId(snapshot.definition.id, target)
        val account = requireNotNull(budgets.currentOrNull(accountId)) {
            "Persisted ToolWorkshop stage artifact has no durable V16 account"
        }
        require(account.quota == profile.hardQuota) {
            "Persisted ToolWorkshop stage changed its hard resource quota"
        }
        val reservation = requireNotNull(
            account.reservations.singleOrNull {
                it.idempotencyKey == idempotencyKey(snapshot.definition.id, target)
            }
        ) { "Persisted ToolWorkshop stage artifact has no durable V16 reservation" }
        val requested = profile.requested(target)
        require(reservation.reserved == requested) {
            "Persisted ToolWorkshop stage changed its reserved resource envelope"
        }
        settle(accountId, reservation, requested)
    }

    private suspend fun reserve(
        accountId: ResourceBudgetAccountId,
        jobId: ToolWorkshopJobId,
        stage: ToolWorkshopJobState,
        requested: ResourceBudgetUsage,
    ): ResourceBudgetReservation? = when (
        val result = budgets.reserve(accountId, idempotencyKey(jobId, stage), requested)
    ) {
        is ResourceBudgetReservationResult.Denied -> null
        is ResourceBudgetReservationResult.Reserved -> result.reservation
        is ResourceBudgetReservationResult.Existing -> result.reservation
    }

    private suspend fun settle(
        accountId: ResourceBudgetAccountId,
        reservation: ResourceBudgetReservation,
        actual: ResourceBudgetUsage,
    ) {
        when (reservation.state) {
            ResourceBudgetReservationState.RESERVED -> budgets.commit(accountId, reservation.id, actual)
            ResourceBudgetReservationState.COMMITTED -> require(reservation.settledUsage == actual)
            ResourceBudgetReservationState.RELEASED -> error(
                "Completed ToolWorkshop stage has released V16 reservation"
            )
        }
    }

    private suspend fun loadSpecification(jobId: ToolWorkshopJobId): ToolSpecification =
        ToolWorkshopStagePayloadCodec.decodeSpecification(
            requireArtifact(jobId, ToolWorkshopJobState.SPECIFIED).payload
        )

    private suspend fun loadDesign(jobId: ToolWorkshopJobId): ToolDesign =
        ToolWorkshopStagePayloadCodec.decodeDesign(
            requireArtifact(jobId, ToolWorkshopJobState.DESIGNED).payload
        )

    private suspend fun loadSource(jobId: ToolWorkshopJobId): GeneratedSource =
        ToolWorkshopStagePayloadCodec.decodeSource(
            requireArtifact(jobId, ToolWorkshopJobState.IMPLEMENTED).payload
        )

    private suspend fun loadBuild(jobId: ToolWorkshopJobId): ToolBuildResult =
        ToolWorkshopStagePayloadCodec.decodeBuild(
            requireArtifact(jobId, ToolWorkshopJobState.BUILT).payload
        )

    private suspend fun loadTests(jobId: ToolWorkshopJobId): ToolTestResult =
        ToolWorkshopStagePayloadCodec.decodeTest(
            requireArtifact(jobId, ToolWorkshopJobState.TESTED).payload
        )

    private suspend fun requireArtifact(
        jobId: ToolWorkshopJobId,
        stage: ToolWorkshopJobState,
    ): ToolWorkshopStageArtifact = requireNotNull(stageArtifacts.load(jobId, stage)) {
        "ToolWorkshop stage $stage has no durable artifact"
    }

    private fun nextStage(state: ToolWorkshopJobState): ToolWorkshopJobState = when (state) {
        ToolWorkshopJobState.REQUESTED -> ToolWorkshopJobState.SPECIFIED
        ToolWorkshopJobState.SPECIFIED -> ToolWorkshopJobState.DESIGNED
        ToolWorkshopJobState.DESIGNED -> ToolWorkshopJobState.IMPLEMENTED
        ToolWorkshopJobState.IMPLEMENTED -> ToolWorkshopJobState.BUILT
        ToolWorkshopJobState.BUILT -> ToolWorkshopJobState.TESTED
        ToolWorkshopJobState.TESTED -> ToolWorkshopJobState.SECURITY_VALIDATED
        ToolWorkshopJobState.SECURITY_VALIDATED -> ToolWorkshopJobState.VERIFIED
        ToolWorkshopJobState.VERIFIED -> ToolWorkshopJobState.TRIAL_READY
        ToolWorkshopJobState.TRIAL_READY,
        ToolWorkshopJobState.REJECTED,
        ToolWorkshopJobState.INTERRUPTED -> error("Terminal ToolWorkshop state has no next stage")
    }

    private fun ToolWorkshopJobDefinition.toGap(): CapabilityGap = CapabilityGap(
        requirement = CapabilityRequirement(
            capabilityId = capabilityId,
            severity = severity,
            requiredInputs = requiredInputs,
            requiredOutputs = requiredOutputs,
        ),
        type = gapType,
        candidateProviderIds = candidateProviderIds,
    )

    private fun accountId(jobId: ToolWorkshopJobId, stage: ToolWorkshopJobState) =
        ResourceBudgetAccountId("tool-workshop:${jobId.value}:${stage.name.lowercase()}")

    private fun idempotencyKey(jobId: ToolWorkshopJobId, stage: ToolWorkshopJobState) =
        "${jobId.value}:${stage.name}"

    private fun stageResource(jobId: ToolWorkshopJobId, stage: ToolWorkshopJobState) =
        "tool-workshop:${jobId.value}:${stage.name.lowercase()}"
}
