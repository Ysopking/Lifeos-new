package app.lifeos.core.runtime.agency

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyEvent
import app.lifeos.core.runtime.policy.OwnerPolicyGrant
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerPolicyRepositoryLoadReport
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExternalEffectExecutorTest {
    private val now = Instant.parse("2026-09-18T12:00:00Z")
    private val payload = "payload".toByteArray()
    private val handle = PayloadHandle.fromPayload(payload)

    @Test
    fun unknownOutcomeReconcilesWithoutSecondTransport() = runTest {
        val harness = harness(
            firstTransport = ExternalTransportResult.Unknown("remote-1"),
            reconcile = ExternalTransportResult.Confirmed(
                externalReference = "remote-1",
                observationFingerprint = "a".repeat(64),
            ),
        )

        val first = harness.executor.execute(harness.contract)
        val second = harness.executor.execute(harness.contract)

        assertEquals(ExternalEffectState.UNKNOWN_OUTCOME, first.state)
        assertEquals(ExternalEffectState.CONFIRMED, second.state)
        assertEquals(1, harness.transportCalls)
        assertEquals(1, harness.reconcileCalls)
    }

    @Test
    fun challengeResumeUsesSameChallengeAndTransitionsToConfirmed() = runTest {
        val harness = harness(
            firstTransport = ExternalTransportResult.ChallengeRequired(
                challengeId = "mfa-1",
                reason = "mfa-required",
            ),
            resumedTransport = ExternalTransportResult.Confirmed(
                externalReference = "remote-2",
                observationFingerprint = "b".repeat(64),
            ),
        )

        val waiting = harness.executor.execute(harness.contract)
        val confirmed = harness.executor.resumeAfterChallenge(
            harness.contract,
            ExternalChallengeResolution(
                challengeId = "mfa-1",
                confirmedByUser = true,
                evidenceFingerprint = "c".repeat(64),
            ),
        )

        assertEquals(ExternalEffectState.WAITING_FOR_USER, waiting.state)
        assertEquals(ExternalEffectState.CONFIRMED, confirmed.state)
        assertEquals(2, harness.transportCalls)
    }

    @Test
    fun wrongChallengeIdCannotResumeOrTouchTransport() = runTest {
        val harness = harness(
            firstTransport = ExternalTransportResult.ChallengeRequired(
                challengeId = "mfa-1",
                reason = "mfa-required",
            ),
        )
        harness.executor.execute(harness.contract)
        val callsBefore = harness.transportCalls

        assertFailsWith<IllegalArgumentException> {
            harness.executor.resumeAfterChallenge(
                harness.contract,
                ExternalChallengeResolution(
                    challengeId = "mfa-other",
                    confirmedByUser = true,
                    evidenceFingerprint = "d".repeat(64),
                ),
            )
        }
        assertEquals(callsBefore, harness.transportCalls)
    }

    @Test
    fun missingPayloadFailsBeforeTransport() = runTest {
        val harness = harness(persistPayload = false)

        val receipt = harness.executor.execute(harness.contract)

        assertEquals(ExternalEffectState.FAILED, receipt.state)
        assertEquals(0, harness.transportCalls)
    }

    @Test
    fun terminalReceiptIsReturnedWithoutReexecution() = runTest {
        val harness = harness(
            firstTransport = ExternalTransportResult.Confirmed(
                externalReference = "remote-terminal",
                observationFingerprint = "e".repeat(64),
            )
        )

        val first = harness.executor.execute(harness.contract)
        val second = harness.executor.execute(harness.contract)

        assertEquals(ExternalEffectState.CONFIRMED, first.state)
        assertEquals(first, second)
        assertEquals(1, harness.transportCalls)
        assertEquals(0, harness.reconcileCalls)
    }

    private suspend fun harness(
        firstTransport: ExternalTransportResult = ExternalTransportResult.Confirmed(
            externalReference = "remote-default",
            observationFingerprint = "f".repeat(64),
        ),
        resumedTransport: ExternalTransportResult = firstTransport,
        reconcile: ExternalTransportResult = firstTransport,
        persistPayload: Boolean = true,
    ): Harness {
        val ownerRepo = TestOwnerPolicyRepository()
        val ledger = OwnerPolicyLedger(ownerRepo) { now }
        ledger.grant(
            OwnerPolicyGrant.create(
                actorId = OwnerActorId("external-test"),
                effect = OwnerEffectType.NETWORK_ACCESS,
                resource = OwnerResourceSelector(
                    OwnerResourceSelectorType.EXACT,
                    ENDPOINT,
                ),
                scope = SCOPE,
                validFrom = now.minusSeconds(60),
            )
        )
        val receiptRepo = TestReceiptRepository()
        val payloadRepo = TestPayloadRepository()
        if (persistPayload) payloadRepo.persist(handle, payload)
        var transportCalls = 0
        var reconcileCalls = 0
        val executor = PolicyGatedExternalEffectExecutor(
            policyGate = OwnerPolicyEffectGate(ledger),
            receipts = receiptRepo,
            payloads = payloadRepo,
            transport = ExternalEffectTransport { _, _, challenge ->
                transportCalls += 1
                if (challenge == null) firstTransport else resumedTransport
            },
            observationReconciler = ExternalObservationReconciler { _, _ ->
                reconcileCalls += 1
                reconcile
            },
            now = { now },
        )
        val contract = ExternalActionContract(
            actionId = "action-1",
            idempotencyKey = "idem-1",
            endpoint = ExternalEndpoint(ENDPOINT),
            operation = "send",
            payloadHandle = handle,
            payloadFingerprint = handle.fingerprint,
            requiredOwnerPolicy = OwnerEffectRequest(
                actorId = OwnerActorId("external-test"),
                effect = OwnerEffectType.NETWORK_ACCESS,
                resource = ENDPOINT,
                scope = SCOPE,
            ),
            resourceReservation = null,
            sourcePhotonRefs = setOf(PhotonRevisionRef(PhotonId("source"), 1)),
            expectedObservation = ExternalObservation(
                kind = "receipt",
                expectedFingerprint = "0".repeat(64),
            ),
        )
        return Harness(
            executor = executor,
            contract = contract,
            transportCallsProvider = { transportCalls },
            reconcileCallsProvider = { reconcileCalls },
        )
    }

    private data class Harness(
        val executor: PolicyGatedExternalEffectExecutor,
        val contract: ExternalActionContract,
        val transportCallsProvider: () -> Int,
        val reconcileCallsProvider: () -> Int,
    ) {
        val transportCalls: Int get() = transportCallsProvider()
        val reconcileCalls: Int get() = reconcileCallsProvider()
    }

    private class TestReceiptRepository : ExternalEffectReceiptRepository {
        private val receipts = linkedMapOf<String, EffectReceipt>()

        override suspend fun load(actionId: String): EffectReceipt? = receipts[actionId]

        override suspend fun save(receipt: EffectReceipt) {
            receipts[receipt.actionId] = receipt
        }
    }

    private class TestPayloadRepository : ExternalPayloadRepository {
        private val payloads = linkedMapOf<PayloadHandle, ByteArray>()

        override suspend fun persist(handle: PayloadHandle, payload: ByteArray) {
            payloads[handle] = payload.copyOf()
        }

        override suspend fun load(handle: PayloadHandle): ByteArray? =
            payloads[handle]?.copyOf()
    }

    private class TestOwnerPolicyRepository : OwnerPolicyRepository {
        private val events = mutableListOf<OwnerPolicyEvent>()

        override suspend fun loadReport(): OwnerPolicyRepositoryLoadReport =
            OwnerPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(expectedRevision: Long, event: OwnerPolicyEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            events += event
            return true
        }
    }

    private companion object {
        const val ENDPOINT = "test://external-effect"
        const val SCOPE = "goal:test"
    }
}
