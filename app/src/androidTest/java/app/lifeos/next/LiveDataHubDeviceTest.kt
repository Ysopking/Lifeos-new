package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.runtime.livedata.LiveDataAccountKey
import app.lifeos.core.runtime.livedata.LiveDataAccountObservation
import app.lifeos.core.runtime.livedata.LiveDataCapability
import app.lifeos.core.runtime.livedata.LiveDataConnectorId
import app.lifeos.core.runtime.livedata.LiveDataDelta
import app.lifeos.core.runtime.livedata.LiveDataDeltaOperation
import app.lifeos.core.runtime.livedata.LiveDataIngestResult
import app.lifeos.core.runtime.livedata.LiveDataPermission
import app.lifeos.core.runtime.livedata.LiveDataPermissionState
import app.lifeos.core.runtime.livedata.LiveDataStreamKind
import app.lifeos.core.runtime.livedata.canonicalLiveDataMetadata
import app.lifeos.core.runtime.source.SourceMetadataRepository
import app.lifeos.next.kernel.KernelBootstrapStatus
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real AndroidKeyStore/canonical-ingress proof for M01 account permissions and live deltas. */
@RunWith(AndroidJUnit4::class)
class LiveDataHubDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: LifeOsApplication
        get() = instrumentation.targetContext.applicationContext as LifeOsApplication

    @Test
    fun accountPermissionSnapshotGatesCanonicalDeltaIngress() = runBlocking {
        awaitBoot()
        val hub = app.photonIngress.liveData
        val observedAt = Instant.now()
        val accountKey = LiveDataAccountKey(
            "device-m01-" + observedAt.epochSecond + "-" + observedAt.nano
        )
        val connector = LiveDataConnectorId("device-test")
        val granted = LiveDataAccountObservation(
            connectorId = connector,
            accountKey = accountKey,
            capabilities = LiveDataCapability.entries.toSet(),
            permissions = permissions(messages = LiveDataPermissionState.GRANTED),
            observedAt = observedAt,
            sourceCursor = "device-cursor-1",
        )

        val grantedPhoton = hub.observeAccount(granted)
        assertEquals(grantedPhoton, app.kernel.photonStore.load(grantedPhoton.id))
        assertFalse(grantedPhoton.content.contains(accountKey.value))
        assertTrue(grantedPhoton.tags.none { it.contains(accountKey.value) })

        val message = LiveDataDelta(
            connectorId = connector,
            accountKey = accountKey,
            kind = LiveDataStreamKind.MESSAGE,
            externalId = "private-message-id",
            externalVersion = "v1",
            operation = LiveDataDeltaOperation.UPSERT,
            occurredAt = observedAt,
            observedAt = observedAt,
            payload = "device-live-data-message",
        )
        val accepted = hub.ingest(message)
        assertTrue(accepted is LiveDataIngestResult.Accepted)
        val acceptedResult = accepted as LiveDataIngestResult.Accepted
        val persisted = app.kernel.photonStore.load(message.photonId)
        assertNotNull(persisted)
        assertEquals(setOf(grantedPhoton.id), persisted!!.provenance.parentIds)
        assertTrue(persisted.tags.none { it.contains("private-message-id") })
        val metadataRecord = SourceMetadataRepository(app.kernel.photonStore)
            .load(PhotonRevisionRef(message.photonId, 1L))
        assertNotNull(metadataRecord)
        assertEquals(message.metadata, metadataRecord!!.metadata)
        assertNotNull(app.kernel.photonStore.load(acceptedResult.metadataPhotonId))

        val revokeAt = Instant.now().let { current ->
            if (current.isAfter(observedAt)) current else observedAt.plusNanos(1)
        }
        val revoked = hub.observeAccount(
            granted.copy(
                observedAt = revokeAt,
                sourceCursor = "device-cursor-2",
                permissions = permissions(messages = LiveDataPermissionState.REVOKED),
            )
        )
        assertEquals(grantedPhoton.revision + 1L, revoked.revision)

        val blockedDelta = message.copy(
            externalVersion = "v2",
            occurredAt = revokeAt,
            observedAt = revokeAt,
            payload = "must-not-enter",
            metadata = canonicalLiveDataMetadata(
                connectorId = connector,
                accountKey = accountKey,
                kind = LiveDataStreamKind.MESSAGE,
                externalId = message.externalId,
                externalVersion = "v2",
                occurredAt = revokeAt,
                observedAt = revokeAt,
                mimeType = message.mimeType,
                privacyZone = message.metadata.privacyZone,
            ),
        )
        val blocked = hub.ingest(blockedDelta)
        assertTrue(blocked is LiveDataIngestResult.Blocked)
        assertNull(app.kernel.photonStore.load(blockedDelta.photonId))
    }

    private fun permissions(
        messages: LiveDataPermissionState,
    ): Map<LiveDataPermission, LiveDataPermissionState> = mapOf(
        LiveDataPermission.READ_MESSAGES to messages,
        LiveDataPermission.READ_CALENDAR to LiveDataPermissionState.GRANTED,
        LiveDataPermission.READ_FILES to LiveDataPermissionState.GRANTED,
    )

    private suspend fun awaitBoot() {
        val startup = withTimeout(BOOT_TIMEOUT_MS) {
            app.startupState.first {
                it.phase == LifeOsProcessStartupPhase.READY ||
                    it.phase == LifeOsProcessStartupPhase.FAILED
            }
        }
        if (startup.phase == LifeOsProcessStartupPhase.FAILED) {
            error("Process startup failed during M01 device test: " + startup.failure.orEmpty())
        }
        val kernel = withTimeout(BOOT_TIMEOUT_MS) {
            app.kernel.bootstrapState.first {
                it.status == KernelBootstrapStatus.READY ||
                    it.status == KernelBootstrapStatus.DEGRADED ||
                    it.status == KernelBootstrapStatus.FAILED
            }
        }
        if (kernel.status == KernelBootstrapStatus.FAILED) {
            error("Kernel boot failed during M01 device test: " + kernel.failureMessage.orEmpty())
        }
    }

    private companion object {
        const val BOOT_TIMEOUT_MS = 20_000L
    }
}
