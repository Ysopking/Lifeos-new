package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.life.AppSensorRegistry
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import app.lifeos.core.runtime.life.ObservationPrivacyClass
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.ProjectionClassificationContext
import app.lifeos.core.runtime.life.ProjectionClassificationEngine
import app.lifeos.core.runtime.life.RepresentationLevel
import app.lifeos.core.runtime.life.SensorAttentionMode
import app.lifeos.core.runtime.life.SensorClass
import app.lifeos.core.runtime.life.SensorDescriptor
import app.lifeos.core.runtime.life.SensorHealthState
import app.lifeos.core.runtime.life.SensorId
import app.lifeos.core.runtime.policy.OwnerObservationType
import app.lifeos.core.runtime.world.SensorAttentionDemand
import app.lifeos.core.runtime.world.SensorAttentionRuntime
import app.lifeos.core.runtime.world.StateDimensionId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * B465 product-level contract test across the information/app-interaction track.
 *
 * No external effect is executed here. The purpose is to prove that observation classification,
 * sensor attention, app discovery, canonical capability registration, semantic UI projection and
 * cross-app planning compose without collapsing their authority boundaries.
 */
class InformationAppInteractionGoldTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun projectedSemanticUiToValidatedCrossAppPlanPreservesAllAuthorityBoundaries() = runTest {
        val semanticBoundary = SemanticAccessibilityBoundary()
        val uiSnapshot = SemanticUiSnapshot.create(
            packageName = "messenger.example",
            windowRevision = "17",
            capturedAt = now,
            nodes = listOf(
                SemanticUiNodeSnapshot(
                    nodeKey = "conversation-body",
                    role = SemanticUiRole.TEXT,
                    labelFingerprint = "1".repeat(64),
                    valueFingerprint = "2".repeat(64),
                    enabled = true,
                    visible = true,
                    supportedActions = emptySet(),
                )
            ),
        )
        val observation = semanticBoundary.observe(
            snapshot = uiSnapshot,
            observationGrantId = "owner-observation-grant:" + "3".repeat(64),
        )
        val classification = ProjectionClassificationEngine().classify(
            observation = observation,
            context = ProjectionClassificationContext(
                directStateRead = true,
                sourceStateClosedForContract = true,
            ),
        )

        assertEquals(ObservationSurfaceKind.APP_UI, observation.surface)
        assertEquals(ObservationAuthorityClass.UI_OBSERVATION, observation.authority)
        assertEquals(ObservationPrivacyClass.SENSITIVE, observation.privacy)
        assertEquals(RepresentationLevel.PROJECTED, classification.descriptor.representation)

        val registry = CapabilityRegistry()
        val appRegistry = UniversalAppCapabilityRegistryAdapter(registry)
        val classifier = AppCapabilityDiscoveryClassifier(
            listOf(
                discoveryRule(
                    id = "message-read",
                    capability = "communication.message.read",
                    semanticContract = "communication:message:read",
                    input = "conversation-id",
                    output = "message-text",
                ),
                discoveryRule(
                    id = "document-prepare",
                    capability = "document.share.prepare",
                    semanticContract = "document:share:prepare",
                    input = "message-text",
                    output = "share-prepared",
                ),
            )
        )

        val candidates = classifier.classify(
            listOf(
                surface(
                    provider = "android:messenger.example",
                    contract = "communication:message:read",
                    source = "4".repeat(64),
                ),
                surface(
                    provider = "android:documents.example",
                    contract = "document:share:prepare",
                    source = "5".repeat(64),
                ),
            )
        )

        assertEquals(2, candidates.size)
        candidates.forEachIndexed { index, candidate ->
            assertFalse(candidate.activationAuthority)
            assertFalse(candidate.executionAuthority)
            assertFalse(candidate.ownerPolicyAuthority)
            appRegistry.promoteValidated(
                candidate = candidate,
                evidence = AppCapabilityValidationEvidence(
                    candidateFingerprint = candidate.fingerprint,
                    shadowTestFingerprint =
                        if (index == 0) "6".repeat(64) else "7".repeat(64),
                    contractTestFingerprint =
                        if (index == 0) "8".repeat(64) else "9".repeat(64),
                    validatedProviderVersion = candidate.providerVersion,
                ),
            )
        }

