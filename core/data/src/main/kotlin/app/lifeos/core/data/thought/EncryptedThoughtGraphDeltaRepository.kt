package app.lifeos.core.data.thought

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.runtime.thought.ThoughtGraphCompactionPolicy
import app.lifeos.core.runtime.thought.ThoughtGraphCompactionReport
import app.lifeos.core.runtime.thought.ThoughtGraphDelta
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaCodec
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaId
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaLoadReport
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaRepository
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaSegment
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaSegmentCodec
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaSegmentId
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaWriteResult
import app.lifeos.core.runtime.thought.ThoughtGraphHistoryCompactor
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Encrypted immutable V3 ThoughtGraph history.
 *
 * Backward-compatible loose `.tgdelta` files remain readable. Compaction writes immutable encrypted
 * content-addressed segments first, reads them back for exact verification, and only then removes
 * redundant loose files. `loadReport` always reads the union of loose files and segments and rejects
 * any same-id content disagreement, so a process kill at any compaction point cannot lose provenance.
 */
class EncryptedThoughtGraphDeltaRepository(context: Context) :
    ThoughtGraphDeltaRepository,
    ThoughtGraphHistoryCompactor {
    private val directory = context.filesDir.resolve("thought-graph-delta-vault")
    private val deltaKey: SecretKey by lazy { loadOrCreateKey(DELTA_KEY_ALIAS) }
    private val segmentKey: SecretKey by lazy { loadOrCreateKey(SEGMENT_KEY_ALIAS) }
    private val mutex = Mutex()

    override suspend fun save(delta: ThoughtGraphDelta): ThoughtGraphDeltaWriteResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectory()
                findExistingInternal(delta.id)?.let { existing ->
                    require(sameIdentityContent(existing, delta)) {
                        "Thought graph delta identity collision"
                    }
                    return@withLock ThoughtGraphDeltaWriteResult.Duplicate(existing)
                }

                val name = deltaFileName(delta.id)
                val plaintext = ThoughtGraphDeltaCodec.encode(delta)
                val encrypted = ThoughtGraphDeltaVaultCodec.encrypt(plaintext, deltaKey)
                writeAtomic(name, encrypted)
                ThoughtGraphDeltaWriteResult.Stored(delta)
            }
        }

    override suspend fun load(id: ThoughtGraphDeltaId): ThoughtGraphDelta? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectory()
                findExistingInternal(id)?.also { delta ->
                    require(delta.id == id) { "Thought graph delta identity mismatch" }
                }
            }
        }

    override suspend fun loadReport(): ThoughtGraphDeltaLoadReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureDirectory()
            loadReportInternal()
        }
    }

    override suspend fun compact(policy: ThoughtGraphCompactionPolicy): ThoughtGraphCompactionReport =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectory()
                val looseNames = looseNamesInternal()
                val looseBefore = looseNames.size
                if (looseBefore < policy.triggerLooseDeltaCount) {
                    return@withLock ThoughtGraphCompactionReport(
                        looseBefore = looseBefore,
                        looseAfter = looseBefore,
                        segmentsWritten = 0,
                        deltasCompacted = 0,
                    )
                }

                val loose = looseNames.map { name -> name to readDeltaInternal(name) }
                    .sortedWith(compareBy<Pair<String, ThoughtGraphDelta>> { it.second.observedAt }
                        .thenBy { it.second.id.value })
                val eligibleCount = (loose.size - policy.retainLooseDeltaCount).coerceAtLeast(0)
                val eligible = loose.take(eligibleCount)
                val batches = buildCompactionBatches(eligible, policy.maxDeltasPerSegment)
                var segmentsWritten = 0
                var deltasCompacted = 0

                batches.forEach { batch ->
                    if (batch.size < 2) return@forEach
                    val segment = ThoughtGraphDeltaSegment.create(batch.map { it.second })
                    val segmentName = segmentFileName(segment.id)
                    val base = directory.resolve(segmentName)
                    val backup = directory.resolve("$segmentName.bak")
                    if (base.exists() || backup.exists()) {
                        require(readSegmentInternal(segmentName) == segment) {
                            "Thought graph segment identity collision"
                        }
                    } else {
                        val plaintext = ThoughtGraphDeltaSegmentCodec.encode(segment)
                        val encrypted = ThoughtGraphSegmentVaultCodec.encrypt(plaintext, segmentKey)
                        writeAtomic(segmentName, encrypted)
                        require(readSegmentInternal(segmentName) == segment) {
                            "Thought graph segment verification failed"
                        }
                        segmentsWritten++
                    }

                    // The verified segment is now the durable authority for these exact deltas.
                    // Deleting loose copies is safe and individually idempotent after any process kill.
                    batch.forEach { (name, _) ->
                        deleteAtomicFiles(name)
                        deltasCompacted++
                    }
                }

                val looseAfter = looseNamesInternal().size
                ThoughtGraphCompactionReport(
                    looseBefore = looseBefore,
                    looseAfter = looseAfter,
                    segmentsWritten = segmentsWritten,
                    deltasCompacted = deltasCompacted,
                )
            }
        }

    private fun loadReportInternal(): ThoughtGraphDeltaLoadReport {
        val byId = linkedMapOf<ThoughtGraphDeltaId, ThoughtGraphDelta>()
        val failures = mutableListOf<String>()

        segmentNamesInternal().forEach { name ->
            try {
                readSegmentInternal(name).deltas.forEach { delta ->
                    mergeExact(byId, delta, "segment:$name", failures)
                }
            } catch (_: Exception) {
                failures += name
            }
        }
        looseNamesInternal().forEach { name ->
            try {
                mergeExact(byId, readDeltaInternal(name), "loose:$name", failures)
            } catch (_: Exception) {
                failures += name
            }
        }

        return ThoughtGraphDeltaLoadReport(
            deltas = byId.values.sortedBy { it.id.value },
            unreadableEntries = failures.distinct().sorted(),
        )
    }

    private fun mergeExact(
        byId: MutableMap<ThoughtGraphDeltaId, ThoughtGraphDelta>,
        delta: ThoughtGraphDelta,
        source: String,
        failures: MutableList<String>,
    ) {
        val existing = byId[delta.id]
        if (existing == null) {
            byId[delta.id] = delta
        } else if (existing != delta) {
            failures += "identity-collision:${delta.id.value}:$source"
        }
    }

    private fun findExistingInternal(id: ThoughtGraphDeltaId): ThoughtGraphDelta? {
        val looseName = deltaFileName(id)
        val looseBase = directory.resolve(looseName)
        val looseBackup = directory.resolve("$looseName.bak")
        if (looseBase.exists() || looseBackup.exists()) return readDeltaInternal(looseName)

        segmentNamesInternal().forEach { name ->
            val match = readSegmentInternal(name).deltas.firstOrNull { it.id == id }
            if (match != null) return match
        }
        return null
    }

    private fun buildCompactionBatches(
        eligible: List<Pair<String, ThoughtGraphDelta>>,
        maxCount: Int,
    ): List<List<Pair<String, ThoughtGraphDelta>>> {
        val batches = mutableListOf<List<Pair<String, ThoughtGraphDelta>>>()
        var current = mutableListOf<Pair<String, ThoughtGraphDelta>>()
        var currentBytes = 0
        val maxBytes = ThoughtGraphDeltaSegmentCodec.MAX_PAYLOAD_BYTES - SEGMENT_HEADROOM_BYTES

        fun flush() {
            if (current.isNotEmpty()) batches += current.toList()
            current = mutableListOf()
            currentBytes = 0
        }

        eligible.forEach { entry ->
            val encodedSize = ThoughtGraphDeltaCodec.encode(entry.second).size + 4
            if (current.isNotEmpty() && (current.size >= maxCount || currentBytes + encodedSize > maxBytes)) {
                flush()
            }
            current += entry
            currentBytes += encodedSize
        }
        flush()
        return batches
    }

    private fun readDeltaInternal(name: String): ThoughtGraphDelta {
        require(name.endsWith(DELTA_FILE_SUFFIX)) { "Invalid thought graph delta file name" }
        val container = readAtomicBounded(name, ThoughtGraphDeltaVaultCodec.MAX_CONTAINER_BYTES)
        val plaintext = ThoughtGraphDeltaVaultCodec.decrypt(container, deltaKey)
        val delta = ThoughtGraphDeltaCodec.decode(plaintext)
        require(deltaFileName(delta.id) == name) { "Thought graph delta file/content identity mismatch" }
        return delta
    }

    private fun readSegmentInternal(name: String): ThoughtGraphDeltaSegment {
        require(name.endsWith(SEGMENT_FILE_SUFFIX)) { "Invalid thought graph segment file name" }
        val container = readAtomicBounded(name, ThoughtGraphSegmentVaultCodec.MAX_CONTAINER_BYTES)
        val plaintext = ThoughtGraphSegmentVaultCodec.decrypt(container, segmentKey)
        val segment = ThoughtGraphDeltaSegmentCodec.decode(plaintext)
        require(segmentFileName(segment.id) == name) { "Thought graph segment file/content identity mismatch" }
        return segment
    }

    private fun readAtomicBounded(name: String, maxBytes: Int): ByteArray =
        AtomicFile(directory.resolve(name)).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxBytes) { "Thought graph vault entry too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

    private fun writeAtomic(name: String, bytes: ByteArray) {
        val target = AtomicFile(directory.resolve(name))
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    private fun deleteAtomicFiles(name: String) {
        val base = directory.resolve(name)
        val backup = directory.resolve("$name.bak")
        if (base.exists()) check(base.delete()) { "Unable to delete compacted thought graph delta $name" }
        if (backup.exists()) check(backup.delete()) { "Unable to delete compacted thought graph backup $name.bak" }
    }

    private fun looseNamesInternal(): List<String> = vaultNamesWithSuffix(DELTA_FILE_SUFFIX)

    private fun segmentNamesInternal(): List<String> = vaultNamesWithSuffix(SEGMENT_FILE_SUFFIX)

    private fun vaultNamesWithSuffix(suffix: String): List<String> {
        val files = directory.listFiles() ?: throw IOException("Thought graph delta vault cannot be listed")
        return files
            .map { it.name.removeSuffix(".bak") }
            .filter { it.endsWith(suffix) }
            .distinct()
            .sorted()
    }

    private fun sameIdentityContent(first: ThoughtGraphDelta, second: ThoughtGraphDelta): Boolean =
        first.id == second.id &&
            first.sourceKey == second.sourceKey &&
            first.sourceRevision == second.sourceRevision &&
            first.nodeVersions == second.nodeVersions &&
            first.edgeVersions == second.edgeVersions

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Thought graph delta vault unavailable" }
    }

    private fun deltaFileName(id: ThoughtGraphDeltaId): String =
        digestFileName(id.value, DELTA_PREFIX, DELTA_FILE_SUFFIX, "delta")

    private fun segmentFileName(id: ThoughtGraphDeltaSegmentId): String =
        digestFileName(id.value, ThoughtGraphDeltaSegment.SEGMENT_PREFIX, SEGMENT_FILE_SUFFIX, "segment")

    private fun digestFileName(value: String, prefix: String, suffix: String, label: String): String {
        require(value.startsWith(prefix)) { "Invalid thought graph $label id prefix" }
        val digest = value.removePrefix(prefix)
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid thought graph $label id digest" }
        return "$digest$suffix"
    }

    private fun loadOrCreateKey(alias: String): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val DELTA_KEY_ALIAS = "lifeos.thought.graph.delta.v1"
        const val SEGMENT_KEY_ALIAS = "lifeos.thought.graph.segment.v1"
        const val DELTA_PREFIX = "thought-graph-delta:"
        const val DELTA_FILE_SUFFIX = ".tgdelta"
        const val SEGMENT_FILE_SUFFIX = ".tgsegment"
        const val SEGMENT_HEADROOM_BYTES = 1024 * 1024
    }
}
