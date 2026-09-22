package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerCapabilityConstraint
import app.lifeos.core.runtime.policy.OwnerEffectPreparationResult
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CalendarContactRuntimeTest {
    @Test
    fun calendar_read_is_bounded_and_deterministic() = runTest {
        val host = FakeHost()
        host.calendarRows += calendarRevision(2L, start = 200L)
        host.calendarRows += calendarRevision(1L, start = 100L)
        val runtime = runtime(host)

        val result = runtime.readCalendar(
            plan(PimActionKind.CALENDAR_READ),
            CalendarQuery(maxResults = 1),
        )

        assertEquals(1, result.rows.size)
        assertEquals(1L, result.rows.single().ref.eventId)
        assertTrue(result.fingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun mutation_requires_exact_android_write_permission_metadata() = runTest {
        val host = FakeHost()
        val runtime = runtime(host)
        val request = CalendarCreateRequest(calendarState())

        assertFailsWith<IllegalArgumentException> {
            runtime.createCalendarEvent(
                plan = plan(
                    kind = PimActionKind.CALENDAR_CREATE,
                    includePermission = false,
                ),
                actorId = OWNER,
                request = request,
            )
        }
        assertEquals(0, host.calendarCreateCalls)
    }

    @Test
    fun calendar_create_without_owner_grant_fails_before_host() = runTest {
        val host = FakeHost()
        val runtime = runtime(host)
        val result = runtime.createCalendarEvent(
            plan = plan(PimActionKind.CALENDAR_CREATE),
            actorId = OWNER,
            request = CalendarCreateRequest(calendarState()),
        )

        assertIs<PimActionResult.Blocked>(result)
        assertEquals(0, host.calendarCreateCalls)
    }

    @Test
    fun revocation_after_calendar_preparation_wins_at_exposure() = runTest {
        val now = NOW
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { now }
        val gate = OwnerPolicyEffectGate(ledger)
        val host = FakeHost()
        val runtime = CalendarContactRuntime(host, gate)
        val plan = plan(PimActionKind.CALENDAR_CREATE)
        val request = CalendarCreateRequest(calendarState())
        val grant = grant(now, plan, OwnerEffectType.CALENDAR_WRITE)
        ledger.grant(grant)
        val prepared = assertIs<OwnerEffectPreparationResult.Ready>(
            runtime.prepareCalendarCreate(plan, OWNER, request)
        ).preparation
        ledger.revoke(grant.id)

        val result = runtime.createCalendarEvent(plan, OWNER, request, prepared)

        assertIs<PimActionResult.Blocked>(result)
        assertEquals(0, host.calendarCreateCalls)
    }

    @Test
    fun calendar_update_cannot_redirect_to_another_event() = runTest {
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { NOW }
        val gate = OwnerPolicyEffectGate(ledger)
        val host = FakeHost().apply { redirectCalendarUpdate = true }
        val runtime = CalendarContactRuntime(host, gate)
        val plan = plan(PimActionKind.CALENDAR_UPDATE)
        ledger.grant(grant(NOW, plan, OwnerEffectType.CALENDAR_WRITE))
        val existing = calendarRevision(7L, start = 100L)
        val request = CalendarUpdateRequest(
            expectedRevision = existing,
            nextState = existing.state.copy(title = "changed"),
        )

        assertFailsWith<IllegalArgumentException> {
            runtime.updateCalendarEvent(plan, OWNER, request)
        }
    }

    @Test
    fun contact_resolve_is_canonical_and_bounded() = runTest {
        val host = FakeHost()
        host.contactRows += contactRevision(2L, "Zed")
        host.contactRows += contactRevision(1L, "Anna")
        val runtime = runtime(host)

        val result = runtime.resolveContacts(
            plan(PimActionKind.CONTACT_RESOLVE),
            ContactResolveQuery("  AnNa  ", maxResults = 1),
        )

        assertEquals(1, result.rows.size)
        assertEquals("Anna", result.rows.single().state.displayName)
    }

    @Test
    fun contact_update_uses_contact_write_owner_effect() = runTest {
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { NOW }
        val plan = plan(PimActionKind.CONTACT_UPDATE)
        ledger.grant(grant(NOW, plan, OwnerEffectType.CONTACT_WRITE))
        val host = FakeHost()
        val runtime = CalendarContactRuntime(host, OwnerPolicyEffectGate(ledger))
        val existing = contactRevision(4L, "Before")
        host.contactRows += existing

        val result = runtime.updateContact(
            plan = plan,
            actorId = OWNER,
            request = ContactUpdateRequest(
                expectedRevision = existing,
                nextState = ContactState("After", phone = "+49123"),
            ),
        )

        val mutation = assertIs<PimActionResult.ContactMutation>(result)
        assertTrue(mutation.policyAssessment.allowed)
        assertEquals("After", mutation.revision.state.displayName)
        assertEquals(1, host.contactUpdateCalls)
    }

    private fun runtime(host: FakeHost): CalendarContactRuntime =
        CalendarContactRuntime(
            host,
            OwnerPolicyEffectGate(OwnerPolicyLedger(TestRepository()) { NOW }),
        )

    private fun plan(
        kind: PimActionKind,
        includePermission: Boolean = true,
    ): AndroidCapabilityDispatchPlan {
        val descriptor = CapabilityDescriptor(
            capabilityId = kind.capabilityId,
            providerId = "android.pim",
            providerType = ProviderType.MODULE,
            contract = CapabilityContract(
                requiredInputs = setOf("request"),
                outputs = setOf("result"),
            ),
            state = ProviderState.ACTIVE,
            trustLevel = TrustLevel.SYSTEM,
            reliability = 1.0,
        )
        val permissions = if (includePermission && kind.writePermission != null) {
            listOf(
                AndroidPermissionRequirement(
                    AndroidPermissionKind.RUNTIME_PERMISSION,
                    kind.writePermission,
                )
            )
        } else {
            emptyList()
        }
        return AndroidCapabilityDispatchPlan(
            requestFingerprint = "c".repeat(64),
            binding = AndroidCapabilityBinding(
                descriptor = descriptor,
                providerVersion = "b407-v1",
                requiredOwnerEffect = kind.writeEffect,
                ownerScope = kind.capabilityValue,
                permissions = permissions,
                riskClass = if (kind.mutating) AndroidCapabilityRiskClass.MEDIUM else AndroidCapabilityRiskClass.LOW,
                reversibility = if (kind.mutating) {
                    AndroidCapabilityReversibility.COMPENSATABLE
                } else {
                    AndroidCapabilityReversibility.REVERSIBLE
                },
                recoverySemantics = AndroidRecoverySemantics.RETRY_SAFE,
                expectedOutcomeContract = "result",
            ),
        )
    }

    private fun grant(
        now: Instant,
        plan: AndroidCapabilityDispatchPlan,
        effect: OwnerEffectType,
    ): OwnerPolicyGrant = OwnerPolicyGrant.create(
        actorId = OWNER,
        effect = effect,
        resource = OwnerResourceSelector(OwnerResourceSelectorType.ANY),
        scope = plan.binding.ownerScope,
        capability = OwnerCapabilityConstraint(
            capabilityId = plan.capabilityId,
            providerVersion = plan.binding.providerVersion,
        ),
        validFrom = now.minusSeconds(1),
    )

    private fun calendarState(): CalendarEventState =
        CalendarEventState(
            calendarId = 11L,
            title = "Title",
            startMillis = 100L,
            endMillis = 200L,
            timeZone = "Europe/Berlin",
        )

    private fun calendarRevision(
        eventId: Long,
        start: Long,
    ): CalendarEventRevision {
        val state = calendarState().copy(startMillis = start, endMillis = start + 100L)
        return CalendarEventRevision(
            CalendarEventRef(state.calendarId, eventId),
            state,
        )
    }

    private fun contactRevision(
        id: Long,
        name: String,
    ): ContactRevision = ContactRevision(
        ref = ContactRef(contactId = id, rawContactId = id + 100L),
        state = ContactState(name),
        updatedAtMillis = 100L + id,
    )

    private class FakeHost : CalendarContactHost {
        val calendarRows = mutableListOf<CalendarEventRevision>()
        val contactRows = mutableListOf<ContactRevision>()
        var calendarCreateCalls = 0
        var contactUpdateCalls = 0
        var redirectCalendarUpdate = false

        override suspend fun readCalendar(query: CalendarQuery): List<CalendarEventRevision> =
            calendarRows.filter { row ->
                (query.fromMillis == null || row.state.endMillis >= query.fromMillis) &&
                    (query.untilMillis == null || row.state.startMillis <= query.untilMillis)
            }.sortedWith(
                compareBy<CalendarEventRevision> { it.state.startMillis }
                    .thenBy { it.ref.calendarId }
                    .thenBy { it.ref.eventId }
            ).take(query.maxResults)

        override suspend fun createCalendarEvent(
            request: CalendarCreateRequest,
        ): CalendarEventRevision {
            calendarCreateCalls += 1
            return CalendarEventRevision(
                CalendarEventRef(request.state.calendarId, 900L + calendarCreateCalls),
                request.state,
            )
        }

        override suspend fun updateCalendarEvent(
            request: CalendarUpdateRequest,
        ): CalendarEventRevision =
            CalendarEventRevision(
                ref = if (redirectCalendarUpdate) {
                    request.ref.copy(eventId = request.ref.eventId + 1L)
                } else {
                    request.ref
                },
                state = request.nextState,
            )

        override suspend fun resolveContacts(
            query: ContactResolveQuery,
        ): List<ContactRevision> =
            contactRows.filter {
                it.state.displayName.contains(query.normalized, ignoreCase = true)
            }.sortedWith(
                compareBy<ContactRevision> { it.state.displayName.lowercase() }
                    .thenBy { it.ref.contactId }
                    .thenBy { it.ref.rawContactId }
            ).take(query.maxResults)

        override suspend fun createContact(
            request: ContactCreateRequest,
        ): ContactRevision =
            ContactRevision(
                ContactRef(700L, 800L),
                request.state,
                1L,
            )

        override suspend fun updateContact(
            request: ContactUpdateRequest,
        ): ContactRevision {
            contactUpdateCalls += 1
            return ContactRevision(
                request.ref,
                request.nextState,
                request.expectedRevision.updatedAtMillis + 1L,
            )
        }
    }

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

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
        val OWNER = OwnerActorId("calendar-contact-runtime")
    }
}
