package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectPreparation
import app.lifeos.core.runtime.policy.OwnerEffectPreparationResult
import app.lifeos.core.runtime.policy.OwnerEffectExposureResult
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyAssessment
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class PimActionKind(
    val capabilityValue: String,
    val writeEffect: OwnerEffectType?,
    val writePermission: String?,
) {
    CALENDAR_READ("calendar.read", null, null),
    CALENDAR_CREATE(
        "calendar.event.create",
        OwnerEffectType.CALENDAR_WRITE,
        WRITE_CALENDAR_PERMISSION,
    ),
    CALENDAR_UPDATE(
        "calendar.event.update",
        OwnerEffectType.CALENDAR_WRITE,
        WRITE_CALENDAR_PERMISSION,
    ),
    CONTACT_RESOLVE("contact.resolve", null, null),
    CONTACT_CREATE(
        "contact.create",
        OwnerEffectType.CONTACT_WRITE,
        WRITE_CONTACTS_PERMISSION,
    ),
    CONTACT_UPDATE(
        "contact.update",
        OwnerEffectType.CONTACT_WRITE,
        WRITE_CONTACTS_PERMISSION,
    ),
    ;

    val capabilityId: CapabilityId
        get() = CapabilityId(capabilityValue)

    val mutating: Boolean
        get() = writeEffect != null
}

data class CalendarEventRef(
    val calendarId: Long,
    val eventId: Long,
) {
    init {
        require(calendarId > 0L)
        require(eventId > 0L)
    }

    val policyResource: String
        get() = "android-calendar://calendar/" + calendarId + "/event/" + eventId

    fun fingerprint(): String = pimFingerprint(
        "calendar-event-ref/v1",
        calendarId.toString(),
        eventId.toString(),
    )
}

data class CalendarEventState(
    val calendarId: Long,
    val title: String,
    val description: String = "",
    val location: String = "",
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    val timeZone: String,
) {
    init {
        require(calendarId > 0L)
        require(title.length <= MAX_TITLE_CHARS)
        require(description.length <= MAX_DESCRIPTION_CHARS)
        require(location.length <= MAX_LOCATION_CHARS)
        require(startMillis >= 0L)
        require(endMillis >= startMillis)
        require(timeZone.isNotBlank() && timeZone.length <= MAX_TIMEZONE_CHARS)
        require(title.none(Char::isISOControl))
        require(description.none { it == '\u0000' })
        require(location.none { it == '\u0000' })
        require(timeZone.none(Char::isISOControl))
    }

    fun fingerprint(): String = pimFingerprint(
        "calendar-event-state/v1",
        calendarId.toString(),
        title,
        description,
        location,
        startMillis.toString(),
        endMillis.toString(),
        allDay.toString(),
        timeZone,
    )
}

data class CalendarEventRevision(
    val ref: CalendarEventRef,
    val state: CalendarEventState,
) {
    init {
        require(ref.calendarId == state.calendarId)
    }

    fun fingerprint(): String = pimFingerprint(
        "calendar-event-revision/v1",
        ref.fingerprint(),
        state.fingerprint(),
    )
}

data class CalendarQuery(
    val fromMillis: Long? = null,
    val untilMillis: Long? = null,
    val maxResults: Int = 128,
) {
    init {
        require(fromMillis == null || fromMillis >= 0L)
        require(untilMillis == null || untilMillis >= 0L)
        require(fromMillis == null || untilMillis == null || untilMillis >= fromMillis)
        require(maxResults in 1..MAX_PIM_QUERY_RESULTS)
    }

    fun fingerprint(): String = pimFingerprint(
        "calendar-query/v1",
        fromMillis?.toString().orEmpty(),
        untilMillis?.toString().orEmpty(),
        maxResults.toString(),
    )
}

data class CalendarCreateRequest(
    val state: CalendarEventState,
) {
    init {
        require(state.title.isNotBlank()) { "Calendar create title must not be blank" }
    }

    val policyResource: String
        get() = "android-calendar://calendar/" + state.calendarId + "/events"

    fun fingerprint(): String = pimFingerprint(
        "calendar-create/v1",
        state.fingerprint(),
    )
}

