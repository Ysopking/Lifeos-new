package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.ToolPermission
import app.lifeos.core.runtime.genesis.GenesisHandoff
import app.lifeos.core.runtime.genesis.GenesisHandoffTarget
import app.lifeos.core.runtime.web.OpenApiAuthenticationMode
import app.lifeos.core.runtime.web.OpenApiHttpMethod
import app.lifeos.core.runtime.web.OpenApiOperationDescriptor
import app.lifeos.core.runtime.web.OpenApiToolCandidate
import app.lifeos.core.runtime.web.WebApiCapabilityCandidate
import app.lifeos.core.runtime.web.WebApiCapabilityDiscoveryEngine
import app.lifeos.core.runtime.web.WebApiDiscoveryEvidence
import app.lifeos.core.runtime.web.WebApiDiscoverySignal
import app.lifeos.core.runtime.web.WebAssistedToolWorkshopBrief
import app.lifeos.core.runtime.web.WebAssistedToolWorkshopBriefBuilder
import app.lifeos.core.runtime.web.WebResourceIdentity
import app.lifeos.core.runtime.web.NormalizedOpenApiDocument
import app.lifeos.core.runtime.web.OpenApiToolCandidateTranslator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebAssistedBuildStudioPlannerTest {
    @Test
    fun plan_binds_web_brief_to_existing_buildstudio_contract_without_authority() {
        val gap = gap()
        val brief = brief(gap.requirement)
        val request = request(gap, brief)

        val plan = WebAssistedBuildStudioPlanner().plan(request)

        assertEquals(request.fingerprint, plan.requestFingerprint)
        assertEquals(SOURCE_COMMIT, plan.buildSpec.sourceCommit)
        assertEquals(brief.requirement, plan.buildSpec.gap.requirement)
        assertEquals(plan.buildSpec.id, plan.design.buildSpecId)
        assertTrue(
            plan.design.implementationNotes.any {
                it == "web-assisted-tool-workshop-brief:" + brief.fingerprint
            }
        )
        assertTrue("activation-allowed:false" in plan.design.implementationNotes)
        assertFalse(request.repositoryAuthority)
        assertFalse(request.patchAuthority)
        assertFalse(request.buildAuthority)
        assertFalse(request.permissionAuthority)
        assertFalse(request.activationAuthority)
        assertFalse(request.executionAuthority)
        assertFalse(plan.patchAuthority)
        assertFalse(plan.buildAuthority)
        assertFalse(plan.activationAuthority)
        assertFalse(plan.executionAuthority)
    }

    @Test
    fun protected_paths_fail_closed_before_buildstudio_host() {
        val gap = gap()
        val brief = brief(gap.requirement)
        val request = WebAssistedBuildStudioRequest.create(
            sourceCommit = SOURCE_COMMIT,
            gap = gap,
            genesisHandoff = buildStudioHandoff(),
            toolWorkshopBrief = brief,
            allowedPathPrefixes = setOf("core/runtime/src/main/kotlin"),
            requiredTestPaths = setOf("core/runtime/src/test/kotlin/app/lifeos/core/runtime/capability/Test.kt"),
            proposedSourcePaths = setOf(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/ToolWorkshopCoordinator.kt"
            ),
            proposedTestPaths = setOf(
                "core/runtime/src/test/kotlin/app/lifeos/core/runtime/capability/Test.kt"
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            WebAssistedBuildStudioPlanner().plan(request)
        }
    }

    @Test
    fun deterministic_request_produces_deterministic_plan() {
        val gap = gap()
        val brief = brief(gap.requirement)
        val first = request(gap, brief)
        val second = request(gap, brief)
        val planner = WebAssistedBuildStudioPlanner()

        assertEquals(first, second)
        assertEquals(planner.plan(first), planner.plan(second))
    }

    private fun request(
        gap: CapabilityGap,
        brief: WebAssistedToolWorkshopBrief,
    ): WebAssistedBuildStudioRequest =
        WebAssistedBuildStudioRequest.create(
            sourceCommit = SOURCE_COMMIT,
            gap = gap,
            genesisHandoff = buildStudioHandoff(),
            toolWorkshopBrief = brief,
            allowedPathPrefixes = setOf("core/runtime-web/src"),
            requiredTestPaths = setOf(
                "core/runtime-web/src/test/kotlin/app/lifeos/core/runtime/web/GeneratedApiAdapterTest.kt"
            ),
            proposedSourcePaths = setOf(
                "core/runtime-web/src/main/kotlin/app/lifeos/core/runtime/web/GeneratedApiAdapter.kt"
            ),
            proposedTestPaths = setOf(
                "core/runtime-web/src/test/kotlin/app/lifeos/core/runtime/web/GeneratedApiAdapterTest.kt"
            ),
        )

    private fun buildStudioHandoff(): GenesisHandoff =
        GenesisHandoff(
            proposalId = "proposal-b415",
            target = GenesisHandoffTarget.BUILD_STUDIO,
            referenceId = "module-proposal-b415",
            payloadFingerprint = "1".repeat(64),
            requiresExplicitApproval = true,
        )

    private fun gap(): CapabilityGap =
        CapabilityGap(
            requirement = CapabilityRequirement(
                capabilityId = CapabilityId("calendar.sync"),
                severity = GapSeverity.BLOCKING,
                requiredInputs = setOf("calendar event"),
                requiredOutputs = setOf("sync receipt"),
            ),
            type = CapabilityGapType.CAPABILITY_MISSING,
        )

    private fun brief(requirement: CapabilityRequirement): WebAssistedToolWorkshopBrief {
        val evidence = WebApiDiscoveryEvidence.create(
            source = WebResourceIdentity.parse("https://api.example.com/openapi.json"),
            acquisitionReceiptFingerprint = "a".repeat(64),
            payloadSha256 = "b".repeat(64),
            contentType = "application/json",
            publicText = """{"openapi":"3.1.0","calendar":true}""",
        )
        val discovery = WebApiCapabilityDiscoveryEngine()
            .discover(requirement, listOf(evidence))
            .candidates
            .single()
        val operation = OpenApiOperationDescriptor.create(
            method = OpenApiHttpMethod.POST,
            path = "/v1/calendar/events",
            operationId = "createCalendarEvent",
            requestContracts = listOf("calendar event"),
            responseContracts = listOf("sync receipt"),
            authenticationMode = OpenApiAuthenticationMode.REQUIRED_UNRESOLVED,
        )
        val document = NormalizedOpenApiDocument.create(
            resource = discovery.resource,
            discoveryCandidateFingerprint = discovery.fingerprint,
            specPayloadSha256 = "9".repeat(64),
            openApiVersion = "3.1.0",
            operations = listOf(operation),
        )
        val candidate: OpenApiToolCandidate = requireNotNull(
            OpenApiToolCandidateTranslator().translate(requirement, discovery, document)
        )
        assertEquals(setOf(ToolPermission.NETWORK_ACCESS), candidate.requestedPermissions)
        return WebAssistedToolWorkshopBriefBuilder().build(
            requirement,
            candidate,
            emptyList(),
        )
    }

    private companion object {
        const val SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
