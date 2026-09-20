package app.lifeos.core.runtime.genesis

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.capability.CapabilityComposer
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapDetector
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.CompositeCapabilityPlan
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType

enum class GenesisTriggerSource {
    CAPABILITY_GAP,
    DEEP_SEARCH_UNRESOLVED,
    USER_REQUEST,
    HEALTH_RECOVERY,
}

enum class GenesisSolutionKind {
    PROCEDURE,
    CAPABILITY_COMPOSITION,
    CONNECTOR_WRAPPER,
    QUERY_ANALYSIS_PIPELINE,
    CODE_TOOL,
    MODULE_IMPLEMENTATION,
}

data class GenesisRequest(
    val requirement: CapabilityRequirement,
    val availableContracts: Set<String> = emptySet(),
    val triggerSource: GenesisTriggerSource = GenesisTriggerSource.CAPABILITY_GAP,
    val query: String? = null,
    val evidenceRefs: Set<String> = emptySet(),
    val unresolvedCount: Int = 1,
    val protectedRoot: Boolean = false,
    val codeToolAllowed: Boolean = true,
    val moduleAllowed: Boolean = true,
) {
    init {
        require(availableContracts.none { it.isBlank() }) { "Genesis contracts must not be blank" }
        require(query == null || query.isNotBlank()) { "Genesis query must not be blank" }
        require(evidenceRefs.none { it.isBlank() }) { "Genesis evidence refs must not be blank" }
        require(unresolvedCount > 0) { "Genesis unresolved count must be positive" }
    }

    val id: String = StableFieldIds.fingerprint(
        "genesis-request/v1",
        requirement.capabilityId.value,
        requirement.severity.name,
        triggerSource.name,
        query.orEmpty(),
        unresolvedCount.toString(),
        protectedRoot.toString(),
        codeToolAllowed.toString(),
        moduleAllowed.toString(),
        *requirement.requiredInputs.sorted().map { "required-input:$it" }.toTypedArray(),
        *requirement.requiredOutputs.sorted().map { "required-output:$it" }.toTypedArray(),
        *availableContracts.sorted().map { "available:$it" }.toTypedArray(),
        *evidenceRefs.sorted().map { "evidence:$it" }.toTypedArray(),
    )
}

data class GenesisTriggerDecision(
    val eligible: Boolean,
    val reason: String,
) {
    init { require(reason.isNotBlank()) }
}

fun interface GenesisTriggerPolicy {
    fun evaluate(request: GenesisRequest, gap: CapabilityGap): GenesisTriggerDecision
}

class DefaultGenesisTriggerPolicy(
    private val minimumUnresolvedCount: Int = 1,
) : GenesisTriggerPolicy {
    init { require(minimumUnresolvedCount > 0) }

    override fun evaluate(request: GenesisRequest, gap: CapabilityGap): GenesisTriggerDecision {
        if (request.unresolvedCount < minimumUnresolvedCount) {
            return GenesisTriggerDecision(false, "insufficient-unresolved-evidence")
        }
        if (
            gap.requirement.severity == GapSeverity.NON_BLOCKING &&
            request.triggerSource != GenesisTriggerSource.USER_REQUEST
        ) {
            return GenesisTriggerDecision(false, "non-blocking-gap-requires-user-request")
        }
        return GenesisTriggerDecision(true, "eligible-capability-gap")
    }
}

data class GenesisProcedureCandidate(
    val procedureId: String,
    val requiredInputs: Set<String> = emptySet(),
    val outputs: Set<String>,
    val reliability: Double,
    val verified: Boolean = true,
) {
    init {
        require(procedureId.isNotBlank()) { "Genesis procedure id must not be blank" }
        require(requiredInputs.none { it.isBlank() })
        require(outputs.none { it.isBlank() })
        require(reliability.isFinite() && reliability in 0.0..1.0)
    }
}

fun interface GenesisProcedureCatalog {
    suspend fun candidatesFor(
        request: GenesisRequest,
        gap: CapabilityGap,
    ): List<GenesisProcedureCandidate>

    companion object {
        val NONE = GenesisProcedureCatalog { _, _ -> emptyList() }
    }
}