data class CalendarUpdateRequest(
    val expectedRevision: CalendarEventRevision,
    val nextState: CalendarEventState,
) {
    init {
        require(expectedRevision.ref.calendarId == nextState.calendarId)
        require(nextState.title.isNotBlank()) { "Calendar update title must not be blank" }
    }

    val ref: CalendarEventRef
        get() = expectedRevision.ref

    fun fingerprint(): String = pimFingerprint(
        "calendar-update/v1",
        expectedRevision.fingerprint(),
        nextState.fingerprint(),
    )
}

data class ContactRef(
    val contactId: Long,
    val rawContactId: Long,
) {
    init {
        require(contactId > 0L)
        require(rawContactId > 0L)
    }

    val policyResource: String
        get() = "android-contacts://contact/" + contactId + "/raw/" + rawContactId

    fun fingerprint(): String = pimFingerprint(
        "contact-ref/v1",
        contactId.toString(),
        rawContactId.toString(),
    )
}

data class ContactState(
    val displayName: String,
    val phone: String? = null,
    val email: String? = null,
) {
    init {
        require(displayName.length <= MAX_CONTACT_NAME_CHARS)
        require(phone == null || phone.length <= MAX_CONTACT_VALUE_CHARS)
        require(email == null || email.length <= MAX_CONTACT_VALUE_CHARS)
        require(displayName.none(Char::isISOControl))
        require(phone == null || phone.none(Char::isISOControl))
        require(email == null || email.none(Char::isISOControl))
    }

    fun fingerprint(): String = pimFingerprint(
        "contact-state/v1",
        displayName,
        phone.orEmpty(),
        email.orEmpty(),
    )
}

data class ContactRevision(
    val ref: ContactRef,
    val state: ContactState,
    val updatedAtMillis: Long,
) {
    init {
        require(updatedAtMillis >= 0L)
    }

    fun fingerprint(): String = pimFingerprint(
        "contact-revision/v1",
        ref.fingerprint(),
        state.fingerprint(),
        updatedAtMillis.toString(),
    )
}

data class ContactResolveQuery(
    val query: String,
    val maxResults: Int = 32,
) {
    init {
        require(query.isNotBlank() && query.length <= MAX_CONTACT_QUERY_CHARS)
        require(query.none(Char::isISOControl))
        require(maxResults in 1..MAX_CONTACT_RESULTS)
    }

    val normalized: String
        get() = query.trim().replace(Regex("\\s+"), " ")

    fun fingerprint(): String = pimFingerprint(
        "contact-resolve-query/v1",
        normalized,
        maxResults.toString(),
    )
}

data class ContactCreateRequest(
    val state: ContactState,
) {
    init {
        require(state.displayName.isNotBlank()) { "Contact create name must not be blank" }
    }

    val policyResource: String
        get() = "android-contacts://contacts"

    fun fingerprint(): String = pimFingerprint(
        "contact-create/v1",
        state.fingerprint(),
    )
}

data class ContactUpdateRequest(
    val expectedRevision: ContactRevision,
    val nextState: ContactState,
) {
    init {
        require(nextState.displayName.isNotBlank()) { "Contact update name must not be blank" }
    }

    val ref: ContactRef
        get() = expectedRevision.ref

    fun fingerprint(): String = pimFingerprint(
        "contact-update/v1",
        expectedRevision.fingerprint(),
        nextState.fingerprint(),
    )
}

interface CalendarContactHost {
    suspend fun readCalendar(query: CalendarQuery): List<CalendarEventRevision>

    suspend fun createCalendarEvent(request: CalendarCreateRequest): CalendarEventRevision

    suspend fun updateCalendarEvent(request: CalendarUpdateRequest): CalendarEventRevision

    suspend fun resolveContacts(query: ContactResolveQuery): List<ContactRevision>

    suspend fun createContact(request: ContactCreateRequest): ContactRevision

