package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CognitionJournalIndexEntry(
    val kind: CognitionJournalKind,
    val sequence: Long,
    val stableId: String,
    val photonRef: PhotonRevisionRef,
    val recordedAt: Instant,
) {
    init {
        require(sequence > 0L) { "Cognition journal sequence must be positive" }
        require(stableId.isNotBlank()) { "Cognition journal stable id must not be blank" }
    }
}

data class CognitionJournalReservation(
    val kind: CognitionJournalKind,
    val sequence: Long,
    val stableId: String,
) {
    init {
        require(sequence > 0L) { "Cognition journal reservation sequence must be positive" }
        require(stableId.isNotBlank()) { "Cognition journal reservation stable id must not be blank" }
    }
}

data class CognitionJournalIndexSnapshot(
    val formatVersion: Int = FORMAT_VERSION,
    val entries: List<CognitionJournalIndexEntry> = emptyList(),
    val pendingReservations: List<CognitionJournalReservation> = emptyList(),
) {
    init {
        require(formatVersion == FORMAT_VERSION) { "Unsupported cognition journal index format" }
        require(entries.size <= MAX_ENTRIES) { "Cognition journal index too large" }
        require(entries.map { it.photonRef }.distinct().size == entries.size) {
            "Duplicate cognition journal Photon ref"
        }
        require(entries.map { it.kind to it.stableId }.distinct().size == entries.size) {
            "Duplicate cognition journal stable id"
        }
        require(entries.map { it.kind to it.sequence }.distinct().size == entries.size) {
            "Duplicate cognition journal sequence"
        }
        require(
            pendingReservations.map { it.kind to it.sequence }.distinct().size ==
                pendingReservations.size
        ) { "Duplicate pending cognition journal sequence" }
        require(
            pendingReservations.map { it.kind to it.stableId }.distinct().size ==
                pendingReservations.size
        ) { "Duplicate pending cognition journal stable id" }
    }

    fun entries(kind: CognitionJournalKind): List<CognitionJournalIndexEntry> {
        val values = entries.filter { it.kind == kind }
        return when (kind) {
            CognitionJournalKind.EVENT -> values.sortedBy { it.sequence }
            else -> values.sortedWith(
                compareBy<CognitionJournalIndexEntry> { it.recordedAt }
                    .thenBy { it.stableId }
                    .thenBy { it.photonRef.photonId.value }
            )
        }
    }

    fun refs(kind: CognitionJournalKind): List<PhotonRevisionRef> =
        entries(kind).map { it.photonRef }

    fun head(kind: CognitionJournalKind): Long =
        entries.asSequence().filter { it.kind == kind }.maxOfOrNull { it.sequence } ?: 0L

    fun reservedHead(kind: CognitionJournalKind): Long = maxOf(
        head(kind),
        pendingReservations.asSequence()
            .filter { it.kind == kind }
            .maxOfOrNull { it.sequence } ?: 0L,
    )

    fun size(kind: CognitionJournalKind): Long =
        entries.count { it.kind == kind }.toLong()

    fun canonical(): CognitionJournalIndexSnapshot = copy(
        entries = entries.sortedWith(
            compareBy<CognitionJournalIndexEntry> { it.kind.ordinal }
                .thenBy { it.sequence }
                .thenBy { it.stableId }
        ),
        pendingReservations = pendingReservations.sortedBy { it.kind.ordinal },
    )

    companion object {
        const val FORMAT_VERSION = 1
        const val MAX_ENTRIES = 250_000

        fun empty(): CognitionJournalIndexSnapshot = CognitionJournalIndexSnapshot()
    }
}

interface CognitionJournalIndexRepository {
    suspend fun load(): CognitionJournalIndexSnapshot?
    suspend fun save(snapshot: CognitionJournalIndexSnapshot)
}

data class CognitionJournalIndexReconciliationReport(
    val rebuilt: Boolean,
    val entryCount: Int,
    val recoveredPendingReservations: Int,
    val actualPhotonCount: Int,
) {
    init {
        require(entryCount >= 0)
        require(recoveredPendingReservations >= 0)
        require(actualPhotonCount >= 0)
    }
}

