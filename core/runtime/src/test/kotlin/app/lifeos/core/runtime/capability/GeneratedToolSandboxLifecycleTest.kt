package app.lifeos.core.runtime.capability

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeneratedToolSandboxLifecycleTest {
    private val t0 = Instant.parse("2026-09-07T19:00:00Z")

    @Test
    fun strictSandboxRejectsNetworkAndRepositoryMutationBeforeTrial() = runTest {
        val tools = GeneratedToolRegistry()
        tools.register(
            verifiedRecord(
                permissions = setOf(
                    ToolPermission.NETWORK_ACCESS,
                    ToolPermission.MODIFY_REPOSITORY,
                )
            )
        )
        val lifecycle = GeneratedToolLifecycleCoordinator(tools)

        val result = assertIs<GeneratedToolTrialAdmissionResult.Rejected>(
            lifecycle.admitToTrial("tool-1")
        )

        assertEquals(GeneratedToolState.REJECTED, result.record.state)
        assertTrue(result.reasons.single().contains("NETWORK_ACCESS"))
        assertTrue(result.reasons.single().contains("MODIFY_REPOSITORY"))
    }

    @Test
    fun safeVerifiedToolEntersTrialButDoesNotActivateAutomatically() = runTest {
        val tools = GeneratedToolRegistry()
        tools.register(verifiedRecord(permissions = setOf(ToolPermission.WRITE_TEMP_FILE)))
        val lifecycle = GeneratedToolLifecycleCoordinator(tools)

        val result = assertIs<GeneratedToolTrialAdmissionResult.TrialStarted>(
            lifecycle.admitToTrial("tool-1")
        )

        assertEquals(GeneratedToolState.TRIAL, result.record.state)
        assertEquals("strict-local-trial-v1", result.sandbox.profileId)
        assertEquals(GeneratedToolState.TRIAL, tools.get("tool-1")?.state)
    }

    @Test
    fun declaredTempWriteReceivesPerInvocationPermit() = runTest {
        val tools = GeneratedToolRegistry()
        tools.register(verifiedRecord(permissions = setOf(ToolPermission.WRITE_TEMP_FILE)))
        val lifecycle = GeneratedToolLifecycleCoordinator(tools)
        lifecycle.admitToTrial("tool-1")

        val decision = assertIs<GeneratedToolInvocationDecision.Granted>(
            lifecycle.authorizeTrialInvocation(
                toolId = "tool-1",
                invocationId = "trial-1",
                requestedPermissions = setOf(ToolPermission.WRITE_TEMP_FILE),
            )
        )

        assertEquals("tool-1", decision.permit.toolId)
        assertEquals("trial-1", decision.permit.invocationId)
        assertEquals(setOf(ToolPermission.WRITE_TEMP_FILE), decision.permit.grantedPermissions)
        assertEquals(GeneratedToolState.TRIAL, tools.get("tool-1")?.state)
    }

    @Test
    fun undeclaredNetworkRequestIsDeniedAndQuarantinesTrial() = runTest {
        val tools = GeneratedToolRegistry()
        tools.register(verifiedRecord(permissions = setOf(ToolPermission.WRITE_TEMP_FILE)))
        val lifecycle = GeneratedToolLifecycleCoordinator(tools)
        lifecycle.admitToTrial("tool-1")

        val decision = assertIs<GeneratedToolInvocationDecision.Denied>(
            lifecycle.authorizeTrialInvocation(
                toolId = "tool-1",
                invocationId = "trial-network",
                requestedPermissions = setOf(ToolPermission.NETWORK_ACCESS),
            )
        )

        assertTrue(decision.reasons.any { it.contains("permissions-not-declared:NETWORK_ACCESS") })
        assertTrue(decision.reasons.any { it.contains("permissions-outside-sandbox:NETWORK_ACCESS") })
        assertEquals(GeneratedToolState.QUARANTINED, tools.get("tool-1")?.state)
    }

    @Test
    fun threeCleanTrialsRequireExplicitPromotionAndRegisterCapability() = runTest {
        val tools = GeneratedToolRegistry()
        val capabilities = CapabilityRegistry()
        tools.register(
            verifiedRecord(
                permissions = emptySet(),
                requiredInputs = setOf("text"),
                requiredOutputs = setOf("normalized-text"),
            )
        )
        val lifecycle = GeneratedToolLifecycleCoordinator(
            tools = tools,
            capabilityRegistry = capabilities,
        )
        lifecycle.admitToTrial("tool-1")

        repeat(3) { index ->
            lifecycle.recordTrial(
                toolId = "tool-1",
                result = GeneratedToolTrialResult(
                    invocationId = "trial-$index",
                    success = true,
                    producedExpectedOutput = true,
                    latencyMs = 10,
                    recordedAt = t0.plusSeconds(index.toLong()),
                ),
            )
        }

        val evaluation = assertIs<GeneratedToolPromotionEvaluation.Eligible>(
            lifecycle.evaluatePromotion("tool-1")
        )
        assertEquals(3, evaluation.stats.trials)
        assertEquals(GeneratedToolState.TRIAL, tools.get("tool-1")?.state)

        val promoted = lifecycle.promote("tool-1")
        assertEquals(GeneratedToolState.ACTIVE, promoted.state)
        val provider = capabilities.providersFor(CapabilityId("text.normalize")).single()
        assertEquals(ProviderType.GENERATED_TOOL, provider.providerType)
        assertEquals(TrustLevel.LOW, provider.trustLevel)
        assertEquals(setOf("text"), provider.contract.requiredInputs)
        assertEquals(setOf("normalized-text"), provider.contract.outputs)
        assertEquals(1.0, provider.reliability)
    }

    @Test
    fun safetyViolationQuarantinesTrialImmediately() = runTest {
        val tools = GeneratedToolRegistry()
        tools.register(verifiedRecord())
        val lifecycle = GeneratedToolLifecycleCoordinator(tools)
        lifecycle.admitToTrial("tool-1")

        val result = assertIs<GeneratedToolTrialRecordResult.Quarantined>(
            lifecycle.recordTrial(
                toolId = "tool-1",
                result = GeneratedToolTrialResult(
                    invocationId = "unsafe-trial",
                    success = false,
                    producedExpectedOutput = false,
                    safetyViolation = true,
                    latencyMs = 5,
                    recordedAt = t0,
                ),
            )
        )

        assertEquals(GeneratedToolState.QUARANTINED, result.record.state)
        assertEquals(1, result.stats.safetyViolations)
        assertIs<GeneratedToolPromotionEvaluation.Blocked>(
            lifecycle.evaluatePromotion("tool-1")
        )
    }

    @Test
    fun insufficientVerificationConfidenceCannotEnterTrial() = runTest {
        val tools = GeneratedToolRegistry()
        tools.register(verifiedRecord(confidence = 0.70))
        val lifecycle = GeneratedToolLifecycleCoordinator(tools)

        val result = assertIs<GeneratedToolTrialAdmissionResult.Rejected>(
            lifecycle.admitToTrial("tool-1")
        )

        assertTrue(result.reasons.any { it.startsWith("verification-confidence-below-threshold") })
        assertEquals(GeneratedToolState.REJECTED, result.record.state)
    }

    private fun verifiedRecord(
        permissions: Set<ToolPermission> = emptySet(),
        confidence: Double = 0.95,
        requiredInputs: Set<String> = emptySet(),
        requiredOutputs: Set<String> = emptySet(),
    ) = GeneratedToolRecord(
        manifest = GeneratedToolManifest(
            toolId = "tool-1",
            sourceCapability = CapabilityId("text.normalize"),
            sourceHash = "source-hash",
            buildHash = "build-hash",
            permissions = permissions,
            generatedAt = t0,
            requiredInputs = requiredInputs,
            requiredOutputs = requiredOutputs,
        ),
        state = GeneratedToolState.VERIFIED,
        verificationConfidence = confidence,
    )
}