    suspend fun updateContact(request: ContactUpdateRequest): ContactRevision
}

sealed interface PimActionResult {
    val kind: PimActionKind
    val requestFingerprint: String

    data class CalendarRows(
        override val requestFingerprint: String,
        val rows: List<CalendarEventRevision>,
        val fingerprint: String,
    ) : PimActionResult {
        override val kind: PimActionKind = PimActionKind.CALENDAR_READ
    }

    data class ContactRows(
        override val requestFingerprint: String,
        val rows: List<ContactRevision>,
        val fingerprint: String,
    ) : PimActionResult {
        override val kind: PimActionKind = PimActionKind.CONTACT_RESOLVE
    }

    data class CalendarMutation(
        override val kind: PimActionKind,
        override val requestFingerprint: String,
        val revision: CalendarEventRevision,
        val policyAssessment: OwnerPolicyAssessment,
        val fingerprint: String,
    ) : PimActionResult {
        init {
            require(kind == PimActionKind.CALENDAR_CREATE || kind == PimActionKind.CALENDAR_UPDATE)
        }
    }

    data class ContactMutation(
        override val kind: PimActionKind,
        override val requestFingerprint: String,
        val revision: ContactRevision,
        val policyAssessment: OwnerPolicyAssessment,
        val fingerprint: String,
    ) : PimActionResult {
        init {
            require(kind == PimActionKind.CONTACT_CREATE || kind == PimActionKind.CONTACT_UPDATE)
        }
    }

    data class Blocked(
        override val kind: PimActionKind,
        override val requestFingerprint: String,
        val policyAssessment: OwnerPolicyAssessment,
    ) : PimActionResult
}

/**
 * B407 capability-bound PIM runtime. Android permission resolution remains B405's responsibility;
 * productive provider mutations additionally pass the existing JIT OwnerPolicyEffectGate.
 */
