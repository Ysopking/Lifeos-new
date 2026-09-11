package app.lifeos.core.runtime.resource

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

@JvmInline
value class ResourceBudgetAccountId(val value: String) {
    init { require(value.isNotBlank()) { "Resource budget account id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class ResourceBudgetReservationId(val value: String) {
    init { require(value.startsWith(PREFIX)) { "Invalid resource budget reservation id" } }
    override fun toString(): String = value

    companion object {
        const val PREFIX = "resource-reservation:"

        fun create(accountId: ResourceBudgetAccountId, idempotencyKey: String): ResourceBudgetReservationId =
            ResourceBudgetReservationId(
                PREFIX + StableFieldIds.fingerprint(
                    "resource-budget-reservation/v1",
                    accountId.value,
                    idempotencyKey,
                )
            )
    }
}

data class ResourceBudgetQuota(
    val elapsedMillis: Long = 0L,
    val workUnits: Long = 0L,
    val memoryBytes: Long = 0L,
    val ioBytes: Long = 0L,
    val networkBytes: Long = 0L,
    val candidates: Long = 0L,
) {
    init {
        require(elapsedMillis >= 0L)
        require(workUnits >= 0L)
        require(memoryBytes >= 0L)
        require(ioBytes >= 0L)
        require(networkBytes >= 0L)
        require(candidates >= 0L)
    }

    fun allows(usage: ResourceBudgetUsage): Boolean =
        usage.elapsedMillis <= elapsedMillis &&
            usage.workUnits <= workUnits &&
            usage.memoryBytes <= memoryBytes &&
            usage.ioBytes <= ioBytes &&
            usage.networkBytes <= networkBytes &&
            usage.candidates <= candidates
}

data class ResourceBudgetUsage(
    val elapsedMillis: Long = 0L,
    val workUnits: Long = 0L,
    val memoryBytes: Long = 0L,
    val ioBytes: Long = 0L,
    val networkBytes: Long = 0L,
    val candidates: Long = 0L,
) {
    init {
        require(elapsedMillis >= 0L)
        require(workUnits >= 0L)
        require(memoryBytes >= 0L)
        require(ioBytes >= 0L)
        require(networkBytes >= 0L)
        require(candidates >= 0L)
    }

    operator fun plus(other: ResourceBudgetUsage): ResourceBudgetUsage = ResourceBudgetUsage(
        elapsedMillis = Math.addExact(elapsedMillis, other.elapsedMillis),
        workUnits = Math.addExact(workUnits, other.workUnits),
        memoryBytes = Math.addExact(memoryBytes, other.memoryBytes),
        ioBytes = Math.addExact(ioBytes, other.ioBytes),
        networkBytes = Math.addExact(networkBytes, other.networkBytes),
        candidates = Math.addExact(candidates, other.candidates),
    )

    fun isWithin(quota: ResourceBudgetQuota): Boolean = quota.allows(this)

    fun isWithin(other: ResourceBudgetUsage): Boolean =
        elapsedMillis <= other.elapsedMillis &&
            workUnits <= other.workUnits &&
            memoryBytes <= other.memoryBytes &&
            ioBytes <= other.ioBytes &&
            networkBytes <= other.networkBytes &&
            candidates <= other.candidates

    fun isZero(): Boolean =
        elapsedMillis == 0L && workUnits == 0L && memoryBytes == 0L && ioBytes == 0L &&
            networkBytes == 0L && candidates == 0L
}

enum class ResourceBudgetReservationState {
    RESERVED,
    COMMITTED,
    RELEASED,
}

data class ResourceBudgetReservation(
    val id: ResourceBudgetReservationId,
    val accountId: ResourceBudgetAccountId,
    val idempotencyKey: String,
    val reserved: ResourceBudgetUsage,
    val state: ResourceBudgetReservationState,
    val createdAt: Instant,
    val settledUsage: ResourceBudgetUsage? = null,
    val settledAt: Instant? = null,
) {
    init {
        require(idempotencyKey.isNotBlank())
        require(id == ResourceBudgetReservationId.create(accountId, idempotencyKey)) {
            "Resource budget reservation id/content mismatch"
        }
        require(!reserved.isZero()) { "Resource budget reservation must reserve non-zero usage" }
        when (state) {
            ResourceBudgetReservationState.RESERVED -> {
                require(settledUsage == null && settledAt == null)
            }
            ResourceBudgetReservationState.COMMITTED -> {
                require(settledUsage != null && settledAt != null)
                require(settledUsage.isWithin(reserved))
            }
            ResourceBudgetReservationState.RELEASED -> {
                require(settledUsage != null && settledAt != null)
                require(settledUsage.isZero())
            }
        }
    }

    companion object {
        fun create(
            accountId: ResourceBudgetAccountId,
            idempotencyKey: String,
            usage: ResourceBudgetUsage,
            createdAt: Instant,
        ): ResourceBudgetReservation = ResourceBudgetReservation(
            id = ResourceBudgetReservationId.create(accountId, idempotencyKey),
            accountId = accountId,
            idempotencyKey = idempotencyKey,
            reserved = usage,
            state = ResourceBudgetReservationState.RESERVED,
            createdAt = createdAt,
        )
    }
}

data class ResourceBudgetAccount(
    val id: ResourceBudgetAccountId,
    val revision: Long,
    val quota: ResourceBudgetQuota,
    val consumed: ResourceBudgetUsage = ResourceBudgetUsage(),
    val reservations: List<ResourceBudgetReservation> = emptyList(),
) {
    init {
        require(revision > 0L) { "Resource budget revision must be positive" }
        require(quota.allows(consumed)) { "Consumed usage exceeds resource budget quota" }
        require(reservations.map { it.id }.distinct().size == reservations.size) {
            "Resource budget reservation ids must be unique"
        }
        require(reservations.map { it.idempotencyKey }.distinct().size == reservations.size) {
            "Resource budget reservation idempotency keys must be unique"
        }
        require(reservations.all { it.accountId == id }) {
            "Resource budget reservation belongs to another account"
        }
        require(quota.allows(consumed + heldUsage())) {
            "Consumed plus reserved usage exceeds resource budget quota"
        }
    }

    fun heldUsage(): ResourceBudgetUsage = reservations
        .filter { it.state == ResourceBudgetReservationState.RESERVED }
        .fold(ResourceBudgetUsage(), ResourceBudgetUsage::plus)
}

data class ResourceBudgetRepositoryLoadReport(
    val account: ResourceBudgetAccount?,
    val unreadableEntries: List<String> = emptyList(),
) {
    init { require(unreadableEntries.none { it.isBlank() }) }
}

/**
 * Durable implementations must make compareAndSet atomic. A process restart may reconstruct a
 * coordinator, but it must not reset account revision, consumption or reservation identity.
 */
interface ResourceBudgetRepository {
    suspend fun load(accountId: ResourceBudgetAccountId): ResourceBudgetRepositoryLoadReport
    suspend fun create(account: ResourceBudgetAccount): Boolean
    suspend fun compareAndSet(
        accountId: ResourceBudgetAccountId,
        expectedRevision: Long,
        updated: ResourceBudgetAccount,
    ): Boolean
}

sealed interface ResourceBudgetReservationResult {
    data class Reserved(val reservation: ResourceBudgetReservation) : ResourceBudgetReservationResult
    data class Existing(val reservation: ResourceBudgetReservation) : ResourceBudgetReservationResult
    data class Denied(val accountId: ResourceBudgetAccountId, val reason: String) : ResourceBudgetReservationResult
}

class ResourceBudgetCoordinator(
    private val repository: ResourceBudgetRepository,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun createAccount(
        accountId: ResourceBudgetAccountId,
        quota: ResourceBudgetQuota,
    ): ResourceBudgetAccount {
        val account = ResourceBudgetAccount(id = accountId, revision = 1L, quota = quota)
        if (repository.create(account)) return account
        return requireAccount(accountId).also {
            require(it.quota == quota) { "Resource budget account already exists with another quota" }
        }
    }

    suspend fun reserve(
        accountId: ResourceBudgetAccountId,
        idempotencyKey: String,
        usage: ResourceBudgetUsage,
    ): ResourceBudgetReservationResult {
        require(idempotencyKey.isNotBlank())
        require(!usage.isZero()) { "Resource budget reservation must reserve non-zero usage" }
        repeat(MAX_CAS_ATTEMPTS) {
            val account = requireAccount(accountId)
            account.reservations.firstOrNull { it.idempotencyKey == idempotencyKey }?.let { existing ->
                require(existing.reserved == usage) { "Budget idempotency key reused with different usage" }
                return ResourceBudgetReservationResult.Existing(existing)
            }
            if (!account.quota.allows(account.consumed + account.heldUsage() + usage)) {
                return ResourceBudgetReservationResult.Denied(
                    accountId = accountId,
                    reason = "resource-budget-exhausted",
                )
            }
            val reservation = ResourceBudgetReservation.create(
                accountId = accountId,
                idempotencyKey = idempotencyKey,
                usage = usage,
                createdAt = now(),
            )
            val updated = account.copy(
                revision = account.revision + 1L,
                reservations = account.reservations + reservation,
            )
            if (repository.compareAndSet(accountId, account.revision, updated)) {
                return ResourceBudgetReservationResult.Reserved(reservation)
            }
        }
        error("Resource budget reservation CAS retry limit exceeded")
    }

    suspend fun commit(
        accountId: ResourceBudgetAccountId,
        reservationId: ResourceBudgetReservationId,
        actualUsage: ResourceBudgetUsage,
    ): ResourceBudgetReservation {
        repeat(MAX_CAS_ATTEMPTS) {
            val account = requireAccount(accountId)
            val index = account.reservations.indexOfFirst { it.id == reservationId }
            require(index >= 0) { "Unknown resource budget reservation" }
            val current = account.reservations[index]
            when (current.state) {
                ResourceBudgetReservationState.COMMITTED -> {
                    require(current.settledUsage == actualUsage) {
                        "Committed reservation replay changed actual usage"
                    }
                    return current
                }
                ResourceBudgetReservationState.RELEASED -> error("Released reservation cannot be committed")
                ResourceBudgetReservationState.RESERVED -> Unit
            }
            require(actualUsage.isWithin(current.reserved)) { "Actual usage exceeds reserved budget" }
            val settled = current.copy(
                state = ResourceBudgetReservationState.COMMITTED,
                settledUsage = actualUsage,
                settledAt = now(),
            )
            val updatedReservations = account.reservations.toMutableList().also { it[index] = settled }
            val updated = account.copy(
                revision = account.revision + 1L,
                consumed = account.consumed + actualUsage,
                reservations = updatedReservations,
            )
            if (repository.compareAndSet(accountId, account.revision, updated)) return settled
        }
        error("Resource budget commit CAS retry limit exceeded")
    }

    suspend fun release(
        accountId: ResourceBudgetAccountId,
        reservationId: ResourceBudgetReservationId,
    ): ResourceBudgetReservation {
        repeat(MAX_CAS_ATTEMPTS) {
            val account = requireAccount(accountId)
            val index = account.reservations.indexOfFirst { it.id == reservationId }
            require(index >= 0) { "Unknown resource budget reservation" }
            val current = account.reservations[index]
            when (current.state) {
                ResourceBudgetReservationState.RELEASED -> return current
                ResourceBudgetReservationState.COMMITTED -> error("Committed reservation cannot be released")
                ResourceBudgetReservationState.RESERVED -> Unit
            }
            val settled = current.copy(
                state = ResourceBudgetReservationState.RELEASED,
                settledUsage = ResourceBudgetUsage(),
                settledAt = now(),
            )
            val updatedReservations = account.reservations.toMutableList().also { it[index] = settled }
            val updated = account.copy(
                revision = account.revision + 1L,
                reservations = updatedReservations,
            )
            if (repository.compareAndSet(accountId, account.revision, updated)) return settled
        }
        error("Resource budget release CAS retry limit exceeded")
    }

    suspend fun current(accountId: ResourceBudgetAccountId): ResourceBudgetAccount = requireAccount(accountId)

    /** Missing is a valid lookup result; unreadable durable state still fails closed. */
    suspend fun currentOrNull(accountId: ResourceBudgetAccountId): ResourceBudgetAccount? {
        val report = repository.load(accountId)
        check(report.unreadableEntries.isEmpty()) {
            "Resource budget store is unreadable: ${report.unreadableEntries.joinToString(",")}" 
        }
        return report.account
    }

    private suspend fun requireAccount(accountId: ResourceBudgetAccountId): ResourceBudgetAccount =
        requireNotNull(currentOrNull(accountId)) { "Unknown resource budget account: $accountId" }

    private companion object {
        const val MAX_CAS_ATTEMPTS = 32
    }
}
