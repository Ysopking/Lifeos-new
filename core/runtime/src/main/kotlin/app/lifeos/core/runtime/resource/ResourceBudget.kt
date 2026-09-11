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
    init {
        require(value.startsWith(PREFIX)) { "Invalid resource budget reservation id prefix" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid resource budget reservation id digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "resource-budget-reservation:"
    }
}

/**
 * Shared V16 quota dimensions. Every account carries an explicit limit for every dimension so a
 * newly introduced kind of work cannot silently become unbounded.
 */
data class ResourceBudgetQuota(
    val elapsedMillis: Long,
    val workUnits: Long,
    val memoryBytes: Long,
    val ioBytes: Long,
    val networkBytes: Long,
    val candidates: Long,
) {
    init {
        require(values().all { it >= 0L }) { "Resource budget quota values must be non-negative" }
    }

    internal fun allows(usage: ResourceBudgetUsage): Boolean =
        usage.elapsedMillis <= elapsedMillis &&
            usage.workUnits <= workUnits &&
            usage.memoryBytes <= memoryBytes &&
            usage.ioBytes <= ioBytes &&
            usage.networkBytes <= networkBytes &&
            usage.candidates <= candidates

    private fun values(): List<Long> = listOf(
        elapsedMillis,
        workUnits,
        memoryBytes,
        ioBytes,
        networkBytes,
        candidates,
    )
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
        require(values().all { it >= 0L }) { "Resource budget usage values must be non-negative" }
    }

    operator fun plus(other: ResourceBudgetUsage): ResourceBudgetUsage = ResourceBudgetUsage(
        elapsedMillis = Math.addExact(elapsedMillis, other.elapsedMillis),
        workUnits = Math.addExact(workUnits, other.workUnits),
        memoryBytes = Math.addExact(memoryBytes, other.memoryBytes),
        ioBytes = Math.addExact(ioBytes, other.ioBytes),
        networkBytes = Math.addExact(networkBytes, other.networkBytes),
        candidates = Math.addExact(candidates, other.candidates),
    )

    fun isWithin(reserved: ResourceBudgetUsage): Boolean =
        elapsedMillis <= reserved.elapsedMillis &&
            workUnits <= reserved.workUnits &&
            memoryBytes <= reserved.memoryBytes &&
            ioBytes <= reserved.ioBytes &&
            networkBytes <= reserved.networkBytes &&
            candidates <= reserved.candidates

    fun isZero(): Boolean = values().all { it == 0L }

    private fun values(): List<Long> = listOf(
        elapsedMillis,
        workUnits,
        memoryBytes,
        ioBytes,
        networkBytes,
        candidates,
    )
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
    val createdAt: Instant,
    val state: ResourceBudgetReservationState = ResourceBudgetReservationState.RESERVED,
    val settledUsage: ResourceBudgetUsage? = null,
    val settledAt: Instant? = null,
) {
    init {
        require(idempotencyKey.isNotBlank()) { "Budget reservation idempotency key must not be blank" }
        when (state) {
            ResourceBudgetReservationState.RESERVED -> {
                require(settledUsage == null && settledAt == null) {
                    "Open budget reservation cannot contain settlement data"
                }
            }
            ResourceBudgetReservationState.COMMITTED -> {
                requireNotNull(settledUsage) { "Committed budget reservation requires settled usage" }
                requireNotNull(settledAt) { "Committed budget reservation requires settlement time" }
                require(settledUsage.isWithin(reserved)) { "Committed usage exceeds reserved budget" }
            }
            ResourceBudgetReservationState.RELEASED -> {
                require(settledUsage == ResourceBudgetUsage()) {
                    "Released budget reservation must settle to zero usage"
                }
                requireNotNull(settledAt) { "Released budget reservation requires settlement time" }
            }
        }
        require(id == expectedId(accountId, idempotencyKey)) { "Budget reservation id/content mismatch" }
    }

    companion object {
        fun create(
            accountId: ResourceBudgetAccountId,
            idempotencyKey: String,
            usage: ResourceBudgetUsage,
            createdAt: Instant,
        ): ResourceBudgetReservation {
            require(idempotencyKey.isNotBlank())
            return ResourceBudgetReservation(
                id = expectedId(accountId, idempotencyKey),
                accountId = accountId,
                idempotencyKey = idempotencyKey,
                reserved = usage,
                createdAt = createdAt,
            )
        }

        private fun expectedId(
            accountId: ResourceBudgetAccountId,
            idempotencyKey: String,
        ): ResourceBudgetReservationId = ResourceBudgetReservationId(
            ResourceBudgetReservationId.PREFIX + StableFieldIds.fingerprint(
                "resource-budget-reservation/v1",
                accountId.value,
                idempotencyKey,
            )
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
        val held = reservations
            .filter { it.state == ResourceBudgetReservationState.RESERVED }
            .fold(ResourceBudgetUsage(), ResourceBudgetUsage::plus)
        require(quota.allows(consumed + held)) {
            "Consumed plus reserved usage exceeds resource budget quota"
        }
    }
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

    private suspend fun requireAccount(accountId: ResourceBudgetAccountId): ResourceBudgetAccount {
        val report = repository.load(accountId)
        check(report.unreadableEntries.isEmpty()) {
            "Resource budget store is unreadable: ${report.unreadableEntries.joinToString(",")}" 
        }
        return requireNotNull(report.account) { "Unknown resource budget account: $accountId" }
    }

    private companion object {
        const val MAX_CAS_ATTEMPTS = 32
    }
}