class CalendarContactRuntime(
    private val host: CalendarContactHost,
    private val ownerPolicyGate: OwnerPolicyEffectGate,
) {
    suspend fun readCalendar(
        plan: AndroidCapabilityDispatchPlan,
        query: CalendarQuery,
    ): PimActionResult.CalendarRows {
        requirePlan(plan, PimActionKind.CALENDAR_READ)
        val rows = host.readCalendar(query)
            .distinctBy { it.ref }
            .sortedWith(compareBy({ it.state.startMillis }, { it.ref.calendarId }, { it.ref.eventId }))
            .take(query.maxResults)
        val requestFingerprint = dispatchFingerprint(
            PimActionKind.CALENDAR_READ,
            plan,
            query.fingerprint(),
        )
        return PimActionResult.CalendarRows(
            requestFingerprint = requestFingerprint,
            rows = rows,
            fingerprint = pimFingerprint(
                "calendar-read-result/v1",
                requestFingerprint,
                rows.joinToString("\u001f") { it.fingerprint() },
            ),
        )
    }

    suspend fun resolveContacts(
        plan: AndroidCapabilityDispatchPlan,
        query: ContactResolveQuery,
    ): PimActionResult.ContactRows {
        requirePlan(plan, PimActionKind.CONTACT_RESOLVE)
        val rows = host.resolveContacts(query)
            .distinctBy { it.ref }
            .sortedWith(compareBy({ it.state.displayName.lowercase() }, { it.ref.contactId }, { it.ref.rawContactId }))
            .take(query.maxResults)
        val requestFingerprint = dispatchFingerprint(
            PimActionKind.CONTACT_RESOLVE,
            plan,
            query.fingerprint(),
        )
        return PimActionResult.ContactRows(
            requestFingerprint = requestFingerprint,
            rows = rows,
            fingerprint = pimFingerprint(
                "contact-resolve-result/v1",
                requestFingerprint,
                rows.joinToString("\u001f") { it.fingerprint() },
            ),
        )
    }

    suspend fun prepareCalendarCreate(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: CalendarCreateRequest,
    ): OwnerEffectPreparationResult {
        requirePlan(plan, PimActionKind.CALENDAR_CREATE)
        return ownerPolicyGate.prepare(
            ownerRequest(
                plan,
                actorId,
                PimActionKind.CALENDAR_CREATE,
                request.policyResource,
            )
        )
    }

    suspend fun createCalendarEvent(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: CalendarCreateRequest,
        prepared: OwnerEffectPreparation? = null,
    ): PimActionResult = calendarMutation(
        kind = PimActionKind.CALENDAR_CREATE,
        plan = plan,
        actorId = actorId,
        policyResource = request.policyResource,
        operationFingerprint = request.fingerprint(),
        prepared = prepared,
    ) { host.createCalendarEvent(request) }

    suspend fun prepareCalendarUpdate(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: CalendarUpdateRequest,
    ): OwnerEffectPreparationResult {
        requirePlan(plan, PimActionKind.CALENDAR_UPDATE)
        return ownerPolicyGate.prepare(
            ownerRequest(plan, actorId, PimActionKind.CALENDAR_UPDATE, request.ref.policyResource)
        )
    }

    suspend fun updateCalendarEvent(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: CalendarUpdateRequest,
        prepared: OwnerEffectPreparation? = null,
    ): PimActionResult = calendarMutation(
        kind = PimActionKind.CALENDAR_UPDATE,
        plan = plan,
        actorId = actorId,
        policyResource = request.ref.policyResource,
        operationFingerprint = request.fingerprint(),
        prepared = prepared,
    ) {
        host.updateCalendarEvent(request).also {
            require(it.ref == request.ref) { "Calendar update redirected to another event" }
        }
    }

    suspend fun prepareContactCreate(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: ContactCreateRequest,
    ): OwnerEffectPreparationResult {
        requirePlan(plan, PimActionKind.CONTACT_CREATE)
        return ownerPolicyGate.prepare(
            ownerRequest(plan, actorId, PimActionKind.CONTACT_CREATE, request.policyResource)
        )
    }

    suspend fun createContact(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: ContactCreateRequest,
        prepared: OwnerEffectPreparation? = null,
    ): PimActionResult = contactMutation(
        kind = PimActionKind.CONTACT_CREATE,
        plan = plan,
        actorId = actorId,
        policyResource = request.policyResource,
        operationFingerprint = request.fingerprint(),
        prepared = prepared,
    ) { host.createContact(request) }

    suspend fun prepareContactUpdate(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: ContactUpdateRequest,
    ): OwnerEffectPreparationResult {
        requirePlan(plan, PimActionKind.CONTACT_UPDATE)
        return ownerPolicyGate.prepare(
            ownerRequest(plan, actorId, PimActionKind.CONTACT_UPDATE, request.ref.policyResource)
        )
    }

    suspend fun updateContact(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: ContactUpdateRequest,
        prepared: OwnerEffectPreparation? = null,
    ): PimActionResult = contactMutation(
        kind = PimActionKind.CONTACT_UPDATE,
        plan = plan,
        actorId = actorId,
        policyResource = request.ref.policyResource,
        operationFingerprint = request.fingerprint(),
        prepared = prepared,
    ) {
        host.updateContact(request).also {
            require(it.ref == request.ref) { "Contact update redirected to another record" }
        }
    }

    private suspend fun calendarMutation(
        kind: PimActionKind,
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        policyResource: String,
        operationFingerprint: String,
        prepared: OwnerEffectPreparation?,
        effect: suspend () -> CalendarEventRevision,
    ): PimActionResult {
        requirePlan(plan, kind)
        val requestFingerprint = dispatchFingerprint(kind, plan, operationFingerprint)
        return when (
            val exposure = ownerPolicyGate.expose(
                ownerRequest(plan, actorId, kind, policyResource),
                prepared,
                effect,
            )
        ) {
            is OwnerEffectExposureResult.Blocked -> PimActionResult.Blocked(
                kind,
                requestFingerprint,
                exposure.assessment,
            )
            is OwnerEffectExposureResult.Exposed -> PimActionResult.CalendarMutation(
                kind = kind,
                requestFingerprint = requestFingerprint,
                revision = exposure.value,
                policyAssessment = exposure.assessment,
                fingerprint = pimFingerprint(
                    "calendar-mutation-result/v1",
                    kind.name,
                    requestFingerprint,
                    exposure.value.fingerprint(),
                    exposure.assessment.decisionId.value,
                    exposure.assessment.policyRevision.toString(),
                ),
            )
        }
    }

    private suspend fun contactMutation(
        kind: PimActionKind,
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        policyResource: String,
        operationFingerprint: String,
        prepared: OwnerEffectPreparation?,
        effect: suspend () -> ContactRevision,
    ): PimActionResult {
        requirePlan(plan, kind)
        val requestFingerprint = dispatchFingerprint(kind, plan, operationFingerprint)
        return when (
            val exposure = ownerPolicyGate.expose(
                ownerRequest(plan, actorId, kind, policyResource),
                prepared,
                effect,
            )
        ) {
            is OwnerEffectExposureResult.Blocked -> PimActionResult.Blocked(
                kind,
                requestFingerprint,
                exposure.assessment,
            )
            is OwnerEffectExposureResult.Exposed -> PimActionResult.ContactMutation(
                kind = kind,
                requestFingerprint = requestFingerprint,
                revision = exposure.value,
                policyAssessment = exposure.assessment,
                fingerprint = pimFingerprint(
                    "contact-mutation-result/v1",
                    kind.name,
                    requestFingerprint,
                    exposure.value.fingerprint(),
                    exposure.assessment.decisionId.value,
                    exposure.assessment.policyRevision.toString(),
                ),
            )
        }
    }

    private fun requirePlan(
        plan: AndroidCapabilityDispatchPlan,
        kind: PimActionKind,
    ) {
        require(plan.capabilityId == kind.capabilityId) {
            "Android capability dispatch plan does not match PIM action"
        }
        if (!kind.mutating) {
            require(plan.binding.requiredOwnerEffect == null) {
                "Read-only PIM action cannot ignore an owner-effect requirement"
            }
            return
        }

        require(plan.binding.requiredOwnerEffect == kind.writeEffect) {
            "PIM mutation is bound to the wrong Owner Policy effect"
        }
        val permission = requireNotNull(kind.writePermission)
        require(
            plan.binding.permissions.any {
                it.kind == AndroidPermissionKind.RUNTIME_PERMISSION && it.name == permission
            }
        ) {
            "PIM mutation is missing the exact Android write permission contract"
        }
    }

    private fun ownerRequest(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        kind: PimActionKind,
        resource: String,
    ): OwnerEffectRequest = OwnerEffectRequest(
        actorId = actorId,
        effect = requireNotNull(kind.writeEffect),
        resource = resource,
        scope = plan.binding.ownerScope,
        capabilityId = plan.capabilityId,
        providerVersion = plan.binding.providerVersion,
    )

    private fun dispatchFingerprint(
        kind: PimActionKind,
        plan: AndroidCapabilityDispatchPlan,
        operationFingerprint: String,
    ): String = pimFingerprint(
        "pim-dispatch/v1",
        kind.name,
        plan.fingerprint(),
        operationFingerprint,
    )
}

const val WRITE_CALENDAR_PERMISSION = "android.permission.WRITE_CALENDAR"
const val WRITE_CONTACTS_PERMISSION = "android.permission.WRITE_CONTACTS"

private const val MAX_PIM_QUERY_RESULTS = 512
private const val MAX_CONTACT_RESULTS = 128
private const val MAX_CONTACT_QUERY_CHARS = 512
private const val MAX_TITLE_CHARS = 1024
private const val MAX_DESCRIPTION_CHARS = 16 * 1024
private const val MAX_LOCATION_CHARS = 2048
private const val MAX_TIMEZONE_CHARS = 128
private const val MAX_CONTACT_NAME_CHARS = 512
private const val MAX_CONTACT_VALUE_CHARS = 2048

private fun pimFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(domain, *parts).forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
