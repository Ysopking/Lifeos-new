package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.buildstudio.BuildStudioExpansionOutcomePhoton
import app.lifeos.core.runtime.buildstudio.BuildStudioExpansionRequest
import app.lifeos.core.runtime.buildstudio.BuildStudioHostProcessRegistry
import app.lifeos.core.runtime.buildstudio.BuildStudioResult
import app.lifeos.core.runtime.capability.CapabilityGapPhoton
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.GeneratedCapabilityCandidatePhoton
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeProcessRegistry
import app.lifeos.core.runtime.capability.ToolWorkshopJobSnapshot
import app.lifeos.core.runtime.genesis.GenesisCoordinator
import app.lifeos.core.runtime.genesis.GenesisHandoffTarget
import app.lifeos.core.runtime.genesis.GenesisProposal
import app.lifeos.core.runtime.genesis.GenesisProposalPhoton
import app.lifeos.core.runtime.genesis.GenesisRequest
import app.lifeos.core.runtime.genesis.GenesisResult
import app.lifeos.core.runtime.genesis.GenesisTriggerSource
import app.lifeos.core.runtime.trace.DecisionTraceRuntimeRegistry
import kotlinx.coroutines.CancellationException

/**
 * Productive guarded self-expansion loop.
 *
 * One shared CapabilityRegistry remains provider truth. Every missing capability is first persisted as
 * immutable gap evidence, Genesis selects the smallest safe handoff, and only that handoff may enter
 * ToolWorkshop or an authorized BuildStudio host. Every candidate/evidence object stays explicitly
 * non-activating; canary, Owner Policy and EvolutionPromotionBridge remain the only activation path.
 */
