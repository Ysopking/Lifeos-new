package app.lifeos.core.runtime.policy

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetReservationId
import java.time.Instant

@JvmInline
value class OwnerActorId(val value: String) {
    init { require(value.isNotBlank()) { "Owner policy actor id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class OwnerPolicyGrantId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid owner policy grant id prefix" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid owner policy grant id digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "owner-policy-grant:"
    }
}

enum class OwnerEffectType {
    FILE_WRITE,
    CALENDAR_WRITE,
    CONTACT_WRITE,
    NOTIFICATION_ACTION,
    NETWORK_ACCESS,
    REMINDER,
    COMMUNICATION,
    PROVIDER_ACTIVATION,
    TOOL_REQUEST,
    TOOL_EXECUTION,
    EXTERNAL_APP_HANDOFF,
}

enum class OwnerResourceSelectorType {
    EXACT,
    PREFIX,
    ANY,
}

data class OwnerResourceSelector(
    val type: OwnerResourceSelectorType,
    val value: String? = null,
) {
    init {
        when (type) {
            OwnerResourceSelectorType.EXACT,
            OwnerResourceSelectorType.PREFIX -> require(!value.isNullOrBlank()) {
                "Exact/prefix owner resource selector requires a value"
            }
            OwnerResourceSelectorType.ANY -> require(value == null) {
                "ANY owner resource selector cannot carry a value"
            }
        }
    }

    fun matches(resource: String): Boolean {
        require(resource.isNotBlank()) { "Owner effect resource must not be blank" }
        return when (type) {
            OwnerResourceSelectorType.EXACT -> resource == value
            OwnerResourceSelectorType.PREFIX -> resource.startsWith(requireNotNull(value))
            OwnerResourceSelectorType.ANY -> true
        }
    }

    fun fingerprintParts(): List<String> = listOf(type.name, value.orEmpty())
}

data class OwnerCapabilityConstraint(
    val capabilityId: CapabilityId,
    val providerVersion: String? = null,
) {
    init { require(providerVersion == null || providerVersion.isNotBlank()) }

    fun matches(capabilityId: CapabilityId?, providerVersion: String?): Boolean =
        capabilityId == this.capabilityId &&
            (this.providerVersion == null || providerVersion == this.providerVersion)
}

/**
 * Immutable V14 owner grant. Scope and resource matching are explicit; no implicit wildcard is
 * introduced when a field is missing. A grant may bind an effect to one durable V16 budget account.
 */
data class OwnerPolicyGrant(
    val id: OwnerPolicyGrantId,
    val actorId: OwnerActorId,
    val effect: OwnerEffectType,
    val resource: OwnerResourceSelector,
    val scope: String,
    val capability: OwnerCapabilityConstraint? = null,
    val budgetAccountId: ResourceBudgetAccountId? = null,
    val validFrom: Instant,
    val validUntil: Instant? = null,
) {
    init {
        require(scope.isNotBlank()) { "Owner policy scope must not be blank" }
        require(validUntil == null || validUntil.isAfter(validFrom)) {
            "Owner policy validity window must be increasing"
        }
        require(id == expectedId()) { "Owner policy grant id/content mismatch" }
    }

    fun isValidAt(at: Instant): Boolean =
        !at.isBefore(validFrom) && (validUntil == null || at.isBefore(validUntil))

    private fun expectedId(): OwnerPolicyGrantId = expectedId(
        actorId = actorId,
        effect = effect,
        resource = resource,
        scope = scope,
        capability = capability,
        budgetAccountId = budgetAccountId,
        validFrom = validFrom,
        validUntil = validUntil,
    )

    companion object {
        fun create(
            actorId: OwnerActorId,
            effect: OwnerEffectType,
            resource: OwnerResourceSelector,
            scope: String,
            capability: OwnerCapabilityConstraint? = null,
            budgetAccountId: ResourceBudgetAccountId? = null,
            validFrom: Instant,
            validUntil: Instant? = null,
        ): OwnerPolicyGrant = OwnerPolicyGrant(
            id = expectedId(
                actorId,
                effect,
                resource,
                scope,
                capability,
                budgetAccountId,
                validFrom,
                validUntil,
            ),
            actorId = actorId,
            effect = effect,
            resource = resource,
            scope = scope,
            capability = capability,
            budgetAccountId = budgetAccountId,
            validFrom = validFrom,
            validUntil = validUntil,
        )

        private fun expectedId(
            actorId: OwnerActorId,
            effect: OwnerEffectType,
            resource: OwnerResourceSelector,
            scope: String,
            capability: OwnerCapabilityConstraint?,
            budgetAccountId: ResourceBudgetAccountId?,
            validFrom: Instant,
            validUntil: Instant?,
        ): OwnerPolicyGrantId = OwnerPolicyGrantId(
            OwnerPolicyGrantId.PREFIX + StableFieldIds.fingerprint(
                "owner-policy-grant/v1",
                actorId.value,
                effect.name,
                *resource.fingerprintParts().toTypedArray(),
                "scope:$scope",
                "capability:${capability?.capabilityId?.value.orEmpty()}",
                "provider-version:${capability?.providerVersion.orEmpty()}",
                "budget-account:${budgetAccountId?.value.orEmpty()}",
                "valid-from:$validFrom",
                "valid-until:${validUntil?.toString().orEmpty()}",
            )
        )
    }
}

enum class OwnerPolicyEventType {
    GRANT,
    REVOKE,
}

data class OwnerPolicyEvent(
    val revision: Long,
    val type: OwnerPolicyEventType,
    val recordedAt: Instant,
    val grant: OwnerPolicyGrant? = null,
    val revokedGrantId: OwnerPolicyGrantId? = null,
) {
    init {
        require(revision > 0L) { "Owner policy revision must be positive" }
        when (type) {
            OwnerPolicyEventType.GRANT -> require(grant != null && revokedGrantId == null) {
                "Grant event requires exactly one grant"
            }
            OwnerPolicyEventType.REVOKE -> require(grant == null && revokedGrantId != null) {
                "Revoke event requires exactly one grant id"
            }
        }
    }
}

data class OwnerPolicyRepositoryLoadReport(
    val events: List<OwnerPolicyEvent>,
    val unreadableEntries: List<String> = emptyList(),
) {
    init { require(unreadableEntries.none { it.isBlank() }) }
}

/** Durable implementations must atomically append only when expectedRevision is still current. */
interface OwnerPolicyRepository {
    suspend fun loadReport(): OwnerPolicyRepositoryLoadReport

    suspend fun headRevision(): Long =
        loadReport().events.maxOfOrNull { it.revision } ?: 0L

    suspend fun loadAfter(revisionExclusive: Long): List<OwnerPolicyEvent> {
        require(revisionExclusive >= 0L)
        return loadReport().events
            .filter { it.revision > revisionExclusive }
            .sortedBy { it.revision }
    }

    suspend fun append(expectedRevision: Long, event: OwnerPolicyEvent): Boolean
}

data class OwnerPolicySnapshot(
    val revision: Long,
    val activeGrants: List<OwnerPolicyGrant>,
) {
    init {
        require(revision >= 0L)
        require(activeGrants.map { it.id }.distinct().size == activeGrants.size)
    }
}

enum class OwnerGrantHistoryState {
    NEVER_SEEN,
    ACTIVE,
    REVOKED,
}

data class OwnerEffectRequest(
    val actorId: OwnerActorId,
    val effect: OwnerEffectType,
    val resource: String,
    val scope: String,
    val capabilityId: CapabilityId? = null,
    val providerVersion: String? = null,
    val budgetAccountId: ResourceBudgetAccountId? = null,
    val budgetReservationId: ResourceBudgetReservationId? = null,
) {
    init {
        require(resource.isNotBlank()) { "Owner effect resource must not be blank" }
        require(scope.isNotBlank()) { "Owner effect scope must not be blank" }
        require(providerVersion == null || providerVersion.isNotBlank())
        require(budgetReservationId == null || budgetAccountId != null) {
            "Budget reservation requires a budget account"
        }
    }
}

sealed interface OwnerPolicyDecision {
    val policyRevision: Long

    data class Allowed(
        override val policyRevision: Long,
        val grantId: OwnerPolicyGrantId,
        val budgetReservationId: ResourceBudgetReservationId?,
    ) : OwnerPolicyDecision

    data class Blocked(
        override val policyRevision: Long,
        val reasons: List<String>,
    ) : OwnerPolicyDecision {
        init { require(reasons.isNotEmpty()) }
    }
}

/**
 * Shared fail-closed V14 ledger/evaluator. Every call reloads the durable ledger so a prepared or
 * resumed action observes revocation and policy changes immediately before its actual host effect.
 */
class OwnerPolicyLedger(
    private val repository: OwnerPolicyRepository,
    private val now: () -> Instant = Instant::now,
) {
    @Volatile
    private var cachedSnapshot: OwnerPolicySnapshot? = null

    suspend fun grant(grant: OwnerPolicyGrant): OwnerPolicyGrant {
        repeat(MAX_CAS_ATTEMPTS) {
            val snapshot = loadSnapshot()
            snapshot.activeGrants.firstOrNull { it.id == grant.id }?.let { return it }
            val event = OwnerPolicyEvent(
                revision = snapshot.revision + 1L,
                type = OwnerPolicyEventType.GRANT,
                recordedAt = now(),
                grant = grant,
            )
            if (repository.append(snapshot.revision, event)) return grant
        }
        error("Owner policy grant CAS retry limit exceeded")
    }

    suspend fun revoke(grantId: OwnerPolicyGrantId): OwnerPolicySnapshot {
        repeat(MAX_CAS_ATTEMPTS) {
            val snapshot = loadSnapshot()
            if (snapshot.activeGrants.none { it.id == grantId }) return snapshot
            val event = OwnerPolicyEvent(
                revision = snapshot.revision + 1L,
                type = OwnerPolicyEventType.REVOKE,
                recordedAt = now(),
                revokedGrantId = grantId,
            )
            if (repository.append(snapshot.revision, event)) return loadSnapshot()
        }
        error("Owner policy revoke CAS retry limit exceeded")
    }

    suspend fun evaluate(
        request: OwnerEffectRequest,
        at: Instant = now(),
    ): OwnerPolicyDecision {
        val snapshot = loadSnapshot()
        val candidates = snapshot.activeGrants
            .asSequence()
            .filter { it.actorId == request.actorId }
            .filter { it.effect == request.effect }
            .filter { it.scope == request.scope }
            .filter { it.resource.matches(request.resource) }
            .filter { it.isValidAt(at) }
            .filter { grant -> grant.capability?.matches(request.capabilityId, request.providerVersion) ?: true }
            .filter { grant ->
                grant.budgetAccountId == null ||
                    (grant.budgetAccountId == request.budgetAccountId && request.budgetReservationId != null)
            }
            .sortedWith(
                compareByDescending<OwnerPolicyGrant> { resourceSpecificity(it.resource) }
                    .thenByDescending { it.capability != null }
                    .thenByDescending { it.budgetAccountId != null }
                    .thenBy { it.id.value }
            )
            .toList()

        val selected = candidates.firstOrNull()
        return if (selected != null) {
            OwnerPolicyDecision.Allowed(
                policyRevision = snapshot.revision,
                grantId = selected.id,
                budgetReservationId = request.budgetReservationId,
            )
        } else {
            OwnerPolicyDecision.Blocked(
                policyRevision = snapshot.revision,
                reasons = denialReasons(snapshot, request, at),
            )
        }
    }

    suspend fun snapshot(): OwnerPolicySnapshot = loadSnapshot()

    suspend fun historyState(
        grantId: OwnerPolicyGrantId,
    ): OwnerGrantHistoryState {
        val report = repository.loadReport()
        check(report.unreadableEntries.isEmpty()) {
            "Owner policy history is unreadable: " +
                report.unreadableEntries.joinToString(",")
        }
        val latest = report.events
            .asSequence()
            .filter { event ->
                event.grant?.id == grantId ||
                    event.revokedGrantId == grantId
            }
            .maxByOrNull { it.revision }
            ?: return OwnerGrantHistoryState.NEVER_SEEN

        return when (latest.type) {
            OwnerPolicyEventType.GRANT ->
                OwnerGrantHistoryState.ACTIVE

            OwnerPolicyEventType.REVOKE ->
                OwnerGrantHistoryState.REVOKED
        }
    }

    private suspend fun loadSnapshot(): OwnerPolicySnapshot {
        val durableHead = repository.headRevision()
        val cached = cachedSnapshot
        if (cached != null && cached.revision == durableHead) return cached

        if (cached == null || cached.revision > durableHead) {
            val report = repository.loadReport()
            check(report.unreadableEntries.isEmpty()) {
                "Owner policy store is unreadable: ${report.unreadableEntries.joinToString(",")}"
            }
            val rebuilt = replay(
                base = OwnerPolicySnapshot(0L, emptyList()),
                events = report.events.sortedBy { it.revision },
                expectedHead = durableHead,
            )
            cachedSnapshot = rebuilt
            return rebuilt
        }

        val tail = repository.loadAfter(cached.revision)
        val updated = replay(
            base = cached,
            events = tail,
            expectedHead = durableHead,
        )
        cachedSnapshot = updated
        return updated
    }

    private fun replay(
        base: OwnerPolicySnapshot,
        events: List<OwnerPolicyEvent>,
        expectedHead: Long,
    ): OwnerPolicySnapshot {
        val active = base.activeGrants.associateByTo(
            linkedMapOf(),
            OwnerPolicyGrant::id,
        )
        var revision = base.revision
        events.sortedBy { it.revision }.forEach { event ->
            require(event.revision == revision + 1L) {
                "Owner policy event revisions must be contiguous"
            }
            when (event.type) {
                OwnerPolicyEventType.GRANT -> {
                    val grant = requireNotNull(event.grant)
                    val existing = active[grant.id]
                    require(existing == null || existing == grant) {
                        "Owner policy grant identity collision"
                    }
                    active[grant.id] = grant
                }
                OwnerPolicyEventType.REVOKE -> {
                    val id = requireNotNull(event.revokedGrantId)
                    require(active.remove(id) != null) {
                        "Owner policy revoked unknown/inactive grant"
                    }
                }
            }
            revision = event.revision
        }
        require(revision == expectedHead) {
            "Owner policy tail does not reach durable head"
        }
        return OwnerPolicySnapshot(
            revision = revision,
            activeGrants = active.values.sortedBy { it.id.value },
        )
    }

    private fun denialReasons(
        snapshot: OwnerPolicySnapshot,
        request: OwnerEffectRequest,
        at: Instant,
    ): List<String> {
        val actor = snapshot.activeGrants.filter { it.actorId == request.actorId }
        if (actor.isEmpty()) return listOf("no-active-grant-for-actor")
        val effect = actor.filter { it.effect == request.effect }
        if (effect.isEmpty()) return listOf("effect-not-granted:${request.effect.name}")
        val scope = effect.filter { it.scope == request.scope }
        if (scope.isEmpty()) return listOf("scope-not-granted:${request.scope}")
        val resource = scope.filter { it.resource.matches(request.resource) }
        if (resource.isEmpty()) return listOf("resource-not-granted:${request.resource}")
        val valid = resource.filter { it.isValidAt(at) }
        if (valid.isEmpty()) return listOf("grant-not-currently-valid")
        val capability = valid.filter { grant ->
            grant.capability?.matches(request.capabilityId, request.providerVersion) ?: true
        }
        if (capability.isEmpty()) return listOf("capability-or-provider-version-not-granted")
        val budget = capability.filter { grant ->
            grant.budgetAccountId == null ||
                (grant.budgetAccountId == request.budgetAccountId && request.budgetReservationId != null)
        }
        if (budget.isEmpty()) return listOf("budget-reservation-required-or-mismatched")
        return listOf("no-owner-policy-grant-matched")
    }

    private fun resourceSpecificity(selector: OwnerResourceSelector): Int = when (selector.type) {
        OwnerResourceSelectorType.EXACT -> 2
        OwnerResourceSelectorType.PREFIX -> 1
        OwnerResourceSelectorType.ANY -> 0
    }

    private companion object {
        const val MAX_CAS_ATTEMPTS = 32
    }
}

// ---- B452 Owner Observation Policy ----

enum class OwnerObservationType {
    NOTIFICATION,
    APP_USAGE,
    APP_CONTENT,
    FILE,
    CALENDAR,
    CONTACT,
    BROWSER,
    LOCATION,
    HEALTH,
    EXTERNAL_SENSOR,
}

@JvmInline
value class OwnerObservationGrantId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid owner observation grant id prefix" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid owner observation grant id digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "owner-observation-grant:"
    }
}

