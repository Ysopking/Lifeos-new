package app.lifeos.next.kernel

import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.agency.EffectReceipt
import app.lifeos.core.runtime.agency.ExternalActionContract
import app.lifeos.core.runtime.agency.ExternalEffectExecutor
import app.lifeos.core.runtime.agency.ExternalEffectState
import app.lifeos.core.runtime.agency.ExternalEndpoint
import app.lifeos.core.runtime.agency.ExternalObservation
import app.lifeos.core.runtime.agency.ExternalChallengeResolution
import app.lifeos.core.runtime.agency.PayloadHandle
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.LanguageGoalCapabilityRouter
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalSemanticEffectSafetyTest {
    private val language = LanguageUnderstandingEngine()
    private val capabilities = LanguageGoalCapabilityRouter(
        CapabilityRegistry(LanguageGoalCapabilityRouter.LOCAL_SYSTEM_PROVIDERS)
    )
    private val source = Photon(
        id = PhotonId("semantic-effect-source"),
        content = "source",
        provenance = Provenance(
            source = "ExternalSemanticEffectSafetyTest",
            actor = "test",
            createdAt = Instant.parse("2026-09-18T12:00:00Z"),
        ),
    )

    @Test
    fun questionNegationQuotationAndConditionNeverReachTransport() = runTest {
        val fake = CountingExternalEffectExecutor()
        val dispatcher = dispatcher(fake)
        val cases = listOf(
            "Was ist eine E-Mail?",
            "Sende diese Mail nicht.",
            "Er sagte: „Sende die Mail.“",
            "Wenn X passiert, sende die Mail.",
        )

        cases.forEachIndexed { index, text ->
            val goal = language.understand(text).goal
            val result = dispatcher.execute(
                GoalActionContext(
                    goal = goal,
                    routing = capabilities.route(goal),
                    sourcePhoton = source.copy(content = text),
                    goalPhotonId = PhotonId("goal-negative-$index"),
                    externalActionContract = contract("negative-$index"),
                )
            )

            assertNull(result.externalEffect, text)
        }
        assertEquals(0, fake.executeCalls)
        assertEquals(0, fake.resumeCalls)
        assertEquals(0, fake.reconcileCalls)
    }

    private fun dispatcher(external: ExternalEffectExecutor) = GoalActionDispatcher(
        executeKnowledge = { LocalKnowledgeExecutionResult.Failed("unused") },
        executeDeepSearch = { LocalDeepSearchExecutionResult.Failed("unused") },
        executeImageGeneration = { ImageGenerationResult.Failed("unused") },
        executeImageTransform = { LocalImageTransformExecutionResult.Failed("unused") },
        executeSchedule = { LocalScheduleExecutionResult.Failed("unused") },
        prepareCommunication = { LocalCommunicationExecutionResult.Failed("unused") },
        executionGuard = PassThroughGoalActionExecutionGuard,
        externalEffectExecutor = external,
        durableRuntimeProvider = { null },
        expandCapabilities = { "unused" },
    )

    private fun contract(suffix: String): ExternalActionContract {
        val payload = "payload-$suffix".toByteArray()
        val handle = PayloadHandle.fromPayload(payload)
        val endpoint = ExternalEndpoint("test://semantic-effect/$suffix")
        return ExternalActionContract(
            actionId = "action-$suffix",
            idempotencyKey = "idem-$suffix",
            endpoint = endpoint,
            operation = "send",
            payloadHandle = handle,
            payloadFingerprint = handle.fingerprint,
            requiredOwnerPolicy = OwnerEffectRequest(
                actorId = OwnerActorId("semantic-effect-test"),
                effect = OwnerEffectType.NETWORK_ACCESS,
                resource = endpoint.uri,
                scope = "goal:test",
            ),
            resourceReservation = null,
            sourcePhotonRefs = setOf(PhotonRevisionRef(source.id, source.revision)),
            expectedObservation = ExternalObservation(
                kind = "receipt",
                expectedFingerprint = "0".repeat(64),
            ),
        )
    }

    private class CountingExternalEffectExecutor : ExternalEffectExecutor {
        var executeCalls = 0
        var resumeCalls = 0
        var reconcileCalls = 0

        override suspend fun execute(contract: ExternalActionContract): EffectReceipt {
            executeCalls += 1
            return receipt(contract)
        }

        override suspend fun resumeAfterChallenge(
            contract: ExternalActionContract,
            resolution: ExternalChallengeResolution,
        ): EffectReceipt {
            resumeCalls += 1
            return receipt(contract)
        }

        override suspend fun reconcile(contract: ExternalActionContract): EffectReceipt {
            reconcileCalls += 1
            return receipt(contract)
        }

        private fun receipt(contract: ExternalActionContract) = EffectReceipt(
            actionId = contract.actionId,
            idempotencyKey = contract.idempotencyKey,
            state = ExternalEffectState.CONFIRMED,
            recordedAt = Instant.parse("2026-09-18T12:00:00Z"),
            externalReference = "test",
            observationFingerprint = "1".repeat(64),
            detail = "test",
        )
    }
}
