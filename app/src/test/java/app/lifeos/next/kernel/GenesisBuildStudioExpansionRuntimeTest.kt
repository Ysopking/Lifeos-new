package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.buildstudio.BuildSpec
import app.lifeos.core.runtime.buildstudio.BuildStudioExpansionRequest
import app.lifeos.core.runtime.buildstudio.BuildStudioHostAdapter
import app.lifeos.core.runtime.buildstudio.BuildStudioHostProcessRegistry
import app.lifeos.core.runtime.buildstudio.BuildStudioHostState
import app.lifeos.core.runtime.buildstudio.BuildStudioHostStatus
import app.lifeos.core.runtime.buildstudio.BuildStudioResult
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.GoalCapabilityPlan
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GenesisBuildStudioExpansionRuntimeTest {
    @Test
    fun protectedGapFailsClosedWhenExternalHostIsMissing() = runTest {
        BuildStudioHostProcessRegistry.uninstall()
        CapabilityRegistry()

        val reason = GenesisCapabilityExpansionRuntime.process(contextForProtectedGap())

        assertTrue(reason.contains("genesis:crypto.signing.root:BUILD_STUDIO"), reason)
        assertTrue(reason.contains("buildstudio-failed:host:buildstudio-host-not-installed"), reason)
    }

    @Test
    fun protectedGapUsesInstalledExternalHostWithoutGrantingActivation() = runTest {
        BuildStudioHostProcessRegistry.uninstall()
        val capabilities = CapabilityRegistry()
        var expandedRequest: BuildStudioExpansionRequest? = null
        val host = object : BuildStudioHostAdapter {
            override val id: String = "gold-contract-host"

            override suspend fun status(): BuildStudioHostStatus =
                BuildStudioHostStatus(BuildStudioHostState.READY, "test-ready")

            override suspend fun run(spec: BuildSpec): BuildStudioResult =
                BuildStudioResult.Failed("test", "direct-run-not-expected")

            override suspend fun expand(request: BuildStudioExpansionRequest): BuildStudioResult {
                expandedRequest = request
                return BuildStudioResult.Failed("host", "bridge-contract-observed")
            }
        }

        try {
            BuildStudioHostProcessRegistry.install(host, capabilities)
            val context = contextForProtectedGap()
            val reason = GenesisCapabilityExpansionRuntime.process(context)
            val request = assertNotNull(expandedRequest)

            assertEquals(context.sourcePhoton.id, request.sourcePhotonId)
            assertEquals(context.goalPhotonId, request.goalPhotonId)
            assertEquals(CapabilityId("crypto.signing.root"), request.gap.requirement.capabilityId)
            assertTrue(!request.activationAllowed)
            assertTrue(reason.contains("genesis:crypto.signing.root:BUILD_STUDIO"), reason)
            assertTrue(reason.contains("buildstudio-failed:host:bridge-contract-observed"), reason)
        } finally {
            BuildStudioHostProcessRegistry.uninstall()
        }
    }

    private fun contextForProtectedGap(): GoalActionContext {
        val goal = GoalFrame(
            intent = IntentType.BUILD_OR_IMPLEMENT,
            objective = "Implement protected signing capability",
            entities = emptyList(),
            references = emptyList(),
            constraints = emptyList(),
            ambiguities = emptyList(),
            confidence = 1.0,
            language = LanguageCode.EN,
        )
        val requirement = CapabilityRequirement(
            capabilityId = CapabilityId("crypto.signing.root"),
            severity = GapSeverity.BLOCKING,
            requiredInputs = setOf("goal-photon"),
            requiredOutputs = setOf("protected-signing-proof"),
        )
        val gap = CapabilityGap(
            requirement = requirement,
            type = CapabilityGapType.CAPABILITY_MISSING,
        )
        val routing = GoalCapabilityResolution(
            plan = GoalCapabilityPlan(
                goal = goal,
                requirements = listOf(requirement),
                languageBlocking = false,
            ),
            selectedProviders = emptyMap(),
            gaps = listOf(gap),
        )
        return GoalActionContext(
            goal = goal,
            routing = routing,
            sourcePhoton = Photon(
                id = PhotonId("source-buildstudio-contract"),
                content = "Implement protected signing capability",
                provenance = Provenance("test", "owner"),
                tags = setOf("chat"),
            ),
            goalPhotonId = PhotonId("goal-buildstudio-contract"),
        )
    }
}
