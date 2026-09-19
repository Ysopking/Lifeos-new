package app.lifeos.core.runtime.scale

import app.lifeos.core.model.PhotonId
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class ScaleFixtureTier {
    CI,
    PROFILE,
}

data class ScaleCorpusConfig(
    val seed: String,
    val photonCount: Int,
    val photonRevisionDepth: Int,
    val relationshipCount: Int,
    val traceNodeCount: Int,
    val traceCount: Int,
    val ledgerEventCount: Int,
    val ledgerKeyCount: Int,
    val uiItemCount: Int,
) {
    init {
        require(seed.isNotBlank())
        require(photonCount in 1..MAX_ITEMS)
        require(photonRevisionDepth in 1..32)
        require(relationshipCount in 1..MAX_RELATIONSHIPS)
        require(traceNodeCount in 1..MAX_ITEMS)
        require(traceCount in 1..traceNodeCount)
        require(ledgerEventCount in 1..MAX_ITEMS)
        require(ledgerKeyCount in 1..ledgerEventCount)
        require(uiItemCount in 1..MAX_ITEMS)
    }

    companion object {
        const val MAX_ITEMS = 500_000
        const val MAX_RELATIONSHIPS = 1_000_000

        fun forTier(tier: ScaleFixtureTier): ScaleCorpusConfig = when (tier) {
            ScaleFixtureTier.CI -> ScaleCorpusConfig(
                seed = "lifeos-m216-ci-v1",
                photonCount = 10_000,
                photonRevisionDepth = 3,
                relationshipCount = 25_000,
                traceNodeCount = 10_000,
                traceCount = 128,
                ledgerEventCount = 10_000,
                ledgerKeyCount = 64,
                uiItemCount = 20_512,
            )
            ScaleFixtureTier.PROFILE -> ScaleCorpusConfig(
                seed = "lifeos-m216-profile-v1",
                photonCount = 100_000,
                photonRevisionDepth = 5,
                relationshipCount = 250_000,
                traceNodeCount = 100_000,
                traceCount = 1_024,
                ledgerEventCount = 100_000,
                ledgerKeyCount = 256,
                uiItemCount = 100_000,
            )
        }
    }
}

data class ScaleRelationshipFixture(
    val ordinal: Int,
    val source: PhotonId,
    val target: PhotonId,
    val kind: String,
)

data class ScaleTraceNodeFixture(
    val ordinal: Int,
    val traceKey: String,
    val nodeKey: String,
)

data class ScaleLedgerEventFixture(
    val globalRevision: Long,
    val key: String,
    val perKeyRevision: Long,
)

data class ScaleCorpusDescriptor(
    val photonCount: Int,
    val photonRevisionCount: Long,
    val relationshipCount: Int,
    val traceNodeCount: Int,
    val traceCount: Int,
    val ledgerEventCount: Int,
    val ledgerKeyCount: Int,
    val uiItemCount: Int,
    val fingerprint: String,
)

/**
 * Deterministic, local-only scale corpus. Sequences are generated on demand so fixture generation
 * itself does not create the kind of accidental full-list retention M213/M216 are intended to catch.
 */
class DeterministicScaleCorpus(
    val config: ScaleCorpusConfig,
) {
    fun photonIds(): Sequence<PhotonId> = sequence {
        repeat(config.photonCount) { index ->
            yield(PhotonId("scale-photon-" + stableId("photon", index)))
        }
    }

    fun relationshipEdges(): Sequence<ScaleRelationshipFixture> = sequence {
        repeat(config.relationshipCount) { index ->
            val sourceIndex = index % config.photonCount
            var targetIndex = ((index.toLong() * 37L + 17L) % config.photonCount).toInt()
            if (targetIndex == sourceIndex) {
                targetIndex = (targetIndex + 1) % config.photonCount
            }
            yield(
                ScaleRelationshipFixture(
                    ordinal = index,
                    source = photonId(sourceIndex),
                    target = photonId(targetIndex),
                    kind = RELATION_KINDS[index % RELATION_KINDS.size],
                )
            )
        }
    }

    fun traceNodes(): Sequence<ScaleTraceNodeFixture> = sequence {
        repeat(config.traceNodeCount) { index ->
            val trace = index % config.traceCount
            yield(
                ScaleTraceNodeFixture(
                    ordinal = index,
                    traceKey = "scale-trace-" + stableId("trace", trace),
                    nodeKey = "scale-node-" + stableId("trace-node", index),
                )
            )
        }
    }

    fun ledgerEvents(): Sequence<ScaleLedgerEventFixture> = sequence {
        val perKeyCounts = LongArray(config.ledgerKeyCount)
        repeat(config.ledgerEventCount) { index ->
            val keyIndex = index % config.ledgerKeyCount
            perKeyCounts[keyIndex] += 1L
            yield(
                ScaleLedgerEventFixture(
                    globalRevision = index.toLong() + 1L,
                    key = "scale-ledger-key-" + stableId("ledger-key", keyIndex),
                    perKeyRevision = perKeyCounts[keyIndex],
                )
            )
        }
    }

    fun uiItemIds(): Sequence<String> = sequence {
        repeat(config.uiItemCount) { index ->
            yield("scale-ui-" + stableId("ui", index))
        }
    }

    fun descriptor(): ScaleCorpusDescriptor {
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, "lifeos-scale-corpus/v1")
        update(digest, config.seed)
        update(digest, config.photonCount.toString())
        update(digest, config.photonRevisionDepth.toString())
        photonIds().forEach { update(digest, it.value) }
        relationshipEdges().forEach { edge ->
            update(digest, edge.ordinal.toString())
            update(digest, edge.source.value)
            update(digest, edge.target.value)
            update(digest, edge.kind)
        }
        traceNodes().forEach { node ->
            update(digest, node.ordinal.toString())
            update(digest, node.traceKey)
            update(digest, node.nodeKey)
        }
        ledgerEvents().forEach { event ->
            update(digest, event.globalRevision.toString())
            update(digest, event.key)
            update(digest, event.perKeyRevision.toString())
        }
        uiItemIds().forEach { update(digest, it) }

        return ScaleCorpusDescriptor(
            photonCount = config.photonCount,
            photonRevisionCount = config.photonCount.toLong() * config.photonRevisionDepth.toLong(),
            relationshipCount = config.relationshipCount,
            traceNodeCount = config.traceNodeCount,
            traceCount = config.traceCount,
            ledgerEventCount = config.ledgerEventCount,
            ledgerKeyCount = config.ledgerKeyCount,
            uiItemCount = config.uiItemCount,
            fingerprint = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
        )
    }

    private fun photonId(index: Int): PhotonId =
        PhotonId("scale-photon-" + stableId("photon", index))

    private fun stableId(domain: String, index: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, "lifeos-scale-id/v1")
        update(digest, config.seed)
        update(digest, domain)
        update(digest, index.toString())
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun update(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private companion object {
        val RELATION_KINDS = listOf(
            "same-project",
            "revision-of",
            "supports",
            "depends-on",
            "derived-from",
        )
    }
}
