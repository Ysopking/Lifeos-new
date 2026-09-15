package app.lifeos.core.model

@JvmInline
value class CognitiveEventId(val value: String) {
    init { require(value.isNotBlank()) { "Cognitive event id must not be blank" } }
}

enum class CognitiveEventKind {
    PROCESSING,
    OUTCOME,
    MEMORY_UPDATE,
    ARTIFACT,
    GOAL_CHANGE,
    RECOVERY,
    WORLD_DELTA,
    TRANSACTION,
    SOURCE_DELTA,
    LIFE_STATE_CHANGE,
    ATTENTION_CHANGE,
    PLAN_CHANGE,
    CAPABILITY_CHANGE,
}

/** Immutable event envelope. Payload remains canonical bytes/text owned by the event-specific codec. */
data class CognitiveEvent(
    val eventId: CognitiveEventId,
    val schemaVersion: Int,
    val kind: CognitiveEventKind,
    val transactionId: CognitiveTransactionId?,
    val traceId: CausalTraceId,
    val sequence: Long,
    val payloadFingerprint: String,
) {
    init {
        require(schemaVersion > 0) { "Event schema version must be positive" }
        require(sequence > 0) { "Event sequence must be positive" }
        require(payloadFingerprint.isNotBlank()) { "Event payload fingerprint must not be blank" }
    }

    val stableFingerprint: String
        get() = StableCognitiveIds.fingerprint(
            eventId.value,
            schemaVersion.toString(),
            kind.name,
            transactionId?.value.orEmpty(),
            traceId.value,
            sequence.toString(),
            payloadFingerprint,
        )
}

/** Append-only boundary; projections consume events without mutating historical events. */
interface CognitiveEventStore {
    suspend fun append(event: CognitiveEvent)
    suspend fun eventsAfter(sequenceExclusive: Long): List<CognitiveEvent>
}