object GenesisCapabilityExpansionRuntime {
    suspend fun process(context: GoalActionContext): String {
        val registry = GeneratedToolRuntimeProcessRegistry.capabilities()
            ?: return "genesis-unavailable:capability-registry-not-installed"
        val genesis = GenesisCoordinator(registry)
        val workshopCapabilities = linkedSetOf<CapabilityId>()
        val gapPhotonIds = linkedMapOf<CapabilityId, PhotonId>()
        val reasons = mutableListOf<String>()

        for (gap in context.routing.blockingGaps.sortedBy { it.requirement.capabilityId.value }) {
            val capabilityId = gap.requirement.capabilityId
            val gapPhoton = CapabilityGapPhoton.create(
                gap = gap,
                sourcePhoton = context.sourcePhoton,
                goalPhotonId = context.goalPhotonId,
                goalPhotonRevision = context.goalPhotonRevision,
            )
            persistEvidence(gapPhoton, "Capability gap Photon changed during productive ingestion")
            gapPhotonIds[capabilityId] = gapPhoton.id

            val requiresBuildStudio = capabilityId == BuildStudioHostProcessRegistry.CAPABILITY_ID
            val request = GenesisRequest(
                requirement = gap.requirement,
                triggerSource = GenesisTriggerSource.CAPABILITY_GAP,
                query = null,
                evidenceRefs = setOf(
                    context.sourcePhoton.id.value,
                    context.goalPhotonId.value,
                    gapPhoton.id.value,
                ),
                unresolvedCount = 1,
                protectedRoot = false,
                codeToolAllowed = !requiresBuildStudio,
                moduleAllowed = true,
            )

            when (val result = genesis.propose(request)) {
                is GenesisResult.Proposed -> {
                    val proposal = result.proposal
                    val proposalPhoton = GenesisProposalPhoton.create(
                        proposal = proposal,
                        sourcePhoton = context.sourcePhoton,
                        goalPhotonId = context.goalPhotonId,
                    )
                    persistEvidence(proposalPhoton, "Genesis proposal Photon changed during productive ingestion")
                    reasons += "genesis:${capabilityId.value}:${proposal.handoff.target.name}"
                    when (proposal.handoff.target) {
                        GenesisHandoffTarget.TOOL_WORKSHOP -> workshopCapabilities += capabilityId
                        GenesisHandoffTarget.BUILD_STUDIO -> reasons += processBuildStudio(
                            context = context,
                            proposal = proposal,
                            gapPhotonId = gapPhoton.id,
                        )
                        else -> Unit
                    }
                }
                is GenesisResult.Blocked -> reasons +=
                    "genesis-blocked:${capabilityId.value}:${result.reasons.joinToString("+")}"
                is GenesisResult.NoAction -> reasons +=
                    "genesis-no-action:${capabilityId.value}:${result.reason}"
            }
        }

        if (workshopCapabilities.isNotEmpty()) {
            val workshopReason = try {
                when (
                    val result = AutonomousToolWorkshopRuntimeRegistry.processIfInstalled(
                        context = context,
                        allowedCapabilityIds = workshopCapabilities,
                    )
                ) {
                    null -> "workshop-not-installed"
                    AutonomousToolWorkshopResult.NotNeeded -> "workshop-not-needed"
                    is AutonomousToolWorkshopResult.Progressed -> {
                        recordWorkshop(context, result.jobs)
                        persistWorkshopCandidates(context, result.jobs, gapPhotonIds)
                        result.jobs.joinToString(",") {
                            "${it.definition.capabilityId.value}:${it.state.name}"
                        }.ifBlank { "workshop-progressed" }
                    }
                    is AutonomousToolWorkshopResult.Blocked -> {
                        recordWorkshop(context, result.jobs, result.reason)
                        persistWorkshopCandidates(context, result.jobs, gapPhotonIds)
                        "workshop-blocked:${result.reason}"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "workshop-failed:${error::class.simpleName}:${error.message.orEmpty().take(120)}"
            }
            reasons += workshopReason
        }

        return reasons.joinToString(";").ifBlank { "genesis-no-expansion" }
    }

    private suspend fun processBuildStudio(
        context: GoalActionContext,
        proposal: GenesisProposal,
        gapPhotonId: PhotonId,
    ): String {
        val request = BuildStudioExpansionRequest(
            gap = proposal.gap,
            genesisHandoff = proposal.handoff,
            sourcePhotonId = context.sourcePhoton.id,
            sourcePhotonRevision = context.sourcePhoton.revision,
            goalPhotonId = context.goalPhotonId,
            goalPhotonRevision = context.goalPhotonRevision,
            gapPhotonId = gapPhotonId,
        )
        val requestPhoton = request.toPhoton(context.sourcePhoton)
        persistEvidence(requestPhoton, "BuildStudio expansion request changed during productive ingestion")

        val result = try {
            BuildStudioHostProcessRegistry.expand(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            BuildStudioResult.Failed(
                stage = "host",
                reason = "${error::class.simpleName ?: "Exception"}:${error.message.orEmpty().take(120)}",
            )
        }
        val outcome = BuildStudioExpansionOutcomePhoton.create(
            request = request,
            requestPhotonId = requestPhoton.id,
            result = result,
            sourcePhoton = context.sourcePhoton,
        )
        persistEvidence(outcome, "BuildStudio expansion outcome changed during productive ingestion")

        return when (result) {
            is BuildStudioResult.CandidateReady -> {
                val candidate = GeneratedCapabilityCandidatePhoton.fromBuildStudio(
                    result = result,
                    capabilityId = proposal.gap.requirement.capabilityId,
                    requiredInputs = proposal.gap.requirement.requiredInputs,
                    requiredOutputs = proposal.gap.requirement.requiredOutputs,
                    sourcePhoton = context.sourcePhoton,
                    gapPhotonId = gapPhotonId,
                    requestPhotonId = requestPhoton.id,
                )
                persistEvidence(candidate, "BuildStudio candidate evidence changed during productive ingestion")
                "buildstudio-candidate-ready:${result.candidate.id}"
            }
            is BuildStudioResult.Rejected ->
                "buildstudio-rejected:${result.stage}:${result.failures.joinToString("+")}"
            is BuildStudioResult.Failed ->
                "buildstudio-failed:${result.stage}:${result.reason}"
        }
    }

    private suspend fun persistWorkshopCandidates(
        context: GoalActionContext,
        jobs: List<ToolWorkshopJobSnapshot>,
        gapPhotonIds: Map<CapabilityId, PhotonId>,
    ) {
        jobs.forEach { snapshot ->
            val gapPhotonId = requireNotNull(gapPhotonIds[snapshot.definition.capabilityId]) {
                "ToolWorkshop candidate lost its durable capability gap lineage"
            }
            val candidate = GeneratedCapabilityCandidatePhoton.fromToolWorkshop(
                snapshot = snapshot,
                sourcePhoton = context.sourcePhoton,
                gapPhotonId = gapPhotonId,
            )
            persistEvidence(candidate, "ToolWorkshop candidate evidence changed during productive ingestion")
        }
    }

    private suspend fun persistEvidence(expected: Photon, mismatchMessage: String) {
        val stored = LifeOsAutomationPhotonBridge.ingestIfInstalled(expected)
        require(stored == null || stored == expected) { mismatchMessage }
    }

    private suspend fun recordWorkshop(
        context: GoalActionContext,
        jobs: List<ToolWorkshopJobSnapshot>,
        explicitReason: String? = null,
    ) {
        val traces = DecisionTraceRuntimeRegistry.currentOrNull() ?: return
        jobs.forEach { snapshot ->
            traces.recordToolWorkshop(
                goalPhotonId = context.goalPhotonId,
                goalPhotonRevision = context.goalPhotonRevision,
                recordedAt = context.sourcePhoton.provenance.createdAt,
                snapshot = snapshot,
                explicitReason = explicitReason,
            )
        }
    }
}
