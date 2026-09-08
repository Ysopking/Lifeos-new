package app.lifeos.core.model.health

import java.time.Instant

@JvmInline
value class ProtectionNodeRef(val value: String) {
    init {
        require(value.isNotBlank()) { "Protection node reference must not be blank" }
        require(value.length <= 128) { "Protection node reference too long" }
    }

    override fun toString(): String = value
}

enum class ProtectionMode {
    NORMAL,
    QUARANTINED,
    SAFE_MODE,
}

enum class ProtectionReasonCode {
    DATA_CORRUPTION,
    INTEGRITY_FAILURE,
    RECOVERY_EXHAUSTED,
    REPEATED_FAILURE,
    MANUAL,
    USER_REQUESTED,
    UNKNOWN,
}

enum class ProtectionActor {
    SYSTEM,
    RECOVERY,
    ESCALATION,
    USER,
}

enum class ProtectionResumePolicy {
    AUTO_AFTER_VERIFICATION,
    USER_AFTER_VERIFICATION,
    MANUAL_LOCKED,
}

data class ProtectionReason(
    val code: ProtectionReasonCode,
    val source: String,
    val message: String,
) {
    init {
        require(source.isNotBlank()) { "Protection reason source must not be blank" }
        require(source.length <= 128) { "Protection reason source too long" }
        require(message.isNotBlank()) { "Protection reason message must not be blank" }
        require(message.length <= 2048) { "Protection reason message too long" }
    }
}

data class RuntimeProtectionState(
    val generation: Long,
    val revision: Long,
    val mode: ProtectionMode,
    val reasons: List<ProtectionReason> = emptyList(),
    val affectedNodes: Set<ProtectionNodeRef> = emptySet(),
    val enteredAt: Instant? = null,
    val lastVerifiedAt: Instant? = null,
    val actor: ProtectionActor = ProtectionActor.SYSTEM,
    val provenance: String = "runtime-protection",
    val resumePolicy: ProtectionResumePolicy = ProtectionResumePolicy.AUTO_AFTER_VERIFICATION,
) {
    init {
        require(generation >= 0) { "Protection generation must not be negative" }
        require(revision >= 0) { "Protection revision must not be negative" }
        require(provenance.isNotBlank()) { "Protection provenance must not be blank" }
        require(provenance.length <= 256) { "Protection provenance too long" }
        require(reasons.size <= MAX_REASONS) { "Too many protection reasons" }
        require(affectedNodes.size <= MAX_AFFECTED_NODES) { "Too many affected protection nodes" }
        require(reasons.distinct() == reasons) { "Protection reasons must not contain duplicates" }

        when (mode) {
            ProtectionMode.NORMAL -> {
                require(reasons.isEmpty()) { "NORMAL protection state cannot retain reasons" }
                require(affectedNodes.isEmpty()) { "NORMAL protection state cannot retain affected nodes" }
                require(enteredAt == null) { "NORMAL protection state cannot retain enteredAt" }
            }
            ProtectionMode.QUARANTINED -> {
                require(reasons.isNotEmpty()) { "QUARANTINED protection state requires a reason" }
                require(affectedNodes.isNotEmpty()) { "QUARANTINED protection state requires affected nodes" }
                require(enteredAt != null) { "QUARANTINED protection state requires enteredAt" }
            }
            ProtectionMode.SAFE_MODE -> {
                require(reasons.isNotEmpty()) { "SAFE_MODE protection state requires a reason" }
                require(enteredAt != null) { "SAFE_MODE protection state requires enteredAt" }
            }
        }

        if (lastVerifiedAt != null && enteredAt != null) {
            require(!lastVerifiedAt.isBefore(enteredAt)) {
                "Protection verification cannot precede protection entry"
            }
        }
    }

    val protected: Boolean get() = mode != ProtectionMode.NORMAL

    fun nextRevision(
        mode: ProtectionMode = this.mode,
        reasons: List<ProtectionReason> = this.reasons,
        affectedNodes: Set<ProtectionNodeRef> = this.affectedNodes,
        enteredAt: Instant? = this.enteredAt,
        lastVerifiedAt: Instant? = this.lastVerifiedAt,
        actor: ProtectionActor = this.actor,
        provenance: String = this.provenance,
        resumePolicy: ProtectionResumePolicy = this.resumePolicy,
        advanceGeneration: Boolean = false,
    ): RuntimeProtectionState = RuntimeProtectionState(
        generation = generation + if (advanceGeneration) 1 else 0,
        revision = Math.addExact(revision, 1),
        mode = mode,
        reasons = reasons,
        affectedNodes = affectedNodes,
        enteredAt = enteredAt,
        lastVerifiedAt = lastVerifiedAt,
        actor = actor,
        provenance = provenance,
        resumePolicy = resumePolicy,
    )

    companion object {
        const val MAX_REASONS = 64
        const val MAX_AFFECTED_NODES = 512

        fun normal(
            generation: Long = 0,
            revision: Long = 0,
            actor: ProtectionActor = ProtectionActor.SYSTEM,
            provenance: String = "runtime-protection",
            lastVerifiedAt: Instant? = null,
        ): RuntimeProtectionState = RuntimeProtectionState(
            generation = generation,
            revision = revision,
            mode = ProtectionMode.NORMAL,
            actor = actor,
            provenance = provenance,
            lastVerifiedAt = lastVerifiedAt,
        )
    }
}
