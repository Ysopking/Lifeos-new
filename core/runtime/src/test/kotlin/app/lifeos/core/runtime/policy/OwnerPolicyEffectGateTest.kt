package app.lifeos.core.runtime.policy

import app.lifeos.core.runtime.capability.CapabilityId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OwnerPolicyEffectGateTest {
    @Test
    fun simulationIsReadOnlyAndCorrelatesWithUnchangedLiveDecision() = runTest {
        val now = Instant.parse("2026-09-11T12:00:00Z")
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { now }
        val request = request()
        ledger.grant(grant(now))
        val beforeEvents = repository.events.size

        val simulated = OwnerPolicyEffectGate(ledger).simulate(request)
        val live = OwnerPolicyEffectGate(ledger).assessLive(request)

        assertTrue(simulated.allowed)
        assertEquals(OwnerPolicyEvaluationMode.SIMULATION, simulated.mode)
        assertEquals(simulated.decisionId, live.decisionId)
        assertEquals(beforeEvents, repository.events.size)
    }

    @Test
    fun revocationAfterPreparationWinsImmediatelyBeforeExposure() = runTest {
        val now = Instant.parse("2026-09-11T12:00:00Z")
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { now }
        val grant = grant(now)
        val request = request()
        ledger.grant(grant)
        val gate = OwnerPolicyEffectGate(ledger)
        val prepared = assertIs<OwnerEffectPreparationResult.Ready>(gate.prepare(request)).preparation
        ledger.revoke(grant.id)
        var effects = 0

        val exposure = gate.expose(request, prepared) {
            effects += 1
            "executed"
        }

        val blocked = assertIs<OwnerEffectExposureResult.Blocked>(exposure)
        assertEquals(0, effects)
        assertFalse(blocked.assessment.allowed)
        assertEquals(2L, blocked.assessment.policyRevision)
    }

    @Test
    fun interveningPolicyRevisionInvalidatesPreparedAuthorityEvenIfGrantStillExists() = runTest {
        val now = Instant.parse("2026-09-11T12:00:00Z")
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { now }
        val request = request()
        ledger.grant(grant(now))
        val gate = OwnerPolicyEffectGate(ledger)
        val prepared = assertIs<OwnerEffectPreparationResult.Ready>(gate.prepare(request)).preparation
        ledger.grant(
            OwnerPolicyGrant.create(
                actorId = OwnerActorId("other"),
                effect = OwnerEffectType.FILE_WRITE,
                resource = OwnerResourceSelector(OwnerResourceSelectorType.ANY),
                scope = "other",
                validFrom = now.minusSeconds(1),
            )
        )
        var effects = 0

        val exposure = gate.expose(request, prepared) { effects += 1 }

        val blocked = assertIs<OwnerEffectExposureResult.Blocked>(exposure)
        assertEquals(0, effects)
        assertEquals(
            listOf(OwnerPolicyReasonCode.POLICY_CHANGED_SINCE_PREPARATION),
            blocked.assessment.reasonCodes,
        )
    }

    @Test
    fun corruptHistoryFailsClosedWithoutRunningEffect() = runTest {
        val now = Instant.parse("2026-09-11T12:00:00Z")
        val badGrant = grant(now)
        val corrupt = object : OwnerPolicyRepository {
            override suspend fun loadReport() = OwnerPolicyRepositoryLoadReport(
                events = listOf(
                    OwnerPolicyEvent(
                        revision = 2L,
                        type = OwnerPolicyEventType.GRANT,
                        recordedAt = now,
                        grant = badGrant,
                    )
                )
            )
            override suspend fun append(expectedRevision: Long, event: OwnerPolicyEvent) = false
        }
        var effects = 0
        val gate = OwnerPolicyEffectGate(OwnerPolicyLedger(corrupt) { now })

        val result = gate.expose(request()) { effects += 1 }

        val blocked = assertIs<OwnerEffectExposureResult.Blocked>(result)
        assertEquals(0, effects)
        assertEquals(listOf(OwnerPolicyReasonCode.POLICY_HISTORY_INVALID), blocked.assessment.reasonCodes)
    }

    @Test
    fun exactRequestIdentityIsCaseSensitive() = runTest {
        val upper = request().copy(resource = "https://api.example/Search")
        val lower = request().copy(resource = "https://api.example/search")

        assertNotEquals(
            OwnerPolicyEffectGate.requestFingerprint(upper),
            OwnerPolicyEffectGate.requestFingerprint(lower),
        )
    }

    @Test
    fun unchangedPreparedAuthorityExecutesExactlyOnce() = runTest {
        val now = Instant.parse("2026-09-11T12:00:00Z")
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { now }
        ledger.grant(grant(now))
        val request = request()
        val gate = OwnerPolicyEffectGate(ledger)
        val prepared = assertIs<OwnerEffectPreparationResult.Ready>(gate.prepare(request)).preparation
        var effects = 0

        val result = gate.expose(request, prepared) {
            effects += 1
            "ok"
        }

        val exposed = assertIs<OwnerEffectExposureResult.Exposed<String>>(result)
        assertEquals("ok", exposed.value)
        assertEquals(1, effects)
    }

    private fun request() = OwnerEffectRequest(
        actorId = OwnerActorId("goal-runtime"),
        effect = OwnerEffectType.NETWORK_ACCESS,
        resource = "https://api.example/search",
        scope = "goal:plan-1",
        capabilityId = CapabilityId("external-search"),
        providerVersion = "v2",
    )

    private fun grant(now: Instant) = OwnerPolicyGrant.create(
        actorId = OwnerActorId("goal-runtime"),
        effect = OwnerEffectType.NETWORK_ACCESS,
        resource = OwnerResourceSelector(OwnerResourceSelectorType.PREFIX, "https://api.example/"),
        scope = "goal:plan-1",
        capability = OwnerCapabilityConstraint(
            capabilityId = CapabilityId("external-search"),
            providerVersion = "v2",
        ),
        validFrom = now.minusSeconds(60),
    )

    private class TestRepository : OwnerPolicyRepository {
        val events = mutableListOf<OwnerPolicyEvent>()

        override suspend fun loadReport() = OwnerPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(expectedRevision: Long, event: OwnerPolicyEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            require(event.revision == expectedRevision + 1L)
            events += event
            return true
        }
    }
}
