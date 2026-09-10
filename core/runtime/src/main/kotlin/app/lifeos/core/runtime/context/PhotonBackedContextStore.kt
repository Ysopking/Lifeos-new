package app.lifeos.core.runtime.context

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRepository
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PhotonBackedContextStore(
    private val repository: PhotonRepository,
    private val scope: ContextScope,
    private val scopeId: String,
    private val actor: String,
) {
    init {
        require(scopeId.isNotBlank()) { "Context scope id must not be blank" }
        require(actor.isNotBlank()) { "Context actor must not be blank" }
    }

    private val mutex = Mutex()

    suspend fun record(
        target: Photon,
        kind: String = ContextKindClassifier.classify(target),
        tags: Set<String> = target.tags,
        contentTerms: Set<String> = ContextKindClassifier.contentTerms(target),
        active: Boolean = true,
        confidence: Double = target.confidence,
        recordedAt: Instant = Instant.now(),
    ): ContextWriteResult = mutex.withLock {
        require(target.mimeType != ContextRecordCodec.MIME_TYPE) {
            "Context records cannot recursively become context targets"
        }
        val recordId = ContextRecordCodec.recordId(scope, scopeId, target.id)
        val previousPhoton = repository.load(recordId)
        val previous = previousPhoton?.let { ContextRecordCodec.decode(it) }
        if (previous != null) {
            require(previous.scope == scope && previous.scopeId == scopeId) {
                "Context record scope mismatch"
            }
            if (target.revision < previous.targetRevision) {
                return@withLock ContextWriteResult.Stale(previous, target.revision)
            }
            val incomingFingerprint = ContextRecordCodec.targetFingerprint(target)
            if (
                target.revision == previous.targetRevision &&
                incomingFingerprint != previous.targetFingerprint
            ) {
                return@withLock ContextWriteResult.Conflict(previous, incomingFingerprint)
            }
            val canonicalTags = tags.filter { it.isNotBlank() }.toSortedSet()
            val canonicalTerms = contentTerms.filter { it.isNotBlank() }.toSortedSet()
            if (
                target.revision == previous.targetRevision &&
                incomingFingerprint == previous.targetFingerprint &&
                previous.kind == kind &&
                previous.tags == canonicalTags &&
                previous.contentTerms == canonicalTerms &&
                previous.active == active &&
                previous.confidence == confidence
            ) {
                return@withLock ContextWriteResult.Unchanged(previous)
            }
        }

        val nextPhoton = ContextRecordCodec.encode(
            previous = previousPhoton,
            scope = scope,
            scopeId = scopeId,
            target = target,
            kind = kind,
            tags = tags,
            contentTerms = contentTerms,
            active = active,
            confidence = confidence,
            recordedAt = recordedAt,
            actor = actor,
        )
        repository.save(nextPhoton)
        val entry = requireNotNull(ContextRecordCodec.decode(nextPhoton)) {
            "Encoded context record must decode"
        }
        ContextWriteResult.Applied(entry)
    }

    suspend fun setActive(
        targetPhotonId: PhotonId,
        active: Boolean,
        recordedAt: Instant = Instant.now(),
    ): ContextWriteResult = mutex.withLock {
        val recordId = ContextRecordCodec.recordId(scope, scopeId, targetPhotonId)
        val previousPhoton = repository.load(recordId) ?: return@withLock missing(targetPhotonId)
        val previous = requireNotNull(ContextRecordCodec.decode(previousPhoton)) {
            "Stored context record cannot be decoded"
        }
        if (previous.active == active) return@withLock ContextWriteResult.Unchanged(previous)
        val target = repository.load(targetPhotonId) ?: return@withLock missing(targetPhotonId)
        if (target.revision < previous.targetRevision) {
            return@withLock ContextWriteResult.Stale(previous, target.revision)
        }
        val targetFingerprint = ContextRecordCodec.targetFingerprint(target)
        if (
            target.revision == previous.targetRevision &&
            targetFingerprint != previous.targetFingerprint
        ) {
            return@withLock ContextWriteResult.Conflict(previous, targetFingerprint)
        }
        val next = ContextRecordCodec.encode(
            previous = previousPhoton,
            scope = scope,
            scopeId = scopeId,
            target = target,
            kind = previous.kind,
            tags = previous.tags,
            contentTerms = previous.contentTerms,
            active = active,
            confidence = previous.confidence,
            recordedAt = recordedAt,
            actor = actor,
        )
        repository.save(next)
        ContextWriteResult.Applied(requireNotNull(ContextRecordCodec.decode(next)))
    }

    suspend fun loadReport(): ContextLoadReport = mutex.withLock {
        val report = repository.loadReport()
        val entries = mutableListOf<ContextEntry>()
        val unreadable = report.unreadableFiles.toMutableList()
        report.photons
            .asSequence()
            .filter { it.mimeType == ContextRecordCodec.MIME_TYPE }
            .forEach { photon ->
                try {
                    val entry = ContextRecordCodec.decode(photon)
                    if (entry != null && entry.scope == scope && entry.scopeId == scopeId) {
                        entries += entry
                    }
                } catch (_: Exception) {
                    unreadable += "context-record:${photon.id.value}"
                }
            }
        ContextLoadReport(
            entries = entries.distinctBy { it.recordPhotonId }.sortedWith(ContextEntryOrdering),
            unreadableFiles = unreadable.distinct().sorted(),
        )
    }

    suspend fun loadAll(): List<ContextEntry> {
        val report = loadReport()
        check(report.complete) { "Context view incomplete: ${report.unreadableFiles.size} unreadable entries" }
        return report.entries
    }

    private fun missing(targetPhotonId: PhotonId): ContextWriteResult =
        throw IllegalStateException("Context target ${targetPhotonId.value} is not durably available")
}
