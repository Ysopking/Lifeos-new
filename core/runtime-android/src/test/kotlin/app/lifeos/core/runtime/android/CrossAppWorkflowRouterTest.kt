package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CrossAppWorkflowRouterTest {
    @Test
    fun routesOutputsFromOneAppProviderIntoNextSemanticCapability() = runTest {
        val open = descriptor(
            capability = "communication.message.read",
            provider = "android:messenger",
            requiredInputs = setOf("conversation-id"),
            outputs = setOf("message-text"),
        )
        val share = descriptor(
            capability = "document.share.prepare",
            provider = "android:documents",
            requiredInputs = setOf("message-text"),
            outputs = setOf("share-prepared"),
        )
        val registry = CapabilityRegistry(listOf(open, share))
        val router = CrossAppWorkflowRouter(
            AndroidCapabilityBus(
                providerCatalog = registry,
                bindings = listOf(binding(open), binding(share)),
            )
        )

        val result = router.resolve(
            workflow = CrossAppWorkflowDefinition(
                workflowId = "message-to-document",
                initialInputs = setOf("conversation-id"),
                steps = listOf(
                    step(
                        "read",
                        open.capabilityId,
                        setOf("message-text"),
                        "android-app://messenger/conversation",
                    ),
                    step(
                        "prepare-share",
                        share.capabilityId,
                        setOf("share-prepared"),
                        "android-app://documents/share",
                    ),
                ),
            ),
            permissions = AndroidPermissionSnapshot(emptySet()),
        )

        val ready = assertIs<CrossAppWorkflowResolution.Ready>(result)
        assertEquals(listOf("read", "prepare-share"), ready.plan.steps.map { it.first })
        assertTrue("message-text" in ready.plan.resultingInputs)
        assertTrue("share-prepared" in ready.plan.resultingInputs)
        assertFalse(ready.plan.executionAuthority)
        assertFalse(ready.plan.ownerPolicyAuthority)
    }

    @Test
    fun missingIntermediateContractBlocksBeforeAnyExecutionAuthorityExists() = runTest {
        val share = descriptor(
            capability = "document.share.prepare",
            provider = "android:documents",
            requiredInputs = setOf("message-text"),
            outputs = setOf("share-prepared"),
        )
        val registry = CapabilityRegistry(listOf(share))
        val router = CrossAppWorkflowRouter(
            AndroidCapabilityBus(
                providerCatalog = registry,
                bindings = listOf(binding(share)),
            )
        )

        val result = router.resolve(
            CrossAppWorkflowDefinition(
                workflowId = "broken-chain",
                initialInputs = setOf("conversation-id"),
                steps = listOf(
                    step(
                        "prepare-share",
                        share.capabilityId,
                        setOf("share-prepared"),
                        "android-app://documents/share",
                    )
                ),
            ),
            AndroidPermissionSnapshot(emptySet()),
        )

        val blocked = assertIs<CrossAppWorkflowResolution.Blocked>(result)
        assertEquals("prepare-share", blocked.stepId)
        assertIs<AndroidCapabilityResolution.ContractMismatch>(blocked.reason)
        assertTrue(blocked.resolvedStepIds.isEmpty())
    }

    private fun descriptor(
        capability: String,
        provider: String,
        requiredInputs: Set<String>,
        outputs: Set<String>,
    ) = CapabilityDescriptor(
        capabilityId = CapabilityId(capability),
        providerId = provider,
        providerType = ProviderType.CONNECTOR,
        contract = CapabilityContract(requiredInputs, outputs),
        trustLevel = TrustLevel.MEDIUM,
    )

    private fun binding(
        descriptor: CapabilityDescriptor,
    ) = AndroidCapabilityBinding(
        descriptor = descriptor,
        providerVersion = "1",
        requiredOwnerEffect = null,
        ownerScope = "test-scope",
        permissions = emptyList(),
        riskClass = AndroidCapabilityRiskClass.LOW,
        reversibility = AndroidCapabilityReversibility.REVERSIBLE,
        recoverySemantics = AndroidRecoverySemantics.RETRY_SAFE,
        expectedOutcomeContract = "observation:test",
    )

    private fun step(
        id: String,
        capability: CapabilityId,
        outputs: Set<String>,
        resource: String,
    ) = CrossAppWorkflowStep(
        stepId = id,
        capabilityId = capability,
        requiredOutputs = outputs,
        resource = resource,
        scope = "test-scope",
    )
}
