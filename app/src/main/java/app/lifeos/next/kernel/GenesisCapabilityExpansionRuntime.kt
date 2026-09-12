package app.lifeos.next.kernel

import app.lifeos.core.runtime.buildstudio.BuildStudioHostProcessRegistry
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeProcessRegistry
import app.lifeos.core.runtime.genesis.GenesisCoordinator
import app.lifeos.core.runtime.genesis.GenesisHandoffTarget
import app.lifeos.core.runtime.genesis.GenesisProposalPhoton
import app.lifeos.core.runtime.genesis.GenesisRequest
import app.lifeos.core.runtime.genesis.GenesisResult
import app.lifeos.core.runtime.genesis.GenesisTriggerSource
import app.lifeos.core.runtime.trace.DecisionTraceRuntimeRegistry
import kotlinx.coroutines.CancellationException

/**
 * Productive capability-expansion loop: one shared CapabilityRegistry -> Genesis smallest-safe
 * proposal -> durable proposal Photon -> only then the selected downstream expansion path.
 *
 * H02/Genesis remains proposal-only. BUILD_STUDIO is never executed as a bounded generated tool;
 * TOOL_WORKSHOP is the only handoff that may enter AutonomousToolWorkshopRuntime. Existing owner,
 * resource, trial, canary and promotion gates remain downstream and authoritative.
 */
object GenesisCapabilityExpansionRuntime {
    suspend fun process(context: GoalActionContext): String {
        val registry = GeneratedToolRuntimeProcessRegistry.capabilities()
            ?: return "genesis-unavailable:capability-registry-not-installed"
        val genesis = GenesisCoordinator(registry)
        val workshopCapabilities = linkedSetOf<CapabilityId>()
        val reasons = mutableListOf<String>()

        for (gap in context.routing.blockingGaps.sortedBy { it.requirement.capabilityId.value }) {
            val capabilityId = gap.requirement.capabilityId
            val requiresBuildStudio = capabilityId == BuildStudioHostProcessRegistry.CAPABILITY_ID
            val request = GenesisRequest(
                requirement = gap.requirement,
                triggerSource = GenesisTriggerSource.CAPABILITY_GAP,
                query = null,
                evidenceRefs = setOf(context.sourcePhoton.id.value, context.goalPhotonId.value),
                unresolvedCount = 1,
                protectedRoot = false,
                codeToolAllowed = !requiresBuildStudio,
                moduleAllowed = true,
            )

            when (val result = genesis.propose(request)) {
                is GenesisResult.Proposed -> {
                    val proposal = result.proposal
                    val photon = GenesisProposalPhoton.create(
                        proposal = proposal,
                        sourcePhoton = context.sourcePhoton,
                        goalPhotonId = context.goalPhotonId,
                    )
                    val stored = LifeOsAutomationPhotonBridge.ingestIfInstalled(photon)
                    require(stored == null || stored == photon) {
                        "Genesis proposal Photon changed during productive ingestion"
                    }
                    reasons += "genesis:${capabilityId.value}:${proposal.handoff.target.name}"
                    if (proposal.handoff.target == GenesisHandoffTarget.TOOL_WORKSHOP) {
                        workshopCapabilities += capabilityId
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
                        result.jobs.joinToString(",") {
                            "${it.definition.capabilityId.value}:${it.state.name}"
                        }.ifBlank { "workshop-progressed" }
                    }
                    is AutonomousToolWorkshopResult.Blocked -> {
                        recordWorkshop(context, result.jobs, result.reason)
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

    private suspend fun recordWorkshop(
        context: GoalActionContext,
        jobs: List<app.lifeos.core.runtime.capability.ToolWorkshopJobSnapshot>,
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
