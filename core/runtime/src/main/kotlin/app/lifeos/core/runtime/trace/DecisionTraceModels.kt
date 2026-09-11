package app.lifeos.core.runtime.trace

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** Exact, case-sensitive and structurally unambiguous hashing for durable trace identity. */
internal object DecisionTraceFingerprints {
    fun sha256(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { raw ->
            val bytes = raw.toByteArray(StandardCharsets.UTF_8)
            val length = bytes.size
            digest.update(
                byteArrayOf(
                    ((length ushr 24) and 0xff).toByte(),
                    ((length ushr 16) and 0xff).toByte(),
                    ((length ushr 8) and 0xff).toByte(),
                    (length and 0xff).toByte(),
                )
            )
            digest.update(bytes)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

@JvmInline
value class DecisionTraceId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid decision trace id prefix" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) { "Invalid decision trace id digest" }
    }
    override fun toString(): String = value
    companion object {
        const val PREFIX = "decision-trace:"
        fun create(rootType: String, rootId: String): DecisionTraceId {
            require(rootType.isNotBlank())
            require(rootId.isNotBlank())
            return DecisionTraceId(
                PREFIX + DecisionTraceFingerprints.sha256("decision-trace/v1", rootType, rootId)
            )
        }
    }
}

@JvmInline
value class DecisionTraceNodeId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }
    override fun toString(): String = value
    companion object { const val PREFIX = "decision-trace-node:" }
}

enum class DecisionTraceNodeType {
    OBSERVED_FACT, POLICY_CONSTRAINT, RESOURCE_CONSTRAINT, INFERRED_HYPOTHESIS,
    UNRESOLVED_UNCERTAINTY, CANDIDATE_ALTERNATIVE, REJECTION, SELECTION,
    EXECUTION_OUTCOME, RECOVERY_OUTCOME,
}

enum class DecisionTraceLinkType {
    SUPPORTS, CONSTRAINS, DERIVED_FROM, ALTERNATIVE_TO, REJECTED_BY, SELECTED_BY, PRODUCED, RECOVERED_BY,
}

data class DecisionTraceNode(
    val id: DecisionTraceNodeId,
    val type: DecisionTraceNodeType,
    val sourceType: String,
    val sourceId: String,
    val sourceRevision: Long,
    val reasonCodes: List<String> = emptyList(),
    val displayLabel: String? = null,
    val recordedAt: Instant,
) {
    init {
        require(sourceType.isNotBlank())
        require(sourceId.isNotBlank())
        require(sourceRevision >= 0L)
        require(reasonCodes.none { it.isBlank() })
        require(reasonCodes == reasonCodes.distinct().sorted())
        require(displayLabel == null || displayLabel.isNotBlank())
        require(displayLabel == null || displayLabel.length <= 160)
        require(id == expectedId())
    }

    private fun expectedId() = createId(type, sourceType, sourceId, sourceRevision, reasonCodes)

    companion object {
        fun create(
            type: DecisionTraceNodeType,
            sourceType: String,
            sourceId: String,
            sourceRevision: Long,
            reasonCodes: List<String> = emptyList(),
            displayLabel: String? = null,
            recordedAt: Instant,
        ): DecisionTraceNode {
            val normalized = reasonCodes.distinct().sorted()
            return DecisionTraceNode(
                createId(type, sourceType, sourceId, sourceRevision, normalized),
                type, sourceType, sourceId, sourceRevision, normalized, displayLabel, recordedAt,
            )
        }

        private fun createId(
            type: DecisionTraceNodeType,
            sourceType: String,
            sourceId: String,
            sourceRevision: Long,
            reasonCodes: List<String>,
        ) = DecisionTraceNodeId(
            DecisionTraceNodeId.PREFIX + DecisionTraceFingerprints.sha256(
                "decision-trace-node/v1", type.name, sourceType, sourceId, sourceRevision.toString(),
                *reasonCodes.toTypedArray(),
            )
        )
    }
}

data class DecisionTraceLink(
    val from: DecisionTraceNodeId,
    val to: DecisionTraceNodeId,
    val type: DecisionTraceLinkType,
) { init { require(from != to) } }

data class DecisionTrace(
    val id: DecisionTraceId,
    val revision: Long,
    val nodes: List<DecisionTraceNode>,
    val links: List<DecisionTraceLink>,
) {
    init {
        require(revision >= 0L)
        require(nodes.map { it.id }.distinct().size == nodes.size)
        val ids = nodes.mapTo(mutableSetOf()) { it.id }
        require(links.all { it.from in ids && it.to in ids })
        require(links.distinct().size == links.size)
    }
}
