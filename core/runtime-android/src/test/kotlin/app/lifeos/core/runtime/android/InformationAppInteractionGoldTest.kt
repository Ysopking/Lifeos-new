package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.agency.ExternalActionReobservationBridge
import app.lifeos.core.runtime.agency.ExternalActionReobservationDecision
import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.life.AppObservationBatch
import app.lifeos.core.runtime.life.AppObservationIngress
import app.lifeos.core.runtime.life.AppSensorBudget
import app.lifeos.core.runtime.life.AppSensorCursor
import app.lifeos.core.runtime.life.AppSensorRegistry
import app.lifeos.core.runtime.life.AppUsageEvent
import app.lifeos.core.runtime.life.AppUsageEventType
import app.lifeos.core.runtime.life.AppUsageObservationFactory
import app.lifeos.core.runtime.life.ControlStatus
import app.lifeos.core.runtime.life.EpistemicStatus
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import app.lifeos.core.runtime.life.ObservationPrivacyClass
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.ProjectionClassificationContext
import app.lifeos.core.runtime.life.ProjectionClassificationEngine
import app.lifeos.core.runtime.life.RealizationDescriptor
import app.lifeos.core.runtime.life.RepresentationLevel
import app.lifeos.core.runtime.life.SensorAttentionMode
import app.lifeos.core.runtime.life.SensorClass
import app.lifeos.core.runtime.life.SensorDescriptor
import app.lifeos.core.runtime.life.SensorHealthState
import app.lifeos.core.runtime.life.SensorId
import app.lifeos.core.runtime.life.TemporalStatus
import app.lifeos.core.runtime.policy.OwnerObservationType
import app.lifeos.core.runtime.world.PersonalContextSnapshot
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
 * B465 integration contract across B451-B464D.
 *
 * No external effect is executed. The test proves that the perception, projection, context,
 * capability and verification boundaries compose while their authorities remain separated.
 */
class InformationAppInteractionGoldTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun usageObservationContextAndAttentionStayNonIntentionalAndNonAuthoritative() = runTest {
        val sensorId = SensorId("android-usage-provider")
        val descriptor = SensorDescriptor(
            sensorId = sensorId,
            sensorClass = SensorClass.APP_USAGE,
            adapterVersion = "1",
            observationType = OwnerObservationType.APP_USAGE,
            resourcePrefix = "android-usage:",
            supportedSurfaces = setOf(ObservationSurfaceKind.APP_USAGE),
            defaultMode = SensorAttentionMode.EVENT_DRIVEN,
        )
        val observation = AppUsageObservationFactory(sensorId).create(
            AppUsageEvent(
                packageName = "calendar.example",
                foregroundSince = now.minusSeconds(120),
                backgroundAt = now.minusSeconds(60),
                observedAt = now,
                eventType = AppUsageEventType.FOREGROUND_INTERVAL,
                sourceRevision = "usage-rev-1",
            )
        )
        val cursor = AppSensorCursor(sensorId, revision = 1L)
        val batch = AppObservationBatch.create(
            sensorId = sensorId,
            observations = listOf(observation),
            nextCursor = AppSensorCursor(sensorId, revision = 2L),
            exhausted = true,
        )

        val validated = AppObservationIngress.validate(
            descriptor = descriptor,
            cursor = cursor,
            budget = AppSensorBudget(),
            batch = batch,
        )
        assertEquals(listOf(observation), validated.observations)
        assertEquals(null, observation.observationGrantId)

        val classified = ProjectionClassificationEngine().classify(
            observation,
            ProjectionClassificationContext(
                directStateRead = true,
                sourceStateClosedForContract = true,
            ),
        )
        assertEquals(RepresentationLevel.PROJECTED, classified.descriptor.representation)
        assertEquals(EpistemicStatus.OBSERVED, classified.descriptor.epistemicStatus)

        val context = PersonalContextSnapshot.create(
            appObservationHeadFingerprint = validated.fingerprint,
            sensorProjectionFingerprint = observation.provenanceFingerprint,
            lifeGraphFingerprint = "life-graph-head",
            evidenceHeadFingerprint = "evidence-head",
            ownerObservationPolicyRevision = 7L,
            createdAt = now,
        )
        assertFalse(context.directWorldStateMutationAllowed)