fun interface GenesisQueryPipelinePolicy {
    fun canAddress(request: GenesisRequest, gap: CapabilityGap): Boolean

    companion object {
        val DEFAULT = GenesisQueryPipelinePolicy { request, _ ->
            request.query != null && request.requirement.requiredOutputs.isNotEmpty()
        }
    }
}

interface GenesisSafetyPolicy {
    fun isProtectedRoot(requirement: CapabilityRequirement): Boolean
    fun codeToolSafe(request: GenesisRequest, gap: CapabilityGap): Boolean
}

object DefaultGenesisSafetyPolicy : GenesisSafetyPolicy {
    private val protectedTokens = listOf(
        "crypto",
        "keystore",
        "task.ownership",
        "task.cas",
        "runtime.protection",
        "signing",
        "update.trust",
    )

    override fun isProtectedRoot(requirement: CapabilityRequirement): Boolean {
        val id = requirement.capabilityId.value.lowercase()
        return protectedTokens.any(id::contains)
    }

    override fun codeToolSafe(request: GenesisRequest, gap: CapabilityGap): Boolean =
        request.codeToolAllowed &&
            !request.protectedRoot &&
            !isProtectedRoot(gap.requirement)
}

data class GenesisSolutionCandidate(
    val kind: GenesisSolutionKind,
    val referenceId: String,
    val reliability: Double,
    val expectedCost: Double,
    val rationale: String,
    val requiresExplicitApproval: Boolean,
    val composition: CompositeCapabilityPlan? = null,
) {
    init {
        require(referenceId.isNotBlank())
        require(reliability.isFinite() && reliability in 0.0..1.0)
        require(expectedCost.isFinite() && expectedCost >= 0.0)
        require(rationale.isNotBlank())
        require(composition == null || kind == GenesisSolutionKind.CAPABILITY_COMPOSITION)
    }
}

data class GenesisProposal(
    val id: String,
    val requestId: String,
    val gap: CapabilityGap,
    val selected: GenesisSolutionCandidate,
    val consideredKinds: List<GenesisSolutionKind>,
    val evidenceRefs: Set<String>,
    val handoff: GenesisHandoff,
) {
    init {
        require(id.isNotBlank())
        require(requestId.isNotBlank())
        require(consideredKinds.isNotEmpty())
        require(consideredKinds.distinct().size == consideredKinds.size)
        require(evidenceRefs.none { it.isBlank() })
        require(handoff.proposalId == id)
        require(!handoff.activationAllowed) { "Genesis proposal cannot authorize activation" }
    }
}

sealed interface GenesisResult {
    data class NoAction(val reason: String) : GenesisResult {
        init { require(reason.isNotBlank()) }
    }

    data class Blocked(
        val gap: CapabilityGap,
        val reasons: List<String>,
    ) : GenesisResult {
        init { require(reasons.isNotEmpty() && reasons.none { it.isBlank() }) }
    }

    data class Proposed(val proposal: GenesisProposal) : GenesisResult
}

fun interface GenesisProposalSink {
    suspend fun record(proposal: GenesisProposal)

    companion object {
        val NONE = GenesisProposalSink { }
    }
}

/**
 * H02 chooses the smallest safe solution for a real capability gap and emits a proposal only.
 * It never invokes ToolWorkshop, BuildStudio, a connector, DeepSearch, or a module activation path.
 */