data class OwnerObservationGrant(
    val id: OwnerObservationGrantId,
    val actorId: OwnerActorId,
    val observationType: OwnerObservationType,
    val resource: OwnerResourceSelector,
    val scope: String,
    val sensorId: String? = null,
    val validFrom: Instant,
    val validUntil: Instant? = null,
) {
    init {
        require(scope.isNotBlank()) { "Owner observation scope must not be blank" }
        require(sensorId == null || sensorId.isNotBlank()) {
            "Owner observation sensor id must be null or non-blank"
        }
        require(validUntil == null || validUntil.isAfter(validFrom)) {
            "Owner observation validity window must be increasing"
        }
        require(id == expectedId()) { "Owner observation grant id/content mismatch" }
    }

    fun isValidAt(at: Instant): Boolean =
        !at.isBefore(validFrom) && (validUntil == null || at.isBefore(validUntil))

    fun matchesSensor(requestSensorId: String?): Boolean =
        sensorId == null || sensorId == requestSensorId

    private fun expectedId(): OwnerObservationGrantId = expectedId(
        actorId = actorId,
        observationType = observationType,
        resource = resource,
        scope = scope,
        sensorId = sensorId,
        validFrom = validFrom,
        validUntil = validUntil,
    )

    companion object {
        fun create(
            actorId: OwnerActorId,
            observationType: OwnerObservationType,
            resource: OwnerResourceSelector,
            scope: String,
            sensorId: String? = null,
            validFrom: Instant,
            validUntil: Instant? = null,
        ): OwnerObservationGrant = OwnerObservationGrant(
            id = expectedId(
                actorId,
                observationType,
                resource,
                scope,
                sensorId,
                validFrom,
                validUntil,
            ),
            actorId = actorId,
            observationType = observationType,
            resource = resource,
            scope = scope,
            sensorId = sensorId,
            validFrom = validFrom,
            validUntil = validUntil,
        )

        private fun expectedId(
            actorId: OwnerActorId,
            observationType: OwnerObservationType,
            resource: OwnerResourceSelector,
            scope: String,
            sensorId: String?,
            validFrom: Instant,
            validUntil: Instant?,
        ): OwnerObservationGrantId = OwnerObservationGrantId(
            OwnerObservationGrantId.PREFIX + StableFieldIds.fingerprint(
                "owner-observation-grant/v1",
                actorId.value,
                observationType.name,
                *resource.fingerprintParts().toTypedArray(),
                scope,
                sensorId.orEmpty(),
                validFrom.toString(),
                validUntil?.toString().orEmpty(),
            )
        )
    }
}

