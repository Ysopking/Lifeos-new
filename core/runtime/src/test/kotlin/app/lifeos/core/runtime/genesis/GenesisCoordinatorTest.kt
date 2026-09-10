package app.lifeos.core.runtime.genesis

import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GenesisCoordinatorTest {
    @Test
    fun `already satisfied capability produces no proposal`() = runTest {
        val requirement = requirement()
        val registry = CapabilityRegistry(
            listOf(
                provider(
                    capability = requirement.capabilityId,
                    providerId = "direct",
                    outputs = setOf("result"),
                )
            )
        )
        val captured = mutableListOf<GenesisProposal>()
        val coordinator = GenesisCoordinator(
            registry = registry,
            sink = GenesisProposalSink { captured += it },
        )

        val result = assertIs<GenesisResult.NoAction>(
            coordinator.propose(GenesisRequest(requirement, availableContracts = setOf("raw")))
        )

        assertEquals("capability-already-satisfied", result.reason)
        assertTrue(captured.isEmpty())
    }

    @Test
    fun `non blocking gap requires explicit user request`() = runTest {
        val requirement = requirement(severity = GapSeverity.NON_BLOCKING)
        val coordinator = GenesisCoordinator(CapabilityRegistry())

        val blocked = assertIs<GenesisResult.Blocked>(
            coordinator.propose(GenesisRequest(requirement, availableContracts = setOf("raw")))
        )
        val requested = assertIs<GenesisResult.Proposed>(
            coordinator.propose(
                GenesisRequest(
                    requirement = requirement,
                    availableContracts = setOf("raw"),
                    triggerSource = GenesisTriggerSource.USER_REQUEST,
                    query = "derive result from raw evidence",
                )
            )
        )

        assertEquals(listOf("non-blocking-gap-requires-user-request"), blocked.reasons)
        assertEquals(GenesisSolutionKind.QUERY_ANALYSIS_PIPELINE, requested.proposal.selected.kind)
    }

    @Test
    fun `verified procedure wins before every other available solution`() = runTest {
        val requirement = requirement()
        val registry = CapabilityRegistry(
            listOf(
                provider(
                    capability = CapabilityId("helper.compose"),
                    providerId = "internal-composer",
                    outputs = setOf("result"),
                ),
                provider(
                    capability = CapabilityId("helper.connector"),
                    providerId = "connector",
                    type = ProviderType.CONNECTOR,
                    outputs = setOf("result"),
                ),
            )
        )
        val procedures = GenesisProcedureCatalog { _, _ ->
            listOf(
                GenesisProcedureCandidate(
                    procedureId = "procedure-b",
                    requiredInputs = setOf("raw"),
                    outputs = setOf("result"),
                    reliability = 0.8,
                ),
                GenesisProcedureCandidate(
                    procedureId = "procedure-a",
                    requiredInputs = setOf("raw"),
                    outputs = setOf("result"),
                    reliability = 0.95,
                ),
            )
        }
        val result = assertIs<GenesisResult.Proposed>(
            GenesisCoordinator(registry, procedures = procedures).propose(
                GenesisRequest(
                    requirement = requirement,
                    availableContracts = setOf("raw"),
                    query = "query fallback",
                )
            )
        )

        assertEquals(GenesisSolutionKind.PROCEDURE, result.proposal.selected.kind)
        assertEquals("procedure-a", result.proposal.selected.referenceId)
        assertEquals(listOf(GenesisSolutionKind.PROCEDURE), result.proposal.consideredKinds)
        assertEquals(GenesisHandoffTarget.PROCEDURE, result.proposal.handoff.target)
        assertFalse(result.proposal.handoff.activationAllowed)
    }

    @Test
    fun `internal capability composition wins before connector query and code`() = runTest {
        val requirement = requirement()
        val registry = CapabilityRegistry(
            listOf(
                provider(
                    capability = CapabilityId("helper.compose"),
                    providerId = "internal",
                    requiredInputs = setOf("raw"),
                    outputs = setOf("result"),
                    reliability = 0.9,
                ),
                provider(
                    capability = CapabilityId("helper.connector"),
                    providerId = "connector",
                    type = ProviderType.CONNECTOR,
                    requiredInputs = setOf("raw"),
                    outputs = setOf("result"),
                    reliability = 0.99,
                ),
            )
        )

        val result = assertIs<GenesisResult.Proposed>(
            GenesisCoordinator(registry).propose(
                GenesisRequest(
                    requirement = requirement,
                    availableContracts = setOf("raw"),
                    query = "query fallback",
                )
            )
        )

        assertEquals(GenesisSolutionKind.CAPABILITY_COMPOSITION, result.proposal.selected.kind)
        assertEquals(listOf("internal"), result.proposal.selected.composition?.steps?.map { it.providerId })
        assertEquals(GenesisHandoffTarget.CAPABILITY_ROUTER, result.proposal.handoff.target)
        assertFalse(result.proposal.selected.requiresExplicitApproval)
    }

    @Test
    fun `connector cannot bypass explicit connector wrapper by being composed`() = runTest {
        val requirement = requirement()
        val registry = CapabilityRegistry(
            listOf(
                provider(
                    capability = CapabilityId("connector.search"),
                    providerId = "external-connector",
                    type = ProviderType.CONNECTOR,
                    requiredInputs = setOf("raw"),
                    outputs = setOf("result"),
                    reliability = 0.97,
                )
            )
        )

        val result = assertIs<GenesisResult.Proposed>(
            GenesisCoordinator(registry).propose(
                GenesisRequest(requirement, availableContracts = setOf("raw"))
            )
        )

        assertEquals(GenesisSolutionKind.CONNECTOR_WRAPPER, result.proposal.selected.kind)
        assertEquals("external-connector", result.proposal.selected.referenceId)
        assertEquals(GenesisHandoffTarget.CONNECTOR_GATEWAY, result.proposal.handoff.target)
        assertTrue(result.proposal.selected.requiresExplicitApproval)
        assertFalse(result.proposal.handoff.activationAllowed)
    }

    @Test
    fun `bounded query analysis is preferred before generated code`() = runTest {
        val requirement = requirement()
        val result = assertIs<GenesisResult.Proposed>(
            GenesisCoordinator(CapabilityRegistry()).propose(
                GenesisRequest(
                    requirement = requirement,
                    availableContracts = setOf("raw"),
                    query = "research a safe answer",
                )
            )
        )

        assertEquals(GenesisSolutionKind.QUERY_ANALYSIS_PIPELINE, result.proposal.selected.kind)
        assertEquals(GenesisHandoffTarget.DEEP_SEARCH, result.proposal.handoff.target)
        assertFalse(result.proposal.selected.requiresExplicitApproval)
        assertFalse(result.proposal.handoff.activationAllowed)
    }

    @Test
    fun `code tool is proposed only after smaller safe paths are unavailable`() = runTest {
        val requirement = requirement()
        val result = assertIs<GenesisResult.Proposed>(
            GenesisCoordinator(CapabilityRegistry()).propose(
                GenesisRequest(
                    requirement = requirement,
                    availableContracts = setOf("raw"),
                    query = null,
                )
            )
        )

        assertEquals(GenesisSolutionKind.CODE_TOOL, result.proposal.selected.kind)
        assertEquals(GenesisHandoffTarget.TOOL_WORKSHOP, result.proposal.handoff.target)
        assertTrue(result.proposal.selected.requiresExplicitApproval)
        assertFalse(result.proposal.handoff.activationAllowed)
        assertEquals(
            listOf(
                GenesisSolutionKind.PROCEDURE,
                GenesisSolutionKind.CAPABILITY_COMPOSITION,
                GenesisSolutionKind.CONNECTOR_WRAPPER,
                GenesisSolutionKind.QUERY_ANALYSIS_PIPELINE,
                GenesisSolutionKind.CODE_TOOL,
            ),
            result.proposal.consideredKinds,
        )
    }

    @Test
    fun `protected trust root never becomes code tool even when caller does not flag it`() = runTest {
        val protectedRequirement = CapabilityRequirement(
            capabilityId = CapabilityId("runtime.protection.repair"),
            severity = GapSeverity.CRITICAL,
            requiredInputs = setOf("state"),
            requiredOutputs = setOf("protected-state"),
        )
        val result = assertIs<GenesisResult.Proposed>(
            GenesisCoordinator(CapabilityRegistry()).propose(
                GenesisRequest(
                    requirement = protectedRequirement,
                    availableContracts = setOf("state"),
                    protectedRoot = false,
                    codeToolAllowed = true,
                    moduleAllowed = true,
                )
            )
        )

        assertEquals(GenesisSolutionKind.MODULE_IMPLEMENTATION, result.proposal.selected.kind)
        assertEquals(GenesisHandoffTarget.BUILD_STUDIO, result.proposal.handoff.target)
        assertTrue(result.proposal.selected.requiresExplicitApproval)
        assertEquals(
            "protected-root-requires-reviewed-module-path",
            result.proposal.selected.rationale,
        )
        assertFalse(result.proposal.handoff.activationAllowed)
    }

    @Test
    fun `no safe implementation path remains explicitly blocked`() = runTest {
        val protectedRequirement = CapabilityRequirement(
            capabilityId = CapabilityId("crypto.root.replace"),
            severity = GapSeverity.CRITICAL,
            requiredOutputs = setOf("root"),
        )
        val result = assertIs<GenesisResult.Blocked>(
            GenesisCoordinator(CapabilityRegistry()).propose(
                GenesisRequest(
                    requirement = protectedRequirement,
                    codeToolAllowed = true,
                    moduleAllowed = false,
                )
            )
        )

        assertEquals(listOf("no-safe-solution-path"), result.reasons)
    }

    @Test
    fun `minimum unresolved evidence policy blocks premature genesis`() = runTest {
        val result = assertIs<GenesisResult.Blocked>(
            GenesisCoordinator(
                registry = CapabilityRegistry(),
                triggerPolicy = DefaultGenesisTriggerPolicy(minimumUnresolvedCount = 3),
            ).propose(
                GenesisRequest(
                    requirement = requirement(),
                    availableContracts = setOf("raw"),
                    unresolvedCount = 2,
                )
            )
        )

        assertEquals(listOf("insufficient-unresolved-evidence"), result.reasons)
    }

    @Test
    fun `same inputs choose deterministic proposal and handoff ids`() = runTest {
        val requirement = requirement()
        val procedures = GenesisProcedureCatalog { _, _ ->
            listOf(
                GenesisProcedureCandidate("b", outputs = setOf("result"), reliability = 0.9),
                GenesisProcedureCandidate("a", outputs = setOf("result"), reliability = 0.9),
            )
        }
        val request = GenesisRequest(
            requirement = requirement,
            availableContracts = setOf("raw"),
            evidenceRefs = setOf("evidence-z", "evidence-a"),
        )
        val first = assertIs<GenesisResult.Proposed>(
            GenesisCoordinator(CapabilityRegistry(), procedures = procedures).propose(request)
        ).proposal
        val second = assertIs<GenesisResult.Proposed>(
            GenesisCoordinator(CapabilityRegistry(), procedures = procedures).propose(request)
        ).proposal

        assertEquals("a", first.selected.referenceId)
        assertEquals(first.id, second.id)
        assertEquals(first.handoff.payloadFingerprint, second.handoff.payloadFingerprint)
        assertEquals(setOf("evidence-a", "evidence-z"), first.evidenceRefs)
        assertFalse(first.handoff.activationAllowed)
    }

    private fun requirement(
        severity: GapSeverity = GapSeverity.BLOCKING,
    ) = CapabilityRequirement(
        capabilityId = CapabilityId("target.missing"),
        severity = severity,
        requiredInputs = setOf("raw"),
        requiredOutputs = setOf("result"),
    )

    private fun provider(
        capability: CapabilityId,
        providerId: String,
        type: ProviderType = ProviderType.MODULE,
        requiredInputs: Set<String> = emptySet(),
        outputs: Set<String> = emptySet(),
        reliability: Double = 1.0,
    ) = CapabilityDescriptor(
        capabilityId = capability,
        providerId = providerId,
        providerType = type,
        contract = CapabilityContract(
            requiredInputs = requiredInputs,
            outputs = outputs,
        ),
        state = ProviderState.ACTIVE,
        trustLevel = TrustLevel.SYSTEM,
        reliability = reliability,
        cost = 0.0,
    )
}