        val descriptors = registry.all(includeUnavailable = true)
        val bus = AndroidCapabilityBus(
            providerCatalog = registry,
            bindings = descriptors.map { descriptor ->
                AndroidCapabilityBinding(
                    descriptor = descriptor,
                    providerVersion = "1",
                    requiredOwnerEffect = null,
                    ownerScope = "gold",
                    permissions = emptyList(),
                    riskClass = AndroidCapabilityRiskClass.LOW,
                    reversibility = AndroidCapabilityReversibility.REVERSIBLE,
                    recoverySemantics = AndroidRecoverySemantics.RETRY_SAFE,
                    expectedOutcomeContract = "gold-observation",
                )
            },
        )
        val resolution = CrossAppWorkflowRouter(bus).resolve(
            workflow = CrossAppWorkflowDefinition(
                workflowId = "gold-message-to-document",
                initialInputs = setOf("conversation-id"),
                steps = listOf(
                    CrossAppWorkflowStep(
                        stepId = "read-message",
                        capabilityId = CapabilityId("communication.message.read"),
                        requiredOutputs = setOf("message-text"),
                        resource = "android-app://messenger.example/conversation",
                        scope = "gold",
                    ),
                    CrossAppWorkflowStep(
                        stepId = "prepare-document-share",
                        capabilityId = CapabilityId("document.share.prepare"),
                        requiredOutputs = setOf("share-prepared"),
                        resource = "android-app://documents.example/share",
                        scope = "gold",
                    ),
                ),
            ),
            permissions = AndroidPermissionSnapshot(emptySet()),
        )

        val ready = assertIs<CrossAppWorkflowResolution.Ready>(resolution)
        assertEquals(
            listOf("read-message", "prepare-document-share"),
            ready.plan.steps.map { it.first },
        )
        assertTrue("message-text" in ready.plan.resultingInputs)
        assertTrue("share-prepared" in ready.plan.resultingInputs)
        assertFalse(ready.plan.executionAuthority)
        assertFalse(ready.plan.ownerPolicyAuthority)
    }

    @Test
    fun blockingInformationNeedFocusesHealthySensorButCannotCreatePermission() = runTest {
        val sensorId = SensorId("calendar-provider")
        val registry = AppSensorRegistry()
        registry.register(
            SensorDescriptor(
                sensorId = sensorId,
                sensorClass = SensorClass.CALENDAR,
                adapterVersion = "1",
                observationType = OwnerObservationType.CALENDAR,
                resourcePrefix = "calendar:",
                supportedSurfaces = setOf(ObservationSurfaceKind.CONTENT_PROVIDER),
                defaultMode = SensorAttentionMode.EVENT_DRIVEN,
            )
        )

        val decision = SensorAttentionRuntime(registry).apply(
            listOf(
                SensorAttentionDemand(
                    sensorId = sensorId,
                    informationGainMicros = 900_000,
                    goalRelevanceMicros = 800_000,
                    verificationValueMicros = 300_000,
                    energyCostMicros = 100_000,
                    privacyCostMicros = 100_000,
                    latencyCostMicros = 50_000,
                    resourceCostMicros = 50_000,
                    blockingGapCount = 1,
                    stateDimensions = setOf(
                        StateDimensionId("calendar.current-events")
                    ),
                )
            )
        ).single()

        assertEquals(SensorAttentionMode.FOCUSED, decision.mode)
        assertFalse(decision.observationGrantAuthority)
        assertFalse(decision.effectAuthority)
        assertEquals(SensorHealthState.HEALTHY, registry.state(sensorId)?.health)
    }

    private fun discoveryRule(
        id: String,
        capability: String,
        semanticContract: String,
        input: String,
        output: String,
    ) = AppCapabilityDiscoveryRule(
        ruleId = "$id/v1",
        capabilityId = CapabilityId(capability),
        acceptedInterfaces = setOf(AppCapabilityInterfaceKind.CONTENT_PROVIDER),
        requiredSemanticContracts = setOf(semanticContract),
        capabilityContract = CapabilityContract(
            requiredInputs = setOf(input),
            outputs = setOf(output),
        ),
        reliability = 0.9,
        cost = 0.1,
    )

    private fun surface(
        provider: String,
        contract: String,
        source: String,
    ) = AppSurfaceEvidence(
        providerId = provider,
        providerVersion = "1",
        interfaceKind = AppCapabilityInterfaceKind.CONTENT_PROVIDER,
        surfaceKey = "provider",
        semanticContracts = setOf(contract),
        sourceFingerprint = source,
    )
}