enum class OwnerObservationPolicyEventType {
    GRANT,
    REVOKE,
}

data class OwnerObservationPolicyEvent(
    val revision: Long,
    val type: OwnerObservationPolicyEventType,
    val recordedAt: Instant,
    val grant: OwnerObservationGrant? = null,
    val revokedGrantId: OwnerObservationGrantId? = null,
) {
    init {
        require(revision > 0L) { "Owner observation policy revision must be positive" }
        when (type) {
            OwnerObservationPolicyEventType.GRANT ->
                require(grant != null && revokedGrantId == null) {
                    "Owner observation grant event requires exactly one grant"
                }
            OwnerObservationPolicyEventType.REVOKE ->
                require(grant == null && revokedGrantId != null) {
                    "Owner observation revoke event requires exactly one grant id"
                }
        }
    }
}

data class OwnerObservationPolicyRepositoryLoadReport(
    val events: List<OwnerObservationPolicyEvent>,
    val unreadableEntries: List<String> = emptyList(),
) {
    init {
        require(unreadableEntries.none { it.isBlank() })
    }
}

interface OwnerObservationPolicyRepository {
    suspend fun loadReport(): OwnerObservationPolicyRepositoryLoadReport

    suspend fun headRevision(): Long =
        loadReport().events.maxOfOrNull { it.revision } ?: 0L

