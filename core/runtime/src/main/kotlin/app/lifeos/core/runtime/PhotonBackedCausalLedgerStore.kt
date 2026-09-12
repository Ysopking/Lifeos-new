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
 * Durable causal ledger backed by the normal encrypted Photon repository.
 *
 * V2 stores the complete high-resolution causal entry so attraction, branch processing and fan-in
 * remain reconstructible after process death. V1 replay guards remain readable for migration.
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
            val compatible = if (persisted.formatVersion() >= FORMAT_VERSION) {
                recovered == entry
            } else {
                recovered.rootPhotonId == entry.rootPhotonId &&
                    recovered.emittedPhotonIds.toSet() == entry.emittedPhotonIds.toSet()
            }
            require(compatible) {
                "Persisted causal trace conflicts with the new entry: ${entry.traceId}"
            }
            memory.append(entry)
            return@withLock
        }

        // Persistence happens before the process cache: a failed write never becomes a false replay.
        photons.save(entry.toLedgerPhoton())
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
        private const val FORMAT_VERSION = 2
        private const val LEGACY_FORMAT_VERSION = 1

        fun replayGuardPhotonId(traceId: CausalTraceId): PhotonId = PhotonId("causal-${traceId.value}")
    }

    private fun CausalLedgerEntry.toLedgerPhoton(): Photon {
        val content = buildString {
            appendLine("v=$FORMAT_VERSION")
            appendLine("trace=${encode(traceId.value)}")
            appendLine("root=${encode(rootPhotonId.value)}")
            append("payload=${CausalLedgerCodec.encode(this@toLedgerPhoton)}")
        }
        return Photon(
            id = replayGuardPhotonId(traceId),
            revision = 1,
            content = content,
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
            tags = setOf("causal-ledger", "replay-guard", "causal-ledger:v2", "trace:${traceId.value}"),
        )
    }

    private fun Photon.toReplayGuardEntry(expectedTraceId: CausalTraceId): CausalLedgerEntry {
        require(mimeType == MIME_TYPE) { "Unexpected causal replay guard MIME type" }
        val values = parsedValues()
        return when (val version = values["v"]?.singleOrNull()?.toIntOrNull()) {
            FORMAT_VERSION -> {
                val entry = CausalLedgerCodec.decode(values["payload"]?.singleOrNull().orEmpty())
                require(entry.traceId == expectedTraceId) { "Causal trace identity mismatch" }
                val declaredTrace = CausalTraceId(decode(values["trace"]?.singleOrNull().orEmpty()))
                val declaredRoot = PhotonId(decode(values["root"]?.singleOrNull().orEmpty()))
                require(declaredTrace == entry.traceId && declaredRoot == entry.rootPhotonId) {
                    "Causal ledger envelope does not match payload"
                }
                entry
            }
            LEGACY_FORMAT_VERSION -> decodeLegacy(values, expectedTraceId)
            else -> error("Unsupported causal replay guard format: $version")
        }
    }

    private fun Photon.formatVersion(): Int = parsedValues()["v"]?.singleOrNull()?.toIntOrNull() ?: -1

    private fun Photon.parsedValues(): Map<String, List<String>> = content.lineSequence()
        .filter(String::isNotBlank)
        .map { line -> line.substringBefore('=') to line.substringAfter('=', missingDelimiterValue = "") }
        .groupBy({ it.first }, { it.second })

    private fun decodeLegacy(
        values: Map<String, List<String>>,
        expectedTraceId: CausalTraceId,
    ): CausalLedgerEntry {
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