/**
 * Durable, reconstructible sequence/index projection for cognition journals.
 *
 * Photon payloads remain authoritative. This index only removes full-vault scans from normal
 * journal operations. Missing/corrupt state or a crash between Photon persistence and index commit
 * is reconciled from the revision-aware Photon index.
 */
class CognitionJournalIndex(
    private val repository: CognitionJournalIndexRepository,
    private val photons: PhotonRepository,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: CognitionJournalIndexSnapshot? = null

    suspend fun reserveNext(
        kind: CognitionJournalKind,
        stableId: String,
    ): CognitionJournalReservation =
        reserveBatch(kind, listOf(stableId)).single()

    suspend fun reserveBatch(
        kind: CognitionJournalKind,
        stableIds: List<String>,
    ): List<CognitionJournalReservation> = mutex.withLock {
        if (stableIds.isEmpty()) return@withLock emptyList()
        require(stableIds.all { it.isNotBlank() })
        require(stableIds.distinct().size == stableIds.size) {
            "Cognition journal batch contains duplicate stable ids"
        }
        val current = currentLocked()
        val occupied = current.entries.asSequence()
            .filter { it.kind == kind }
            .mapTo(mutableSetOf()) { it.stableId }
        occupied += current.pendingReservations.asSequence()
            .filter { it.kind == kind }
            .map { it.stableId }
        require(stableIds.none { it in occupied }) {
            "Cognition journal batch contains an already indexed or pending id"
        }
        val pendingHead = current.pendingReservations.asSequence()
            .filter { it.kind == kind }
            .maxOfOrNull { it.sequence }
            ?: 0L
        val start = maxOf(current.head(kind), pendingHead)
        val reservations = stableIds.mapIndexed { index, stableId ->
            CognitionJournalReservation(
                kind = kind,
                sequence = Math.addExact(start, index.toLong() + 1L),
                stableId = stableId,
            )
        }
        persistLocked(
            current.copy(
                pendingReservations = current.pendingReservations + reservations,
            )
        )
        reservations
    }

    suspend fun commit(
        reservation: CognitionJournalReservation,
        photonRef: PhotonRevisionRef,
        recordedAt: Instant,
    ) = commitBatch(listOf(Triple(reservation, photonRef, recordedAt)))

    suspend fun commitBatch(
        commits: List<Triple<CognitionJournalReservation, PhotonRevisionRef, Instant>>,
    ) = mutex.withLock {
        if (commits.isEmpty()) return@withLock
        val current = cached ?: currentLocked()
        val reservations = commits.map { it.first }
        require(reservations.all { it in current.pendingReservations }) {
            "Cognition journal batch contains a reservation that is not pending"
        }
        require(reservations.distinct().size == reservations.size) {
            "Duplicate cognition journal reservation in commit batch"
        }
        commits.forEach { (reservation, photonRef, _) ->
            require(
                photonRef.photonId ==
                    CognitionJournalIdentity.photonId(reservation.kind.tag, reservation.stableId)
            ) { "Cognition journal reservation/ref identity mismatch" }
            require(current.entries.none {
                it.kind == reservation.kind &&
                    (it.sequence == reservation.sequence || it.stableId == reservation.stableId)
            }) { "Cognition journal index commit conflict" }
        }
        val appended = commits.map { (reservation, photonRef, recordedAt) ->
            CognitionJournalIndexEntry(
                kind = reservation.kind,
                sequence = reservation.sequence,
                stableId = reservation.stableId,
                photonRef = photonRef,
                recordedAt = recordedAt,
            )
        }
        persistLocked(
            current.copy(
                entries = current.entries + appended,
                pendingReservations = current.pendingReservations - reservations.toSet(),
            )
        )
    }

    suspend fun snapshot(): CognitionJournalIndexSnapshot = mutex.withLock {
        currentLocked()
    }

    suspend fun entries(kind: CognitionJournalKind): List<CognitionJournalIndexEntry> =
        mutex.withLock { currentLocked().entries(kind) }

    suspend fun refs(kind: CognitionJournalKind): List<PhotonRevisionRef> =
        mutex.withLock { currentLocked().refs(kind) }

    suspend fun entry(
        kind: CognitionJournalKind,
        stableId: String,
    ): CognitionJournalIndexEntry? = mutex.withLock {
        currentLocked().entries.firstOrNull {
            it.kind == kind && it.stableId == stableId
        }
    }

    suspend fun size(kind: CognitionJournalKind): Long =
        mutex.withLock { currentLocked().size(kind) }

    suspend fun head(kind: CognitionJournalKind): Long =
        mutex.withLock { currentLocked().head(kind) }

    suspend fun reconcile(): CognitionJournalIndexReconciliationReport = mutex.withLock {
        val loaded = try {
            repository.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val actualRefs = actualJournalRefsLocked()
        val indexedRefs = loaded?.entries?.mapTo(linkedSetOf()) { it.photonRef }.orEmpty()
        val requiresRebuild =
            loaded == null ||
                loaded.pendingReservations.isNotEmpty() ||
                indexedRefs != actualRefs.toSet()

        if (!requiresRebuild) {
            val canonical = loaded!!.canonical()
            cached = canonical
            return@withLock CognitionJournalIndexReconciliationReport(
                rebuilt = false,
                entryCount = canonical.entries.size,
                recoveredPendingReservations = 0,
                actualPhotonCount = actualRefs.size,
            )
        }

        val recoveredPending = loaded?.pendingReservations?.size ?: 0
        val rebuilt = rebuildLocked(actualRefs)
        CognitionJournalIndexReconciliationReport(
            rebuilt = true,
            entryCount = rebuilt.entries.size,
            recoveredPendingReservations = recoveredPending,
            actualPhotonCount = actualRefs.size,
        )
    }

    private suspend fun currentLocked(): CognitionJournalIndexSnapshot {
        cached?.let { return it }

        val loaded = try {
            repository.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (loaded == null || loaded.pendingReservations.isNotEmpty()) {
            return rebuildLocked(actualJournalRefsLocked())
        }

        val actualRefs = actualJournalRefsLocked()
        if (loaded.entries.mapTo(linkedSetOf()) { it.photonRef } != actualRefs.toSet()) {
            return rebuildLocked(actualRefs)
        }

        return loaded.canonical().also { cached = it }
    }

    private suspend fun actualJournalRefsLocked(): List<PhotonRevisionRef> {
        if (photons is RevisionedPhotonRepository) {
            return photons.query(
                PhotonIndexQuery(
                    mimeTypes = setOf(COGNITION_JOURNAL_MIME),
                    allTags = setOf(COGNITION_JOURNAL_ROOT_TAG),
                    latestOnly = true,
                    limit = CognitionJournalIndexSnapshot.MAX_ENTRIES,
                )
            )
        }

        return photons.loadReport().photons
            .asSequence()
            .filter {
                it.mimeType == COGNITION_JOURNAL_MIME &&
                    COGNITION_JOURNAL_ROOT_TAG in it.tags
            }
            .map { PhotonRevisionRef(it.id, it.revision) }
            .sortedWith(compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision })
            .toList()
    }

    private suspend fun rebuildLocked(
        refs: List<PhotonRevisionRef>,
    ): CognitionJournalIndexSnapshot {
        val recovered = loadCognitionJournalPhotons(photons, refs).map(::recover)
        val entries = buildList {
            val recoveredEvents = recovered
                .filter { it.kind == CognitionJournalKind.EVENT }
                .sortedWith(
                    compareBy<RecoveredEntry> { requireNotNull(it.sequence) }
                        .thenBy { it.recordedAt }
                        .thenBy { it.stableId }
                        .thenBy { it.ref.photonId.value }
                )
            val payloadSequences = recoveredEvents.map { requireNotNull(it.sequence) }
            val payloadSequenceIsCanonical =
                payloadSequences == (1L..recoveredEvents.size.toLong()).toList()
            recoveredEvents.forEachIndexed { index, value ->
                add(
                    CognitionJournalIndexEntry(
                        kind = value.kind,
                        sequence = if (payloadSequenceIsCanonical) {
                            requireNotNull(value.sequence)
                        } else {
                            index.toLong() + 1L
                        },
                        stableId = value.stableId,
                        photonRef = value.ref,
                        recordedAt = value.recordedAt,
                    )
                )
            }

            CognitionJournalKind.values()
                .filterNot { it == CognitionJournalKind.EVENT }
                .forEach { kind ->
                    recovered.asSequence()
                        .filter { it.kind == kind }
                        .sortedWith(
                            compareBy<RecoveredEntry> { it.recordedAt }
                                .thenBy { it.stableId }
                                .thenBy { it.ref.photonId.value }
                        )
                        .forEachIndexed { index, value ->
                            add(
                                CognitionJournalIndexEntry(
                                    kind = kind,
                                    sequence = index.toLong() + 1L,
                                    stableId = value.stableId,
                                    photonRef = value.ref,
                                    recordedAt = value.recordedAt,
                                )
                            )
                        }
                }
        }

        val snapshot = CognitionJournalIndexSnapshot(entries = entries).canonical()
        persistLocked(snapshot)
        return snapshot
    }

    private fun recover(photon: Photon): RecoveredEntry {
        require(photon.mimeType == COGNITION_JOURNAL_MIME) {
            "Invalid cognition journal MIME"
        }
        require(COGNITION_JOURNAL_ROOT_TAG in photon.tags) {
            "Missing cognition journal root tag"
        }

        val kinds = CognitionJournalKind.values().filter {
            "cognition-journal-kind:${it.tag}" in photon.tags
        }
        require(kinds.size == 1) { "Ambiguous cognition journal kind" }
        val kind = kinds.single()
        val ref = PhotonRevisionRef(photon.id, photon.revision)

        return when (kind) {
            CognitionJournalKind.EVENT -> {
                val value = RuntimeEventJournalCodec.decode(photon.content)
                require(
                    photon.id == CognitionJournalIdentity.photonId(kind.tag, value.event.eventId)
                ) { "Runtime event journal identity mismatch" }
                RecoveredEntry(
                    kind = kind,
                    stableId = value.event.eventId,
                    ref = ref,
                    recordedAt = value.event.recordedAt,
                    sequence = value.offset,
                )
            }

            CognitionJournalKind.TRANSACTION -> {
                val value = CognitionTransactionCodec.decode(photon.content)
                require(
                    photon.id == CognitionJournalIdentity.photonId(kind.tag, value.transactionId)
                ) { "Transaction journal identity mismatch" }
                RecoveredEntry(
                    kind = kind,
                    stableId = value.transactionId,
                    ref = ref,
                    recordedAt = value.recordedAt,
                )
            }

            CognitionJournalKind.OUTCOME -> {
                val value = CognitionOutcomeCodec.decode(photon.content)
                val current = cognitionOutcomeStableId(value)
                val legacy = cognitionOutcomeLegacyStableId(value)
                when (photon.id) {
                    CognitionJournalIdentity.photonId(kind.tag, current),
                    CognitionJournalIdentity.photonId(kind.tag, legacy) -> Unit
                    else -> error("Outcome journal identity mismatch")
                }
                RecoveredEntry(
                    kind = kind,
                    stableId = current,
                    ref = ref,
                    recordedAt = value.recordedAt,
                )
            }

            CognitionJournalKind.TRIGGER -> {
                val value = CognitionTriggerCodec.decode(photon.content)
                require(
                    photon.id == CognitionJournalIdentity.photonId(kind.tag, value.id)
                ) { "Trigger journal identity mismatch" }
                RecoveredEntry(
                    kind = kind,
                    stableId = value.id,
                    ref = ref,
                    recordedAt = value.createdAt,
                )
            }
        }
    }

    private suspend fun persistLocked(snapshot: CognitionJournalIndexSnapshot) {
        val canonical = snapshot.canonical()
        repository.save(canonical)
        cached = canonical
    }

    private data class RecoveredEntry(
        val kind: CognitionJournalKind,
        val stableId: String,
        val ref: PhotonRevisionRef,
        val recordedAt: Instant,
        val sequence: Long? = null,
    )
}

internal fun cognitionOutcomeStableId(outcome: CognitiveOutcome): String =
    StableCognitiveIds.fingerprint(
        "cognition-outcome/v2",
        outcome.taskId.value,
        CognitionOutcomeCodec.encode(outcome.copy(recordedAt = Instant.EPOCH)),
    )

internal fun cognitionOutcomeLegacyStableId(outcome: CognitiveOutcome): String =
    StableCognitiveIds.fingerprint(
        "cognition-outcome/v1",
        outcome.taskId.value,
        CognitionOutcomeCodec.encode(outcome),
    )
