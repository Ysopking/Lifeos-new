package app.lifeos.core.runtime

import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Durable causal replay guard backed by the normal Photon repository.
 *
 * The full high-resolution ledger remains available to observability stores, while this compact
 * record guarantees the critical restart invariant: a completed trace is not executed again after
 * process death. Because the repository is the existing encrypted Photon store in Android
 * composition, no second persistence universe or additional secret is introduced.
 */
class PhotonBackedCausalLedgerStore(
    private val photons: PhotonRepository,
    private val memory: CausalLedgerStore = InMemoryCausalLedgerStore(),
) : CausalLedgerStore {
    private val mutex = Mutex()

    override suspend fun append(entry: CausalLedgerEntry) = mutex.withLock {
        memory.load(entry.traceId)?.let { existing ->
            require(existing == entry) {
                "Causal trace already exists with different content: ${entry.traceId}"
            }
            return@withLock
        }

        val guardId = replayGuardPhotonId(entry.traceId)
        photons.load(guardId)?.let { persisted ->
            val recovered = persisted.toReplayGuardEntry(entry.traceId)
            require(recovered.rootPhotonId == entry.rootPhotonId &&
                recovered.emittedPhotonIds.toSet() == entry.emittedPhotonIds.toSet()
            ) {
                "Persisted causal trace conflicts with the new entry: ${entry.traceId}"
            }
            memory.append(entry)
            return@withLock
        }

        // Durable state is written before the in-process cache. If persistence fails, this trace
        // remains eligible for retry instead of becoming falsely complete until process death.
        photons.save(entry.toReplayGuardPhoton())
        memory.append(entry)
    }

    override suspend fun load(traceId: CausalTraceId): CausalLedgerEntry? = mutex.withLock {
        memory.load(traceId)?.let { return@withLock it }
        val persisted = photons.load(replayGuardPhotonId(traceId)) ?: return@withLock null
        val recovered = persisted.toReplayGuardEntry(expectedTraceId = traceId)
        memory.append(recovered)
        recovered
    }

    companion object {
        const val MIME_TYPE = "application/vnd.lifeos.causal-replay-guard+text"
        private const val FORMAT_VERSION = 1

        fun replayGuardPhotonId(traceId: CausalTraceId): PhotonId = PhotonId("causal-${traceId.value}")
    }

    private fun CausalLedgerEntry.toReplayGuardPhoton(): Photon {
        val lines = buildList {
            add("v=$FORMAT_VERSION")
            add("trace=${encode(traceId.value)}")
            add("root=${encode(rootPhotonId.value)}")
            emittedPhotonIds.sortedBy { it.value }.forEach { add("out=${encode(it.value)}") }
        }
        return Photon(
            id = replayGuardPhotonId(traceId),
            revision = 1,
            content = lines.joinToString("\n"),
            mimeType = MIME_TYPE,
            phase = PhotonPhase.ARCHIVED,
            semanticMass = 0.0,
            energy = 0.0,
            confidence = 1.0,
            provenance = Provenance(
                source = "causal-ledger",
                actor = "lifeos",
                createdAt = Instant.EPOCH,
                parentIds = setOf(rootPhotonId),
            ),
            tags = setOf("causal-ledger", "replay-guard", "trace:${traceId.value}"),
        )
    }

    private fun Photon.toReplayGuardEntry(expectedTraceId: CausalTraceId): CausalLedgerEntry {
        require(mimeType == MIME_TYPE) { "Unexpected causal replay guard MIME type" }
        val values = content.lineSequence()
            .map { line -> line.substringBefore('=') to line.substringAfter('=', missingDelimiterValue = "") }
            .groupBy({ it.first }, { it.second })
        require(values["v"]?.singleOrNull()?.toIntOrNull() == FORMAT_VERSION) {
            "Unsupported causal replay guard format"
        }
        val traceId = CausalTraceId(decode(values["trace"]?.singleOrNull().orEmpty()))
        require(traceId == expectedTraceId) { "Causal trace identity mismatch" }
        val rootPhotonId = PhotonId(decode(values["root"]?.singleOrNull().orEmpty()))
        val emitted = values["out"].orEmpty().map { PhotonId(decode(it)) }.distinct()
        return CausalLedgerEntry(
            traceId = traceId,
            rootPhotonId = rootPhotonId,
            attraction = emptyList(),
            branches = emptyList(),
            processingRecords = emptyList(),
            integration = null,
            emittedPhotonIds = emitted,
        )
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String {
        require(value.isNotBlank()) { "Missing encoded causal ledger value" }
        return String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }
}