    suspend fun loadAfter(revisionExclusive: Long): List<OwnerObservationPolicyEvent> {
        require(revisionExclusive >= 0L)
        return loadReport().events
            .filter { it.revision > revisionExclusive }
            .sortedBy { it.revision }
    }

    suspend fun append(
        expectedRevision: Long,
        event: OwnerObservationPolicyEvent,
    ): Boolean
}

data class OwnerObservationPolicySnapshot(
    val revision: Long,
    val activeGrants: List<OwnerObservationGrant>,
) {
    init {
        require(revision >= 0L)
        require(activeGrants.map { it.id }.distinct().size == activeGrants.size)
    }
}

enum class OwnerObservationGrantHistoryState {
    NEVER_SEEN,
    ACTIVE,
    REVOKED,
}

data class OwnerObservationRequest(
    val actorId: OwnerActorId,
    val observationType: OwnerObservationType,
    val resource: String,
    val scope: String,
    val sensorId: String? = null,
) {
    init {
        require(resource.isNotBlank()) { "Owner observation resource must not be blank" }
        require(scope.isNotBlank()) { "Owner observation scope must not be blank" }
        require(sensorId == null || sensorId.isNotBlank())
    }
}

sealed interface OwnerObservationDecision {
    val policyRevision: Long

    data class Allowed(
        override val policyRevision: Long,
        val grantId: OwnerObservationGrantId,
    ) : OwnerObservationDecision

