package app.lifeos.core.runtime.livedata

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.RevisionedPhotonRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LiveDataHubTest {
    private val connector = LiveDataConnectorId("mail-calendar-files")
    private val account = LiveDataAccountKey("CaseSensitiveAccount@Example.test")
    private val at = Instant.parse("2026-09-19T02:10:00Z")

    @Test
    fun explicitGrantedPermissionAndCapabilityAdmitMessageDelta() = runBlocking {
        val repository = MemoryRevisionedPhotonRepository()
        val ingress = RecordingIngress(repository)
        val hub = LiveDataHub(repository, ingress)
        val accountPhoton = hub.observeAccount(observation())

        val delta = delta(LiveDataStreamKind.MESSAGE)
        val result = assertIs<LiveDataIngestResult.Accepted>(hub.ingest(delta))
        val photon = requireNotNull(repository.load(delta.photonId))

        assertEquals(accountPhoton.id, result.permissionSnapshotId)
        assertEquals(accountPhoton.revision, result.permissionSnapshotRevision)
        assertEquals(setOf(accountPhoton.id), photon.provenance.parentIds)
        assertTrue("live-data-stream:message" in photon.tags)
        assertTrue(
            "permission_snapshot=" + accountPhoton.id.value + "@" + accountPhoton.revision
                in photon.content
        )
    }

    @Test
    fun deniedPermissionFailsClosedWithoutPublishingDelta() = runBlocking {
        val repository = MemoryRevisionedPhotonRepository()
        val ingress = RecordingIngress(repository)
        val hub = LiveDataHub(repository, ingress)
        hub.observeAccount(
            observation(
                permissions = permissions(
                    messages = LiveDataPermissionState.DENIED,
                )
            )
        )
        val publishedBefore = ingress.published.size

        val result = assertIs<LiveDataIngestResult.Blocked>(
            hub.ingest(delta(LiveDataStreamKind.MESSAGE))
        )

        assertEquals(listOf("permission-not-granted:read_messages"), result.reasons)
        assertEquals(publishedBefore, ingress.published.size)
    }

    @Test
    fun missingCapabilityFailsClosedEvenWhenPermissionIsGranted() = runBlocking {
        val repository = MemoryRevisionedPhotonRepository()
        val ingress = RecordingIngress(repository)
        val hub = LiveDataHub(repository, ingress)
        hub.observeAccount(
            observation(capabilities = setOf(LiveDataCapability.MESSAGE_DELTAS))
        )

        val result = assertIs<LiveDataIngestResult.Blocked>(
            hub.ingest(delta(LiveDataStreamKind.FILE))
        )

        assertEquals(listOf("capability-unavailable:file_deltas"), result.reasons)
    }

    @Test
    fun changedAccountObservationAdvancesRevisionAndRevocationAppliesJit() = runBlocking {
        val repository = MemoryRevisionedPhotonRepository()
        val ingress = RecordingIngress(repository)
        val hub = LiveDataHub(repository, ingress)
        val first = hub.observeAccount(observation())
        val revoked = hub.observeAccount(
            observation(
                observedAt = at.plusSeconds(1),
                permissions = permissions(messages = LiveDataPermissionState.REVOKED),
            )
        )

        assertEquals(first.revision + 1L, revoked.revision)
        val result = assertIs<LiveDataIngestResult.Blocked>(
            hub.ingest(delta(LiveDataStreamKind.MESSAGE, observedAt = at.plusSeconds(2)))
        )
        assertEquals(listOf("permission-not-granted:read_messages"), result.reasons)
    }

    @Test
    fun exactRepeatedObservationIsIdempotentAndRepublishedForCrashRecovery() = runBlocking {
        val repository = MemoryRevisionedPhotonRepository()
        val ingress = RecordingIngress(repository)
        val hub = LiveDataHub(repository, ingress)
        val observation = observation()

        val first = hub.observeAccount(observation)
        val second = hub.observeAccount(observation)

        assertEquals(first, second)
        assertEquals(1L, second.revision)
        assertEquals(2, ingress.published.count { it.id == first.id })
    }

    @Test
    fun externalIdentityIsCaseSensitiveButRawAccountAndExternalIdsStayOutOfTags() = runBlocking {
        val upper = LiveDataAccountObservation(
            connectorId = connector,
            accountKey = LiveDataAccountKey("Account"),
            capabilities = LiveDataCapability.entries.toSet(),
            permissions = permissions(),
            observedAt = at,
        )
        val lower = upper.copy(accountKey = LiveDataAccountKey("account"))
        assertNotEquals(upper.photonId, lower.photonId)

        val repository = MemoryRevisionedPhotonRepository()
        val ingress = RecordingIngress(repository)
        val hub = LiveDataHub(repository, ingress)
        val accountPhoton = hub.observeAccount(upper)
        val externalId = "Secret/External/Identifier"
        val delta = delta(
            kind = LiveDataStreamKind.CALENDAR,
            accountKey = upper.accountKey,
            externalId = externalId,
        )
        assertIs<LiveDataIngestResult.Accepted>(hub.ingest(delta))
        val photon = requireNotNull(repository.load(delta.photonId))

        assertTrue(accountPhoton.tags.none { upper.accountKey.value in it })
        assertTrue(photon.tags.none { externalId in it })
        assertTrue(photon.tags.none { upper.accountKey.value in it })
    }

    @Test
    fun messageCalendarAndFileDeltasUseDistinctStableIdsAndDeleteCarriesNoPayload() = runBlocking {
        val repository = MemoryRevisionedPhotonRepository()
        val ingress = RecordingIngress(repository)
        val hub = LiveDataHub(repository, ingress)
        hub.observeAccount(observation())

        val message = delta(LiveDataStreamKind.MESSAGE)
        val calendar = delta(LiveDataStreamKind.CALENDAR)
        val fileDelete = delta(
            LiveDataStreamKind.FILE,
            operation = LiveDataDeltaOperation.DELETE,
            payload = null,
        )
        listOf(message, calendar, fileDelete).forEach {
            assertIs<LiveDataIngestResult.Accepted>(hub.ingest(it))
        }

        assertEquals(3, setOf(message.photonId, calendar.photonId, fileDelete.photonId).size)
        val deletePhoton = requireNotNull(repository.load(fileDelete.photonId))
        assertTrue("live-data-operation:delete" in deletePhoton.tags)
        assertTrue(deletePhoton.content.endsWith("payload="))
    }

    private fun observation(
        observedAt: Instant = at,
        capabilities: Set<LiveDataCapability> = LiveDataCapability.entries.toSet(),
        permissions: Map<LiveDataPermission, LiveDataPermissionState> = permissions(),
    ) = LiveDataAccountObservation(
        connectorId = connector,
        accountKey = account,
        capabilities = capabilities,
        permissions = permissions,
        observedAt = observedAt,
        sourceCursor = "cursor-1",
    )

    private fun permissions(
        messages: LiveDataPermissionState = LiveDataPermissionState.GRANTED,
        calendar: LiveDataPermissionState = LiveDataPermissionState.GRANTED,
        files: LiveDataPermissionState = LiveDataPermissionState.GRANTED,
    ): Map<LiveDataPermission, LiveDataPermissionState> = mapOf(
        LiveDataPermission.READ_MESSAGES to messages,
        LiveDataPermission.READ_CALENDAR to calendar,
        LiveDataPermission.READ_FILES to files,
    )

    private fun delta(
        kind: LiveDataStreamKind,
        accountKey: LiveDataAccountKey = account,
        externalId: String = "external-1",
        operation: LiveDataDeltaOperation = LiveDataDeltaOperation.UPSERT,
        payload: String? = if (operation == LiveDataDeltaOperation.UPSERT) "payload-$kind" else null,
        observedAt: Instant = at.plusSeconds(1),
    ) = LiveDataDelta(
        connectorId = connector,
        accountKey = accountKey,
        kind = kind,
        externalId = externalId,
        externalVersion = "v1",
        operation = operation,
        occurredAt = at,
        observedAt = observedAt,
        payload = payload,
    )

    private class RecordingIngress(
        private val repository: MemoryRevisionedPhotonRepository,
    ) : LiveDataPhotonIngress {
        val published = mutableListOf<Photon>()

        override suspend fun publish(photon: Photon) {
            repository.save(photon)
            published += photon
        }
    }

    private class MemoryRevisionedPhotonRepository : RevisionedPhotonRepository {
        private val values = linkedMapOf<PhotonRevisionRef, Photon>()

        override suspend fun save(photon: Photon) {
            val current = load(photon.id)
            when (
                val result = saveRevision(
                    photon,
                    expectedPreviousRevision = current?.revision,
                )
            ) {
                is PhotonRevisionWriteResult.Created,
                is PhotonRevisionWriteResult.Advanced,
                is PhotonRevisionWriteResult.Idempotent -> Unit
                is PhotonRevisionWriteResult.Conflict -> error(result.reason)
            }
        }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val current = load(photon.id)
            if (current != null && current.revision == photon.revision) {
                return if (current == photon) {
                    PhotonRevisionWriteResult.Idempotent(photon, current)
                } else {
                    PhotonRevisionWriteResult.Conflict(photon, current, "same revision differs")
                }
            }
            if (current == null) {
                if (expectedPreviousRevision != null || photon.revision != 1L) {
                    return PhotonRevisionWriteResult.Conflict(photon, null, "invalid first revision")
                }
                values[PhotonRevisionRef(photon.id, photon.revision)] = photon
                return PhotonRevisionWriteResult.Created(photon)
            }
            if (
                expectedPreviousRevision != current.revision ||
                photon.revision != current.revision + 1L
            ) {
                return PhotonRevisionWriteResult.Conflict(photon, current, "stale revision")
            }
            values[PhotonRevisionRef(photon.id, photon.revision)] = photon
            return PhotonRevisionWriteResult.Advanced(photon, current)
        }

        override suspend fun load(id: PhotonId): Photon? =
            values.values.filter { it.id == id }.maxByOrNull { it.revision }

        override suspend fun load(ref: PhotonRevisionRef): Photon? = values[ref]

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            load(id)?.let { PhotonRevisionRef(it.id, it.revision) }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            val latestById = values.values.groupBy { it.id }.mapValues { (_, values) ->
                values.maxBy { it.revision }
            }
            val source = if (query.latestOnly) latestById.values else values.values
            val filtered = source.asSequence()
                .filter { query.ids.isEmpty() || it.id in query.ids }
                .filter { query.phases.isEmpty() || it.phase in query.phases }
                .filter { query.mimeTypes.isEmpty() || it.mimeType in query.mimeTypes }
                .filter { it.tags.containsAll(query.allTags) }
                .map { PhotonRevisionRef(it.id, it.revision) }
                .toList()
            val ordered = when (query.order) {
                PhotonIndexOrder.IDENTITY -> filtered.sortedWith(
                    compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
                )
                else -> filtered.sortedWith(
                    compareBy<PhotonRevisionRef> {
                        requireNotNull(values[it]).provenance.createdAt
                    }.thenBy { it.photonId.value }.thenBy { it.revision }
                )
            }
            val start = query.after?.let {
                val index = ordered.indexOf(it.lastRef)
                require(index >= 0)
                index + 1
            } ?: 0
            return ordered.drop(start).take(query.limit)
        }

        override suspend fun indexReport(): PhotonIndexReport {
            val latest = values.values.groupBy { it.id }.mapValues { (_, values) ->
                values.maxBy { it.revision }
            }
            return PhotonIndexReport(
                formatVersion = 1,
                entryCount = values.size,
                livePhotonCount = latest.size,
                tombstonedPhotonCount = 0,
                latestRefs = latest.mapValues { (_, photon) ->
                    PhotonRevisionRef(photon.id, photon.revision)
                },
            )
        }

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(
                photons = values.values.groupBy { it.id }.values.map { it.maxBy { p -> p.revision } },
                unreadableFiles = emptyList(),
            )

        override suspend fun loadAll(): List<Photon> = loadReport().photons

        override suspend fun delete(id: PhotonId) {
            values.keys.filter { it.photonId == id }.forEach(values::remove)
        }
    }
}
