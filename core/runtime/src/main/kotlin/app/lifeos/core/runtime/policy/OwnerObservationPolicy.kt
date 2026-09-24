package app.lifeos.core.runtime.policy

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

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