    data class Blocked(
        override val policyRevision: Long,
        val reasons: List<String>,
    ) : OwnerObservationDecision {
        init {
            require(reasons.isNotEmpty())
        }
    }
}

/**
 * B452 read/observation authority ledger.
 *
 * This ledger intentionally cannot authorize effects. Android/platform permission and Owner Effect
 * Policy remain separate authorities.
 */
class OwnerObservationPolicyLedger(
    private val repository: OwnerObservationPolicyRepository,
    private val now: () -> Instant = Instant::now,
) {
    @Volatile
    private var cachedSnapshot: OwnerObservationPolicySnapshot? = null

    suspend fun grant(grant: OwnerObservationGrant): OwnerObservationGrant {
        repeat(MAX_CAS_ATTEMPTS) {
            val snapshot = loadSnapshot()
            snapshot.activeGrants.firstOrNull { it.id == grant.id }?.let { return it }
            val event = OwnerObservationPolicyEvent(
                revision = snapshot.revision + 1L,
                type = OwnerObservationPolicyEventType.GRANT,
                recordedAt = now(),
                grant = grant,
            )
            if (repository.append(snapshot.revision, event)) return grant
        }
        error("Owner observation policy grant CAS retry limit exceeded")
    }

    suspend fun revoke(grantId: OwnerObservationGrantId): OwnerObservationPolicySnapshot {
        repeat(MAX_CAS_ATTEMPTS) {
            val snapshot = loadSnapshot()
            if (snapshot.activeGrants.none { it.id == grantId }) return snapshot
            val event = OwnerObservationPolicyEvent(
                revision = snapshot.revision + 1L,
                type = OwnerObservationPolicyEventType.REVOKE,
                recordedAt = now(),
                revokedGrantId = grantId,
            )
            if (repository.append(snapshot.revision, event)) return loadSnapshot()
        }
        error("Owner observation policy revoke CAS retry limit exceeded")
    }

    suspend fun evaluate(
        request: OwnerObservationRequest,
        at: Instant = now(),
    ): OwnerObservationDecision {
        val snapshot = loadSnapshot()
        val selected = snapshot.activeGrants
            .asSequence()
            .filter { it.actorId == request.actorId }
            .filter { it.observationType == request.observationType }
            .filter { it.scope == request.scope }
            .filter { it.resource.matches(request.resource) }
            .filter { it.matchesSensor(request.sensorId) }
            .filter { it.isValidAt(at) }
            .sortedWith(
                compareByDescending<OwnerObservationGrant> {
                    when (it.resource.type) {
                        OwnerResourceSelectorType.EXACT -> 2
                        OwnerResourceSelectorType.PREFIX -> 1
                        OwnerResourceSelectorType.ANY -> 0
                    }
                }.thenByDescending { it.sensorId != null }
                    .thenBy { it.id.value }
            )
            .firstOrNull()

        return if (selected != null) {
            OwnerObservationDecision.Allowed(
                policyRevision = snapshot.revision,
                grantId = selected.id,
            )
        } else {
            OwnerObservationDecision.Blocked(
                policyRevision = snapshot.revision,
                reasons = denialReasons(snapshot, request, at),
            )
        }
    }

    suspend fun snapshot(): OwnerObservationPolicySnapshot = loadSnapshot()

    suspend fun historyState(
        grantId: OwnerObservationGrantId,
    ): OwnerObservationGrantHistoryState {
        val report = repository.loadReport()
        check(report.unreadableEntries.isEmpty()) {
            "Owner observation policy history is unreadable: " +
                report.unreadableEntries.joinToString(",")
        }
        val latest = report.events
            .asSequence()
            .filter { event ->
                event.grant?.id == grantId || event.revokedGrantId == grantId
            }
            .maxByOrNull { it.revision }
            ?: return OwnerObservationGrantHistoryState.NEVER_SEEN

        return when (latest.type) {
            OwnerObservationPolicyEventType.GRANT ->
                OwnerObservationGrantHistoryState.ACTIVE
            OwnerObservationPolicyEventType.REVOKE ->
                OwnerObservationGrantHistoryState.REVOKED
        }
    }

    private suspend fun loadSnapshot(): OwnerObservationPolicySnapshot {
        val durableHead = repository.headRevision()
        val cached = cachedSnapshot
        if (cached != null && cached.revision == durableHead) return cached

        if (cached == null || cached.revision > durableHead) {
            val report = repository.loadReport()
            check(report.unreadableEntries.isEmpty()) {
                "Owner observation policy store is unreadable: " +
                    report.unreadableEntries.joinToString(",")
            }
            val rebuilt = replay(
                base = OwnerObservationPolicySnapshot(0L, emptyList()),
                events = report.events.sortedBy { it.revision },
                expectedHead = durableHead,
            )
            cachedSnapshot = rebuilt
            return rebuilt
        }

        val updated = replay(
            base = cached,
            events = repository.loadAfter(cached.revision),
            expectedHead = durableHead,
        )
        cachedSnapshot = updated
        return updated
    }

    private fun replay(
        base: OwnerObservationPolicySnapshot,
        events: List<OwnerObservationPolicyEvent>,
        expectedHead: Long,
    ): OwnerObservationPolicySnapshot {
        val active = base.activeGrants.associateByTo(linkedMapOf()) { it.id }
        var revision = base.revision
        events.sortedBy { it.revision }.forEach { event ->
            require(event.revision == revision + 1L) {
                "Owner observation policy event revisions must be contiguous"
            }
            when (event.type) {
                OwnerObservationPolicyEventType.GRANT ->
                    active[requireNotNull(event.grant).id] = event.grant
                OwnerObservationPolicyEventType.REVOKE ->
                    active.remove(requireNotNull(event.revokedGrantId))
            }
            revision = event.revision
        }
        require(revision == expectedHead) {
            "Owner observation policy replay head mismatch"
        }
        return OwnerObservationPolicySnapshot(
            revision = revision,
            activeGrants = active.values.sortedBy { it.id.value },
        )
    }

    private fun denialReasons(
        snapshot: OwnerObservationPolicySnapshot,
        request: OwnerObservationRequest,
        at: Instant,
    ): List<String> {
        val actor = snapshot.activeGrants.filter { it.actorId == request.actorId }
        if (actor.isEmpty()) return listOf("no-active-observation-grant-for-actor")
        val type = actor.filter { it.observationType == request.observationType }
        if (type.isEmpty()) {
            return listOf("observation-type-not-granted:${request.observationType.name}")
        }
        val scope = type.filter { it.scope == request.scope }
        if (scope.isEmpty()) return listOf("observation-scope-not-granted:${request.scope}")
        val resource = scope.filter { it.resource.matches(request.resource) }
        if (resource.isEmpty()) return listOf("observation-resource-not-granted:${request.resource}")
        val sensor = resource.filter { it.matchesSensor(request.sensorId) }
        if (sensor.isEmpty()) return listOf("observation-sensor-not-granted")
        val valid = sensor.filter { it.isValidAt(at) }
        if (valid.isEmpty()) return listOf("observation-grant-not-currently-valid")
        return listOf("no-owner-observation-policy-grant-matched")
    }

    private companion object {
        const val MAX_CAS_ATTEMPTS = 32
    }
}