class GenesisCoordinator(
    private val registry: CapabilityRegistry,
    private val gapDetector: CapabilityGapDetector = CapabilityGapDetector(registry),
    private val procedures: GenesisProcedureCatalog = GenesisProcedureCatalog.NONE,
    private val queryPipelinePolicy: GenesisQueryPipelinePolicy = GenesisQueryPipelinePolicy.DEFAULT,
    private val triggerPolicy: GenesisTriggerPolicy = DefaultGenesisTriggerPolicy(),
    private val safetyPolicy: GenesisSafetyPolicy = DefaultGenesisSafetyPolicy,
    private val sink: GenesisProposalSink = GenesisProposalSink.NONE,
) {
    suspend fun propose(request: GenesisRequest): GenesisResult {
        val gap = gapDetector.detect(request.requirement)
            ?: return GenesisResult.NoAction("capability-already-satisfied")

        val trigger = triggerPolicy.evaluate(request, gap)
        if (!trigger.eligible) {
            return GenesisResult.Blocked(gap, listOf(trigger.reason))
        }

        val considered = mutableListOf<GenesisSolutionKind>()

        considered += GenesisSolutionKind.PROCEDURE
        selectProcedure(request, gap)?.let { return emit(request, gap, it, considered) }

        considered += GenesisSolutionKind.CAPABILITY_COMPOSITION
        selectComposition(request)?.let { return emit(request, gap, it, considered) }

        considered += GenesisSolutionKind.CONNECTOR_WRAPPER
        selectConnector(request)?.let { return emit(request, gap, it, considered) }

        considered += GenesisSolutionKind.QUERY_ANALYSIS_PIPELINE
        if (queryPipelinePolicy.canAddress(request, gap)) {
            return emit(
                request,
                gap,
                GenesisSolutionCandidate(
                    kind = GenesisSolutionKind.QUERY_ANALYSIS_PIPELINE,
                    referenceId = "deepsearch:${request.id}",
                    reliability = 0.0,
                    expectedCost = 0.0,
                    rationale = "bounded-query-analysis-before-code-generation",
                    requiresExplicitApproval = false,
                ),
                considered,
            )
        }

        considered += GenesisSolutionKind.CODE_TOOL
        if (safetyPolicy.codeToolSafe(request, gap)) {
            return emit(
                request,
                gap,
                GenesisSolutionCandidate(
                    kind = GenesisSolutionKind.CODE_TOOL,
                    referenceId = "tool-proposal:${request.id}",
                    reliability = 0.0,
                    expectedCost = 1.0,
                    rationale = "no-smaller-existing-solution;propose-sandboxed-code-tool",
                    requiresExplicitApproval = true,
                ),
                considered,
            )
        }

        considered += GenesisSolutionKind.MODULE_IMPLEMENTATION
        if (request.moduleAllowed) {
            val protected = request.protectedRoot || safetyPolicy.isProtectedRoot(request.requirement)
            return emit(
                request,
                gap,
                GenesisSolutionCandidate(
                    kind = GenesisSolutionKind.MODULE_IMPLEMENTATION,
                    referenceId = "module-proposal:${request.id}",
                    reliability = 0.0,
                    expectedCost = 1.0,
                    rationale = if (protected) {
                        "protected-root-requires-reviewed-module-path"
                    } else {
                        "code-tool-unavailable;propose-reviewed-module-path"
                    },
                    requiresExplicitApproval = true,
                ),
                considered,
            )
        }

        return GenesisResult.Blocked(gap, listOf("no-safe-solution-path"))
    }

    private suspend fun selectProcedure(
        request: GenesisRequest,
        gap: CapabilityGap,
    ): GenesisSolutionCandidate? = procedures.candidatesFor(request, gap)
        .asSequence()
        .filter { it.verified }
        .filter { request.availableContracts.containsAll(it.requiredInputs) }
        .filter { it.outputs.containsAll(request.requirement.requiredOutputs) }
        .sortedWith(
            compareByDescending<GenesisProcedureCandidate> { it.reliability }
                .thenBy { it.procedureId }
        )
        .map { procedure ->
            GenesisSolutionCandidate(
                kind = GenesisSolutionKind.PROCEDURE,
                referenceId = procedure.procedureId,
                reliability = procedure.reliability,
                expectedCost = 0.0,
                rationale = "verified-existing-procedure",
                requiresExplicitApproval = false,
            )
        }
        .firstOrNull()

    private suspend fun selectComposition(request: GenesisRequest): GenesisSolutionCandidate? {
        if (request.requirement.requiredOutputs.isEmpty()) return null
        val internalProviders = registry.all(includeUnavailable = false)
            .filter { provider ->
                provider.providerType != ProviderType.CONNECTOR &&
                    provider.providerType != ProviderType.EXTERNAL_TOOL
            }
        if (internalProviders.isEmpty()) return null
        val safeRegistry = CapabilityRegistry(internalProviders)
        val plan = CapabilityComposer(safeRegistry).compose(
            availableContracts = request.availableContracts,
            requiredOutputs = request.requirement.requiredOutputs,
        ) ?: return null
        if (plan.steps.isEmpty()) return null
        return GenesisSolutionCandidate(
            kind = GenesisSolutionKind.CAPABILITY_COMPOSITION,
            referenceId = compositionId(plan),
            reliability = plan.expectedReliability.coerceIn(0.0, 1.0),
            expectedCost = plan.expectedCost,
            rationale = "existing-internal-capability-composition",
            requiresExplicitApproval = false,
            composition = plan,
        )
    }

    private suspend fun selectConnector(request: GenesisRequest): GenesisSolutionCandidate? {
        if (request.requirement.requiredOutputs.isEmpty()) return null
        val connector = registry.all(includeUnavailable = false)
            .asSequence()
            .filter { it.providerType == ProviderType.CONNECTOR }
            .filter { it.state == ProviderState.ACTIVE || it.state == ProviderState.DEGRADED }
            .filter { request.availableContracts.containsAll(it.contract.requiredInputs) }
            .filter { it.contract.outputs.containsAll(request.requirement.requiredOutputs) }
            .sortedWith(connectorOrder())
            .firstOrNull()
            ?: return null
        return GenesisSolutionCandidate(
            kind = GenesisSolutionKind.CONNECTOR_WRAPPER,
            referenceId = connector.providerId,
            reliability = connector.reliability,
            expectedCost = connector.cost,
            rationale = "existing-connector-wrapper",
            requiresExplicitApproval = true,
        )
    }

    private suspend fun emit(
        request: GenesisRequest,
        gap: CapabilityGap,
        selected: GenesisSolutionCandidate,
        considered: List<GenesisSolutionKind>,
    ): GenesisResult.Proposed {
        val proposalId = StableFieldIds.fingerprint(
            "genesis-proposal/v1",
            request.id,
            gap.type.name,
            selected.kind.name,
            selected.referenceId,
            java.lang.Double.toHexString(selected.reliability),
            java.lang.Double.toHexString(selected.expectedCost),
            selected.rationale,
            selected.requiresExplicitApproval.toString(),
            *considered.map { it.name }.toTypedArray(),
        )
        val target = handoffTarget(selected.kind)
        val handoff = GenesisHandoff(
            proposalId = proposalId,
            target = target,
            referenceId = selected.referenceId,
            payloadFingerprint = StableFieldIds.fingerprint(
                "genesis-handoff/v1",
                proposalId,
                target.name,
                selected.referenceId,
                request.requirement.capabilityId.value,
                *request.requirement.requiredInputs.sorted().toTypedArray(),
                *request.requirement.requiredOutputs.sorted().toTypedArray(),
            ),
            requiresExplicitApproval = selected.requiresExplicitApproval,
        )
        val proposal = GenesisProposal(
            id = proposalId,
            requestId = request.id,
            gap = gap,
            selected = selected,
            consideredKinds = considered.toList(),
            evidenceRefs = request.evidenceRefs.toSortedSet(),
            handoff = handoff,
        )
        sink.record(proposal)
        return GenesisResult.Proposed(proposal)
    }

    private fun compositionId(plan: CompositeCapabilityPlan): String = StableFieldIds.fingerprint(
        "genesis-composition/v1",
        *plan.steps.flatMap { descriptor ->
            listOf(
                descriptor.capabilityId.value,
                descriptor.providerId,
                descriptor.state.name,
            )
        }.toTypedArray(),
    )

    private fun connectorOrder(): Comparator<CapabilityDescriptor> =
        compareByDescending<CapabilityDescriptor> { it.reliability }
            .thenBy { it.cost }
            .thenBy { it.providerId }

    private fun handoffTarget(kind: GenesisSolutionKind): GenesisHandoffTarget = when (kind) {
        GenesisSolutionKind.PROCEDURE -> GenesisHandoffTarget.PROCEDURE
        GenesisSolutionKind.CAPABILITY_COMPOSITION -> GenesisHandoffTarget.CAPABILITY_ROUTER
        GenesisSolutionKind.CONNECTOR_WRAPPER -> GenesisHandoffTarget.CONNECTOR_GATEWAY
        GenesisSolutionKind.QUERY_ANALYSIS_PIPELINE -> GenesisHandoffTarget.DEEP_SEARCH
        GenesisSolutionKind.CODE_TOOL -> GenesisHandoffTarget.TOOL_WORKSHOP
        GenesisSolutionKind.MODULE_IMPLEMENTATION -> GenesisHandoffTarget.BUILD_STUDIO
    }
}
