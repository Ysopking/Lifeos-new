package app.lifeos.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonCodec
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexEntry
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.canonicalPhotonIndexOrder
import app.lifeos.core.model.matches
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Revision-preserving encrypted Photon vault.
 *
 * Photon payloads are authoritative. The encrypted metadata index is reconstructible and may never
 * be the only copy of a fact. Legacy v1/v2 <id>.photon heads are migrated idempotently into the
 * revision directory before the new index is published.
 */
class EncryptedPhotonStore(context: Context) : RevisionedPhotonRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val revisionDirectory = directory.resolve(REVISION_DIRECTORY)
    private val indexFile = directory.resolve(INDEX_FILE)
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val mutex = Mutex()

    @Volatile
    private var cachedIndex: IndexState? = null

    override suspend fun save(photon: Photon): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = ensureIndexLocked()
            val expected = index.head(photon.id)?.ref?.revision
            when (val result = saveRevisionLocked(photon, expected, index)) {
                is PhotonRevisionWriteResult.Created,
                is PhotonRevisionWriteResult.Advanced,
                is PhotonRevisionWriteResult.Idempotent -> Unit
                is PhotonRevisionWriteResult.Conflict ->
                    error("Photon revision conflict: ${result.reason}")
            }
        }
    }

    override suspend fun saveRevision(
        photon: Photon,
        expectedPreviousRevision: Long?,
    ): PhotonRevisionWriteResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            saveRevisionLocked(
                photon = photon,
                expectedPreviousRevision = expectedPreviousRevision,
                index = ensureIndexLocked(),
            )
        }
    }

    override suspend fun load(id: PhotonId): Photon? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val head = ensureIndexLocked().head(id) ?: return@withLock null
            if (head.tombstoned) return@withLock null
            readRevisionInternal(head.ref)
        }
    }

    override suspend fun load(ref: PhotonRevisionRef): Photon? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureIndexLocked()
            val file = revisionFile(ref)
            if (!exists(file)) return@withLock null
            readRevisionInternal(ref)
        }
    }

    override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureIndexLocked().head(id)?.takeUnless { it.tombstoned }?.ref
        }
    }

    override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> =
        withContext(Dispatchers.IO) {
            val index = mutex.withLock { ensureIndexLocked() }
            val ordering = when (query.order) {
                PhotonIndexOrder.IDENTITY ->
                    compareBy<PhotonIndexEntry> { it.ref.photonId.value }
                        .thenBy { it.ref.revision }

                PhotonIndexOrder.NEWEST_FIRST ->
                    compareByDescending<PhotonIndexEntry> { it.createdAt }
                        .thenBy { it.ref.photonId.value }
                        .thenByDescending { it.ref.revision }

                PhotonIndexOrder.OLDEST_FIRST ->
                    compareBy<PhotonIndexEntry> { it.createdAt }
                        .thenBy { it.ref.photonId.value }
                        .thenBy { it.ref.revision }

                PhotonIndexOrder.HIGHEST_SEMANTIC_MASS ->
                    compareByDescending<PhotonIndexEntry> { it.semanticMass }
                        .thenByDescending { it.createdAt }
                        .thenBy { it.ref.photonId.value }

                PhotonIndexOrder.HIGHEST_CONFIDENCE ->
                    compareByDescending<PhotonIndexEntry> { it.confidence }
                        .thenByDescending { it.createdAt }
                        .thenBy { it.ref.photonId.value }
            }
            val ordered = index.candidateEntries(query)
                .asSequence()
                .filter { it.matches(query) }
                .sortedWith(ordering)
                .toList()
            val startIndex = query.after?.let { cursor ->
                val cursorIndex = ordered.indexOfFirst { it.ref == cursor.lastRef }
                require(cursorIndex >= 0) {
                    "Photon index cursor is not present in the filtered result set"
                }
                cursorIndex + 1
            } ?: 0
            ordered
                .asSequence()
                .drop(startIndex)
                .take(query.limit)
                .map { it.ref }
                .toList()
        }

    override suspend fun indexReport(): PhotonIndexReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = ensureIndexLocked()
            PhotonIndexReport(
                formatVersion = INDEX_FORMAT_VERSION,
                entryCount = index.entries.size,
                livePhotonCount = index.entries.values.count { it.latest && !it.tombstoned },
                tombstonedPhotonCount = index.entries.values.count { it.latest && it.tombstoned },
                latestRefs = index.entries.values
                    .asSequence()
                    .filter { it.latest && !it.tombstoned }
                    .associate { it.ref.photonId to it.ref },
                unreadableRevisionFiles = index.unreadableRevisionFiles,
            )
        }
    }

    /**
     * Compatibility view: exactly one current live revision per PhotonId.
     * Historical revisions never appear here and therefore cannot look like duplicate Photon ids.
     */
    override suspend fun loadReport(): PhotonLoadReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = ensureIndexLocked()
            val failures = index.unreadableRevisionFiles.toMutableList()
            val photons = mutableListOf<Photon>()
            index.entries.values
                .asSequence()
                .filter { it.latest && !it.tombstoned }
                .sortedBy { it.ref.photonId.value }
                .forEach { entry ->
                    try {
                        photons += readRevisionInternal(entry.ref)
                    } catch (error: Exception) {
                        failures += revisionRelativePath(entry.ref)
                    }
                }
            PhotonLoadReport(
                photons = photons.sortedBy { it.provenance.createdAt },
                unreadableFiles = failures.distinct().sorted(),
            )
        }
    }

    override suspend fun loadAll(): List<Photon> {
        val report = loadReport()
        check(report.unreadableFiles.isEmpty()) {
            "Unreadable photons: ${report.unreadableFiles.size}"
        }
        return report.photons
    }

    /**
     * Compatibility delete becomes a durable tombstone. Historical revisions remain directly
     * addressable and the tombstone itself is encrypted so index rebuild preserves deletion state.
     */
    override suspend fun delete(id: PhotonId): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = ensureIndexLocked()
            val head = index.head(id) ?: return@withLock
            if (head.tombstoned) return@withLock

            writeTombstoneLocked(id, head.ref.revision)
            val updatedEntries = index.entries.toMutableMap()
            updatedEntries[head.ref] = head.copy(tombstoned = true)
            val updated = index.copy(entries = updatedEntries)
            writeIndexLocked(updated)
            cachedIndex = updated
        }
    }

    private fun saveRevisionLocked(
        photon: Photon,
        expectedPreviousRevision: Long?,
        index: IndexState,
    ): PhotonRevisionWriteResult {
        val ref = PhotonRevisionRef(photon.id, photon.revision)
        val head = index.head(photon.id)
        val previous = head?.let { readRevisionInternal(it.ref) }

        if (head != null && photon.revision == head.ref.revision) {
            return if (previous == photon) {
                PhotonRevisionWriteResult.Idempotent(photon = photon, previous = previous)
            } else {
                PhotonRevisionWriteResult.Conflict(
                    photon = photon,
                    previous = previous,
                    reason = "Revision ${photon.revision} already exists with different content",
                )
            }
        }

        if (head == null) {
            if (expectedPreviousRevision != null) {
                return PhotonRevisionWriteResult.Conflict(
                    photon = photon,
                    previous = null,
                    reason = "Expected previous revision $expectedPreviousRevision but no head exists",
                )
            }
            if (photon.revision != 1L) {
                return PhotonRevisionWriteResult.Conflict(
                    photon = photon,
                    previous = null,
                    reason = "First persisted revision must be 1",
                )
            }
        } else {
            if (photon.revision < head.ref.revision) {
                return PhotonRevisionWriteResult.Conflict(
                    photon = photon,
                    previous = previous,
                    reason = "Stale revision ${photon.revision} < head ${head.ref.revision}",
                )
            }
            if (expectedPreviousRevision != head.ref.revision) {
                return PhotonRevisionWriteResult.Conflict(
                    photon = photon,
                    previous = previous,
                    reason = "Expected previous revision $expectedPreviousRevision but head is ${head.ref.revision}",
                )
            }
            if (photon.revision != head.ref.revision + 1L) {
                return PhotonRevisionWriteResult.Conflict(
                    photon = photon,
                    previous = previous,
                    reason = "Revision must advance exactly once from ${head.ref.revision}",
                )
            }
        }

        writeRevisionLocked(photon)

        // If this id was tombstoned, removing the marker before publishing the new index is safe:
        // a crash causes rebuild to observe the newly written higher revision as the live head.
        if (head?.tombstoned == true) {
            AtomicFile(tombstoneFile(photon.id)).delete()
        }

        val entries = index.entries.toMutableMap()
        if (head != null) {
            entries[head.ref] = head.copy(latest = false, tombstoned = false)
        }
        entries[ref] = indexEntry(photon, latest = true, tombstoned = false)
        val updated = IndexState(entries = entries, unreadableRevisionFiles = emptyList())
        writeIndexLocked(updated)
        cachedIndex = updated

        return if (previous == null) {
            PhotonRevisionWriteResult.Created(photon)
        } else {
            PhotonRevisionWriteResult.Advanced(photon = photon, previous = previous)
        }
    }

    private fun ensureIndexLocked(): IndexState {
        cachedIndex?.let { return it }
        ensureDirectories()

        val loaded = if (exists(indexFile)) {
            runCatching { readIndexLocked() }.getOrNull()
        } else {
            null
        }
        if (loaded != null) {
            val reconciled = reconcileIndexTailLocked(loaded)
            cachedIndex = reconciled
            return reconciled
        }

        return rebuildIndexLocked().also { cachedIndex = it }
    }

    /**
     * Recovers the only incomplete write shapes possible with the V2 order:
     * revision payload -> index publish and tombstone payload -> index publish.
     *
     * Because advances are strictly +1, checking head+1 for every indexed Photon is sufficient and
     * does not require decrypting/scanning the full revision history.
     */
    private fun reconcileIndexTailLocked(index: IndexState): IndexState {
        var changed = false
        val entries = index.entries.toMutableMap()

        val indexedIds = entries.values.mapTo(linkedSetOf()) { it.ref.photonId }
        revisionDirectories().forEach { idDirectory ->
            val id = runCatching {
                PhotonId(idDirectory.name).also { safeId(it) }
            }.getOrNull() ?: return@forEach
            if (id in indexedIds) return@forEach

            var revision = 1L
            var previous: PhotonIndexEntry? = null
            while (revision > 0L) {
                val ref = PhotonRevisionRef(id, revision)
                if (!exists(revisionFile(ref))) break
                val photon = readRevisionInternal(ref)
                previous?.let { entries[it.ref] = it.copy(latest = false, tombstoned = false) }
                val current = indexEntry(photon, latest = true, tombstoned = false)
                entries[ref] = current
                previous = current
                changed = true
                if (revision == Long.MAX_VALUE) break
                revision += 1L
            }
            previous?.let { head ->
                val tombstonedRevision = readTombstoneLocked(id)
                if (tombstonedRevision == head.ref.revision) {
                    entries[head.ref] = head.copy(tombstoned = true)
                } else if (tombstonedRevision != null) {
                    AtomicFile(tombstoneFile(id)).delete()
                }
            }
        }

        index.entries.values
            .asSequence()
            .filter { it.latest }
            .sortedBy { it.ref.photonId.value }
            .forEach { indexedHead ->
                var head = entries[indexedHead.ref] ?: indexedHead
                var nextRevision = head.ref.revision + 1L
                while (nextRevision > 0L) {
                    val nextRef = PhotonRevisionRef(head.ref.photonId, nextRevision)
                    if (!exists(revisionFile(nextRef))) break
                    val nextPhoton = readRevisionInternal(nextRef)
                    entries[head.ref] = head.copy(latest = false, tombstoned = false)
                    head = indexEntry(nextPhoton, latest = true, tombstoned = false)
                    entries[nextRef] = head
                    changed = true
                    if (nextRevision == Long.MAX_VALUE) break
                    nextRevision += 1L
                }

                val tombstonedRevision = readTombstoneLocked(head.ref.photonId)
                if (tombstonedRevision == head.ref.revision && !head.tombstoned) {
                    head = head.copy(tombstoned = true)
                    entries[head.ref] = head
                    changed = true
                } else if (tombstonedRevision != null && tombstonedRevision != head.ref.revision) {
                    // Stale tombstone after a successfully advanced head cannot delete the new head.
                    AtomicFile(tombstoneFile(head.ref.photonId)).delete()
                    changed = true
                }
            }

        if (!changed) return index
        val reconciled = IndexState(entries = entries, unreadableRevisionFiles = emptyList())
        writeIndexLocked(reconciled)
        return reconciled
    }

    /**
     * Rebuild is authority-safe: only encrypted Photon revision payloads and encrypted tombstones
     * are consulted. The index itself is never trusted as source data.
     */
    private fun rebuildIndexLocked(): IndexState {
        ensureDirectories()
        migrateLegacyHeadsLocked()

        val entries = linkedMapOf<PhotonRevisionRef, PhotonIndexEntry>()
        val failures = mutableListOf<String>()

        revisionDirectories().forEach { idDir ->
            val id = runCatching { PhotonId(safeId(PhotonId(idDir.name))) }.getOrNull()
            if (id == null) {
                failures += idDir.relativeTo(directory).path
                return@forEach
            }

            revisionNames(idDir).forEach { name ->
                val revision = name.removeSuffix(PHOTON_SUFFIX).toLongOrNull()
                if (revision == null || revision <= 0L) {
                    failures += idDir.resolve(name).relativeTo(directory).path
                    return@forEach
                }
                val ref = PhotonRevisionRef(id, revision)
                try {
                    val photon = readRevisionInternal(ref)
                    entries[ref] = indexEntry(photon, latest = false, tombstoned = false)
                } catch (error: Exception) {
                    failures += revisionRelativePath(ref)
                }
            }
        }

        val byId = entries.values.groupBy { it.ref.photonId }
        byId.forEach { (id, values) ->
            val head = values.maxBy { it.ref.revision }
            val tombstonedRevision = readTombstoneLocked(id)
            if (tombstonedRevision != null && tombstonedRevision != head.ref.revision) {
                failures += tombstoneFile(id).relativeTo(directory).path
            }
            entries[head.ref] = head.copy(
                latest = true,
                tombstoned = tombstonedRevision == head.ref.revision,
            )
        }

        val rebuilt = IndexState(
            entries = entries,
            unreadableRevisionFiles = failures.distinct().sorted(),
        )
        writeIndexLocked(rebuilt)
        cleanupLegacyHeadsLocked()
        return rebuilt
    }

    private fun migrateLegacyHeadsLocked() {
        legacyPhotonNames().forEach { name ->
            val photon = runCatching { readLegacyPhotonInternal(name) }.getOrNull() ?: return@forEach
            val ref = PhotonRevisionRef(photon.id, photon.revision)
            val target = revisionFile(ref)
            if (!exists(target)) {
                writeRevisionLocked(photon)
            } else {
                require(readRevisionInternal(ref) == photon) {
                    "Legacy/revision Photon mismatch for ${ref.stableKey}"
                }
            }
        }
    }

    private fun cleanupLegacyHeadsLocked() {
        legacyPhotonNames().forEach { name ->
            AtomicFile(directory.resolve(name)).delete()
        }
    }

    private fun readIndexLocked(): IndexState {
        val plaintext = decryptBlob(
            container = readAtomic(indexFile, MAX_INDEX_CONTAINER_BYTES),
            expectedContainerVersion = INDEX_CONTAINER_VERSION,
            maxPlaintextBytes = MAX_INDEX_PLAINTEXT_BYTES,
        )
        return DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
            require(input.readInt() == INDEX_FORMAT_VERSION) { "Unsupported Photon index version" }
            val count = input.readInt()
            require(count in 0..MAX_INDEX_ENTRIES) { "Invalid Photon index entry count" }
            val entries = linkedMapOf<PhotonRevisionRef, PhotonIndexEntry>()
            repeat(count) {
                val id = PhotonId(input.text())
                safeId(id)
                val ref = PhotonRevisionRef(id, input.readLong())
                val createdAt = Instant.ofEpochSecond(
                    input.readLong(),
                    input.readInt().also { require(it in 0..999_999_999) }.toLong(),
                )
                val phase = PhotonPhase.valueOf(input.text())
                val mimeType = input.text()
                val tagCount = input.readInt().also { require(it in 0..MAX_TAGS) }
                val tags = buildSet { repeat(tagCount) { add(input.text()) } }
                val semanticMass = input.readDouble()
                val confidence = input.readDouble()
                val fingerprint = input.text()
                val latest = input.readBoolean()
                val tombstoned = input.readBoolean()
                val entry = PhotonIndexEntry(
                    ref = ref,
                    createdAt = createdAt,
                    phase = phase,
                    mimeType = mimeType,
                    tags = tags,
                    semanticMass = semanticMass,
                    confidence = confidence,
                    contentFingerprint = fingerprint,
                    latest = latest,
                    tombstoned = tombstoned,
                )
                require(entries.put(ref, entry) == null) { "Duplicate Photon index ref ${ref.stableKey}" }
            }
            val failureCount = input.readInt().also { require(it in 0..MAX_INDEX_ENTRIES) }
            val failures = buildList { repeat(failureCount) { add(input.text()) } }
            require(input.available() == 0) { "Trailing Photon index bytes" }
            validateIndex(entries.values)
            IndexState(entries, failures)
        }
    }

    private fun writeIndexLocked(index: IndexState) {
        validateIndex(index.entries.values)
        val plaintext = ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(INDEX_FORMAT_VERSION)
                val ordered = index.entries.values.canonicalPhotonIndexOrder()
                data.writeInt(ordered.size)
                ordered.forEach { entry ->
                    data.text(entry.ref.photonId.value)
                    data.writeLong(entry.ref.revision)
                    data.writeLong(entry.createdAt.epochSecond)
                    data.writeInt(entry.createdAt.nano)
                    data.text(entry.phase.name)
                    data.text(entry.mimeType)
                    data.writeInt(entry.tags.size)
                    entry.tags.sorted().forEach { tag -> data.text(tag) }
                    data.writeDouble(entry.semanticMass)
                    data.writeDouble(entry.confidence)
                    data.text(entry.contentFingerprint)
                    data.writeBoolean(entry.latest)
                    data.writeBoolean(entry.tombstoned)
                }
                data.writeInt(index.unreadableRevisionFiles.size)
                index.unreadableRevisionFiles.sorted().forEach { failure -> data.text(failure) }
            }
            output.toByteArray()
        }
        require(plaintext.size <= MAX_INDEX_PLAINTEXT_BYTES) { "Photon index too large" }
        writeAtomic(
            indexFile,
            encryptBlob(
                plaintext = plaintext,
                containerVersion = INDEX_CONTAINER_VERSION,
                maxPlaintextBytes = MAX_INDEX_PLAINTEXT_BYTES,
            ),
        )
    }

    private fun validateIndex(entries: Collection<PhotonIndexEntry>) {
        require(entries.size <= MAX_INDEX_ENTRIES)
        entries.groupBy { it.ref.photonId }.forEach { (id, values) ->
            require(values.count { it.latest } == 1) {
                "Photon index must have exactly one head for ${id.value}"
            }
            require(values.count { it.tombstoned } <= 1)
        }
    }

    private fun writeRevisionLocked(photon: Photon) {
        val ref = PhotonRevisionRef(photon.id, photon.revision)
        val target = revisionFile(ref)
        target.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) { "Photon revision directory unavailable" }
        }

        if (exists(target)) {
            require(readRevisionInternal(ref) == photon) {
                "Photon revision ${ref.stableKey} already exists with different content"
            }
            return
        }

        val plaintext = PhotonCodec.encode(photon)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(plaintext)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.writeInt(PHOTON_FORMAT_VERSION)
            it.writeInt(cipher.iv.size)
            it.write(cipher.iv)
            it.write(encrypted)
        }
        writeAtomic(target, output.toByteArray())
    }

    private fun readRevisionInternal(ref: PhotonRevisionRef): Photon {
        val file = revisionFile(ref)
        val photon = decryptPhoton(readAtomic(file, MAX_PHOTON_FILE_BYTES))
        require(photon.id == ref.photonId && photon.revision == ref.revision) {
            "Photon revision identity mismatch"
        }
        return photon
    }

    private fun readLegacyPhotonInternal(name: String): Photon {
        require(name.endsWith(PHOTON_SUFFIX))
        val photon = decryptPhoton(readAtomic(directory.resolve(name), MAX_PHOTON_FILE_BYTES))
        require(name == "${safeId(photon.id)}$PHOTON_SUFFIX") { "Legacy Photon identity mismatch" }
        return photon
    }

    private fun writeTombstoneLocked(id: PhotonId, revision: Long) {
        require(revision > 0L)
        val plaintext = ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(TOMBSTONE_FORMAT_VERSION)
                data.text(id.value)
                data.writeLong(revision)
            }
            output.toByteArray()
        }
        writeAtomic(
            tombstoneFile(id),
            encryptBlob(
                plaintext = plaintext,
                containerVersion = TOMBSTONE_CONTAINER_VERSION,
                maxPlaintextBytes = MAX_TOMBSTONE_BYTES,
            ),
        )
    }

    private fun readTombstoneLocked(id: PhotonId): Long? {
        val file = tombstoneFile(id)
        if (!exists(file)) return null
        val plaintext = decryptBlob(
            readAtomic(file, MAX_TOMBSTONE_CONTAINER_BYTES),
            expectedContainerVersion = TOMBSTONE_CONTAINER_VERSION,
            maxPlaintextBytes = MAX_TOMBSTONE_BYTES,
        )
        return DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
            require(input.readInt() == TOMBSTONE_FORMAT_VERSION)
            require(PhotonId(input.text()) == id)
            input.readLong().also {
                require(it > 0L)
                require(input.available() == 0)
            }
        }
    }

    private fun indexEntry(
        photon: Photon,
        latest: Boolean,
        tombstoned: Boolean,
    ): PhotonIndexEntry = PhotonIndexEntry(
        ref = PhotonRevisionRef(photon.id, photon.revision),
        createdAt = photon.provenance.createdAt,
        phase = photon.phase,
        mimeType = photon.mimeType,
        tags = photon.tags,
        semanticMass = photon.semanticMass,
        confidence = photon.confidence,
        contentFingerprint = sha256(photon.content.toByteArray(Charsets.UTF_8)),
        latest = latest,
        tombstoned = tombstoned,
    )

    private fun decryptPhoton(container: ByteArray): Photon {
        require(container.size <= MAX_PHOTON_FILE_BYTES) { "Photon file too large" }
        return DataInputStream(ByteArrayInputStream(container)).use { input ->
            val version = input.readInt()
            require(version in 1..PHOTON_FORMAT_VERSION) { "Unsupported Photon format" }
            val iv = ByteArray(input.readInt().also { require(it in 12..32) })
            input.readFully(iv)
            val encrypted = input.readBytes()
            require(encrypted.isNotEmpty()) { "Missing Photon ciphertext" }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            PhotonCodec.decode(cipher.doFinal(encrypted), version)
        }
    }

    private fun encryptBlob(
        plaintext: ByteArray,
        containerVersion: Int,
        maxPlaintextBytes: Int,
    ): ByteArray {
        require(plaintext.size <= maxPlaintextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(containerVersion)
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.write(encrypted)
            }
            output.toByteArray()
        }
    }

    private fun decryptBlob(
        container: ByteArray,
        expectedContainerVersion: Int,
        maxPlaintextBytes: Int,
    ): ByteArray {
        return DataInputStream(ByteArrayInputStream(container)).use { input ->
            require(input.readInt() == expectedContainerVersion) { "Unsupported encrypted container" }
            val iv = ByteArray(input.readInt().also { require(it in 12..32) })
            input.readFully(iv)
            val encrypted = input.readBytes()
            require(encrypted.isNotEmpty())
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            cipher.doFinal(encrypted).also {
                require(it.size <= maxPlaintextBytes) { "Encrypted payload too large" }
            }
        }
    }

    private fun readAtomic(file: File, maxBytes: Int): ByteArray {
        return AtomicFile(file).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxBytes) { "Encrypted file too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) { "Photon vault directory unavailable" }
        }
        val target = AtomicFile(file)
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    private fun ensureDirectories() {
        check(directory.isDirectory || directory.mkdirs()) { "Photon vault unavailable" }
        check(revisionDirectory.isDirectory || revisionDirectory.mkdirs()) {
            "Photon revision vault unavailable"
        }
    }

    private fun revisionDirectories(): List<File> =
        revisionDirectory.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }

    private fun revisionNames(idDirectory: File): List<String> =
        idDirectory.listFiles().orEmpty()
            .map { it.name.removeSuffix(".bak") }
            .filter { it.endsWith(PHOTON_SUFFIX) }
            .distinct()
            .sortedBy { it.removeSuffix(PHOTON_SUFFIX).toLongOrNull() ?: Long.MAX_VALUE }

    private fun legacyPhotonNames(): List<String> =
        directory.listFiles().orEmpty()
            .map { it.name.removeSuffix(".bak") }
            .filter { it.endsWith(PHOTON_SUFFIX) }
            .distinct()
            .sorted()

    private fun revisionFile(ref: PhotonRevisionRef): File =
        revisionDirectory.resolve(safeId(ref.photonId)).resolve("${ref.revision}$PHOTON_SUFFIX")

    private fun tombstoneFile(id: PhotonId): File =
        revisionDirectory.resolve(safeId(id)).resolve(TOMBSTONE_FILE)

    private fun revisionRelativePath(ref: PhotonRevisionRef): String =
        revisionFile(ref).relativeTo(directory).path

    private fun exists(file: File): Boolean =
        file.exists() || File("${file.path}.bak").exists()

    private fun safeId(id: PhotonId): String = id.value.also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid Photon ID" }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_INDEX_TEXT_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.text(): String {
        val size = readInt()
        require(size in 0..MAX_INDEX_TEXT_BYTES && size <= available())
        val bytes = ByteArray(size).also(::readFully)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun loadOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private data class IndexState(
        val entries: Map<PhotonRevisionRef, PhotonIndexEntry>,
        val unreadableRevisionFiles: List<String> = emptyList(),
    ) {
        private val latestEntries: List<PhotonIndexEntry> =
            entries.values.filter { it.latest }

        val headsById: Map<PhotonId, PhotonIndexEntry> =
            latestEntries.associateBy { it.ref.photonId }

        val liveRefsByTag: Map<String, Set<PhotonRevisionRef>> =
            buildLiveRefIndex { entry -> entry.tags }

        val liveRefsByPhase: Map<PhotonPhase, Set<PhotonRevisionRef>> =
            buildLiveRefIndex { entry -> setOf(entry.phase) }

        val liveRefsByMime: Map<String, Set<PhotonRevisionRef>> =
            buildLiveRefIndex { entry -> setOf(entry.mimeType) }

        init {
            require(headsById.size == latestEntries.size) {
                "Photon index contains multiple latest revisions for one Photon id"
            }
        }

        fun head(id: PhotonId): PhotonIndexEntry? = headsById[id]

        fun candidateEntries(query: PhotonIndexQuery): Collection<PhotonIndexEntry> {
            val candidateSets = mutableListOf<Set<PhotonRevisionRef>>()

            if (query.latestOnly && query.ids.isNotEmpty()) {
                candidateSets += query.ids
                    .mapNotNullTo(linkedSetOf()) { id -> headsById[id]?.ref }
            }

            if (query.latestOnly && !query.includeTombstoned) {
                query.allTags.forEach { tag ->
                    candidateSets += liveRefsByTag[tag].orEmpty()
                }
                if (query.phases.isNotEmpty()) {
                    candidateSets += query.phases
                        .flatMapTo(linkedSetOf()) { phase -> liveRefsByPhase[phase].orEmpty() }
                }
                if (query.mimeTypes.isNotEmpty()) {
                    candidateSets += query.mimeTypes
                        .flatMapTo(linkedSetOf()) { mime -> liveRefsByMime[mime].orEmpty() }
                }
            }

            if (candidateSets.isEmpty()) return entries.values
            val seed = candidateSets.minBy { it.size }
            if (seed.isEmpty()) return emptyList()
            val remaining = candidateSets.filterNot { it === seed }
            return seed
                .asSequence()
                .filter { ref -> remaining.all { ref in it } }
                .mapNotNull(entries::get)
                .toList()
        }

        private fun <K> buildLiveRefIndex(
            keys: (PhotonIndexEntry) -> Set<K>,
        ): Map<K, Set<PhotonRevisionRef>> {
            val mutable = linkedMapOf<K, MutableSet<PhotonRevisionRef>>()
            latestEntries
                .asSequence()
                .filterNot { it.tombstoned }
                .forEach { entry ->
                    keys(entry).forEach { key ->
                        mutable.getOrPut(key) { linkedSetOf() } += entry.ref
                    }
                }
            return mutable.mapValues { (_, refs) -> refs.toSet() }
        }
    }

    private companion object {
        const val ROOT_DIRECTORY = "photon-vault"
        const val REVISION_DIRECTORY = "revisions"
        const val INDEX_FILE = "photon-index.v1"
        const val TOMBSTONE_FILE = "head.tombstone"
        const val PHOTON_SUFFIX = ".photon"

        const val KEY_ALIAS = "lifeos.photon.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val PHOTON_FORMAT_VERSION = PhotonCodec.VERSION
        const val INDEX_FORMAT_VERSION = 1
        const val INDEX_CONTAINER_VERSION = 1
        const val TOMBSTONE_FORMAT_VERSION = 1
        const val TOMBSTONE_CONTAINER_VERSION = 1

        const val MAX_PHOTON_FILE_BYTES = 4 * 1024 * 1024 + 128
        const val MAX_INDEX_PLAINTEXT_BYTES = 64 * 1024 * 1024
        const val MAX_INDEX_CONTAINER_BYTES = MAX_INDEX_PLAINTEXT_BYTES + 64 * 1024
        const val MAX_INDEX_ENTRIES = 250_000
        const val MAX_INDEX_TEXT_BYTES = 4 * 1024 * 1024
        const val MAX_TAGS = 10_000
        const val MAX_TOMBSTONE_BYTES = 8 * 1024
        const val MAX_TOMBSTONE_CONTAINER_BYTES = MAX_TOMBSTONE_BYTES + 1024
    }
}