        val registry = AppSensorRegistry()
        registry.register(descriptor)
        val decision = SensorAttentionRuntime(registry).apply(
            listOf(
                SensorAttentionDemand(
                    sensorId = sensorId,
                    informationGainMicros = 800_000,
                    goalRelevanceMicros = 700_000,
                    verificationValueMicros = 100_000,
                    energyCostMicros = 50_000,
                    privacyCostMicros = 100_000,
                    latencyCostMicros = 50_000,
                    resourceCostMicros = 50_000,
                    blockingGapCount = 1,
                    stateDimensions = setOf(
                        StateDimensionId("calendar.current-context")
                    ),
                )
            )
        ).single()

        assertEquals(SensorAttentionMode.FOCUSED, decision.mode)
        assertFalse(decision.observationGrantAuthority)
        assertFalse(decision.effectAuthority)
        assertEquals(SensorHealthState.HEALTHY, registry.state(sensorId)?.health)
    }

    @Test
    fun semanticUiCannotVerifyActionButActualAuthorizedProviderObservationCan() {
        val boundary = SemanticAccessibilityBoundary()
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
        val projected = boundary.observe(
            snapshot = uiSnapshot,
            observationGrantId = "owner-observation-grant:" + "3".repeat(64),
        )

        val weak = ExternalActionReobservationBridge.fromObservation(
            observation = projected,
            fieldFingerprints = mapOf("status" to "4".repeat(64)),
        )
        assertIs<ExternalActionReobservationDecision.Insufficient>(weak)

        val actual = InformationObservation(
            sourceId = "authoritative-provider",
            sourceResource = "bank://account/current",
            surface = ObservationSurfaceKind.API,
            observedAt = now,
            sourceTimestamp = now,
            sourceRevision = "bank-revision-42",
            mimeType = "application/vnd.lifeos.account-state+text",
            payload = "status=settled",
            realization = RealizationDescriptor(
                representation = RepresentationLevel.ACTUAL,
                epistemicStatus = EpistemicStatus.RECONCILED,
                temporalStatus = TemporalStatus.CURRENT,
                controlStatus = ControlStatus.PASSIVE,
            ),
            authority = ObservationAuthorityClass.AUTHORITATIVE_PROVIDER,
            privacy = ObservationPrivacyClass.SENSITIVE,
            observationGrantId = "owner-observation-grant:" + "5".repeat(64),
            confidence = 1.0,
        )

        val eligible = ExternalActionReobservationBridge.fromObservation(
            observation = actual,
            fieldFingerprints = mapOf("status" to "6".repeat(64)),
        )
        assertIs<ExternalActionReobservationDecision.Eligible>(eligible)
        assertEquals(actual.sourceResource, eligible.node.resourceIdentity)
    }

    @Test
    fun discoveredValidatedProvidersComposeIntoNonExecutingCrossAppPlan() = runTest {
        val rules = listOf(
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
        val candidates = AppCapabilityDiscoveryClassifier(rules).classify(
            listOf(
                surface(
                    provider = "android:messenger.example",
                    contract = "communication:message:read",
                    source = "7".repeat(64),
                ),
                surface(
                    provider = "android:documents.example",
                    contract = "document:share:prepare",
                    source = "8".repeat(64),
                ),
            )
        )
        assertEquals(2, candidates.size)

        val registry = CapabilityRegistry()
        val appRegistry = UniversalAppCapabilityRegistryAdapter(registry)
        candidates.forEachIndexed { index, candidate ->
            assertFalse(candidate.activationAuthority)
            assertFalse(candidate.executionAuthority)
            assertFalse(candidate.ownerPolicyAuthority)
            appRegistry.promoteValidated(
                candidate,
                AppCapabilityValidationEvidence(
                    candidateFingerprint = candidate.fingerprint,
                    shadowTestFingerprint =
                        if (index == 0) "9".repeat(64) else "a".repeat(64),
                    contractTestFingerprint =
                        if (index == 0) "b".repeat(64) else "c".repeat(64),
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
        val result = CrossAppWorkflowRouter(bus).resolve(
            CrossAppWorkflowDefinition(
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

        val ready = assertIs<CrossAppWorkflowResolution.Ready>(result)
        assertEquals(
            listOf("read-message", "prepare-document-share"),
            ready.plan.steps.map { it.first },
        )
        assertTrue("message-text" in ready.plan.resultingInputs)
        assertTrue("share-prepared" in ready.plan.resultingInputs)
        assertFalse(ready.plan.executionAuthority)
        assertFalse(ready.plan.ownerPolicyAuthority)
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
