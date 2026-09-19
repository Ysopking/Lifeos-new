package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.runtime.livedata.LiveDataHub
import app.lifeos.core.runtime.livedata.LiveDataPhotonIngress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame

class LiveDataHubRuntimeRegistryTest {
    @AfterTest
    fun cleanup() {
        LiveDataHubRuntimeRegistry.clearForTests()
    }

    @Test
    fun registryExposesExactCanonicalHubInstance() {
        val hub = LiveDataHub(
            photons = NoopRevisionedPhotonRepository(),
            ingress = LiveDataPhotonIngress { },
        )

        LiveDataHubRuntimeRegistry.install(hub)

        assertSame(hub, LiveDataHubRuntimeRegistry.currentOrNull())
    }

    private class NoopRevisionedPhotonRepository : RevisionedPhotonRepository {
        override suspend fun save(photon: Photon) = Unit

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult = PhotonRevisionWriteResult.Created(photon)

        override suspend fun load(id: PhotonId): Photon? = null
        override suspend fun load(ref: PhotonRevisionRef): Photon? = null
        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? = null
        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> = emptyList()
        override suspend fun indexReport(): PhotonIndexReport = PhotonIndexReport(
            formatVersion = 1,
            entryCount = 0,
            livePhotonCount = 0,
            tombstonedPhotonCount = 0,
            latestRefs = emptyMap(),
        )
        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(emptyList(), emptyList())
        override suspend fun loadAll(): List<Photon> = emptyList()
        override suspend fun delete(id: PhotonId) = Unit
    }
}
