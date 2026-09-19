package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.self.SelfObservationTrigger
import java.io.File
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelfObservationGoldDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val root: File
        get() = instrumentation.targetContext.filesDir.resolve("self-observation-gold")
    private val marker: File
        get() = root.resolve("authority.txt")

    @Test
    fun seedAuthoritativeSelfStateBeforeProcessDeath() = runBlocking {
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val app = readyApplication()
        awaitStartupSideEffects(app)
        val analysis = freshExplicitObservation(app)
        val snapshot = analysis.cycle.snapshot

        assertTrue(snapshot.authorityFingerprint.matches(Regex("[0-9a-f]{64}")))
        assertNotNull("Photon index identity must be observable for GOLD", snapshot.photon.indexFingerprint)

        val photonReport = app.kernel.photonStore.indexReport()
        assertTrue(
            "Photon index must be fully readable before process death",
            photonReport.unreadableRevisionFiles.isEmpty(),
        )
        val authorityLines = listOf(
            "authority=" + snapshot.authorityFingerprint,
            "world.revision=" + encodeLong(snapshot.world.worldHeadRevision),
            "world.fingerprint=" + encode(snapshot.world.worldHeadFingerprint),
            "equation.revision=" + encodeLong(snapshot.world.worldEquationRevision),
            "equation.version=" + encode(snapshot.world.worldEquationVersion),
            "equation.fingerprint=" + encode(snapshot.world.worldEquationFingerprint),
            "boot.id=" + encode(snapshot.world.bootCycleId),
            "boot.fingerprint=" + encode(snapshot.world.bootCycleFingerprint),
            "photon.entryCount=" + photonReport.entryCount,
        )
        val photonRefLines = photonReport.latestRefs.entries
            .sortedBy { it.key.value }
            .map { (id, ref) ->
                "photon.ref=" + encodePhotonId(id.value) + ":" + ref.revision
            }
        marker.writeText((authorityLines + photonRefLines).joinToString("\n"))
        assertTrue(marker.isFile)
        println("SELF_OBSERVATION_GOLD_SEED_AUTHORITY=" + snapshot.authorityFingerprint)
        println("SELF_OBSERVATION_GOLD_SEED_PHOTON_POPULATION=" +
            encode(snapshot.photon.indexFingerprint))
        println("SELF_OBSERVATION_GOLD_SEED_PHOTON_HEAD=" +
            encode(snapshot.photon.headFingerprint))
        println("SELF_OBSERVATION_GOLD_SEED_COGNITION=" +
            encode(snapshot.world.cognitiveSnapshotFingerprint))
    }

    @Test
    fun recoverAuthorityFingerprintAndObserveLiveState() = runBlocking {
        assertTrue("Self-observation GOLD seed marker missing", marker.isFile)
        val markerLines = marker.readLines()
        val expectedPhotonRefs = markerLines
            .filter { it.startsWith("photon.ref=") }
            .map { line ->
                val payload = line.removePrefix("photon.ref=")
                val separator = payload.lastIndexOf(':')
                assertTrue("Malformed Photon continuity marker: " + line, separator > 0)
                val id = PhotonId(decodePhotonId(payload.substring(0, separator)))
                val revision = payload.substring(separator + 1).toLong()
                id to revision
            }
        val expected = markerLines
            .filterNot { it.startsWith("photon.ref=") }
            .associate { line ->
                val separator = line.indexOf('=')
                assertTrue("Malformed self-observation GOLD marker line: " + line, separator > 0)
                line.substring(0, separator) to line.substring(separator + 1)
            }
        assertEquals(9, expected.size)
        assertEquals(expectedPhotonRefs.size, expectedPhotonRefs.map { it.first }.distinct().size)

        val app = readyApplication()
        awaitStartupSideEffects(app)
        val analysis = freshExplicitObservation(app)
        val snapshot = analysis.cycle.snapshot

        val actualComponents = linkedMapOf(
            "world.revision" to encodeLong(snapshot.world.worldHeadRevision),
            "world.fingerprint" to encode(snapshot.world.worldHeadFingerprint),
            "equation.revision" to encodeLong(snapshot.world.worldEquationRevision),
            "equation.version" to encode(snapshot.world.worldEquationVersion),
            "equation.fingerprint" to encode(snapshot.world.worldEquationFingerprint),
            "boot.id" to encode(snapshot.world.bootCycleId),
            "boot.fingerprint" to encode(snapshot.world.bootCycleFingerprint),
        )
        actualComponents.forEach { (key, actual) ->
            val expectedValue = expected[key]
            println("SELF_OBSERVATION_GOLD_COMPONENT_" + key.uppercase().replace('.', '_') +
                "_EXPECTED=" + expectedValue)
            println("SELF_OBSERVATION_GOLD_COMPONENT_" + key.uppercase().replace('.', '_') +
                "_ACTUAL=" + actual)
            assertEquals("Authority component changed after process death: " + key, expectedValue, actual)
        }

        // PhotonStore is a mutable append/revision authority. Process restart is allowed to append
        // new canonical evidence or advance existing heads, but it may never lose/regress the
        // durable live heads that existed before process death.
        val currentPhotonReport = app.kernel.photonStore.indexReport()
        assertTrue(
            "Photon index became unreadable after process death: " +
                currentPhotonReport.unreadableRevisionFiles.joinToString(","),
            currentPhotonReport.unreadableRevisionFiles.isEmpty(),
        )
        val seedEntryCount = requireNotNull(expected["photon.entryCount"]).toInt()
        assertTrue(
            "Photon index entry count regressed across process death",
            currentPhotonReport.entryCount >= seedEntryCount,
        )
        expectedPhotonRefs.forEach { (id, seedRevision) ->
            val recovered = currentPhotonReport.latestRefs[id]
            assertNotNull("Seed Photon disappeared after process death: " + id.value, recovered)
            assertTrue(
                "Seed Photon revision regressed after process death: " + id.value +
                    " seed=" + seedRevision + " recovered=" + recovered!!.revision,
                recovered.revision >= seedRevision,
            )
        }
        assertEquals(
            "Durable authority fingerprint changed after process death",
            expected["authority"],
            snapshot.authorityFingerprint,
        )
        assertNotNull("Live runtime telemetry must be re-observed after process death", snapshot.runtime.telemetry)

        // CognitiveSnapshot is a rebuildable checkpoint: it is useful live evidence but not part
        // of the restart-stable durable authority identity.
        println("SELF_OBSERVATION_GOLD_RECOVERED_PHOTON_POPULATION=" +
            encode(snapshot.photon.indexFingerprint))
        println("SELF_OBSERVATION_GOLD_RECOVERED_PHOTON_HEAD=" +
            encode(snapshot.photon.headFingerprint))
        println("SELF_OBSERVATION_GOLD_RECOVERED_COGNITION=" +
            encode(snapshot.world.cognitiveSnapshotFingerprint))
        // Photon revisions/population, CPU time, heap, UID traffic and rebuildable cognition may
        // legitimately advance/change while the control-plane authority chain remains exact.
        println("SELF_OBSERVATION_GOLD_RECOVERED_AUTHORITY=" + snapshot.authorityFingerprint)
        println("SELF_OBSERVATION_GOLD_LIVE_STATE=" + snapshot.stateFingerprint)
    }

    private suspend fun readyApplication(): LifeOsApplication {
        val app = instrumentation.targetContext.applicationContext as LifeOsApplication
        val startup = withTimeout(60_000L) {
            app.startupState.first {
                it.ready || it.phase == LifeOsProcessStartupPhase.FAILED
            }
        }
        assertTrue(
            "LIFEOS startup failed before self-observation GOLD: " + startup.failure,
            startup.ready,
        )
        return app
    }

    private suspend fun awaitStartupSideEffects(app: LifeOsApplication) {
        withTimeout(60_000L) {
            while (
                app.latestInitialDataBootstrap == null &&
                app.initialDataBootstrapFailure == null
            ) {
                delay(50L)
            }
        }
        withTimeout(60_000L) {
            while (
                app.latestLiveSourceSync == null &&
                app.liveSourceSyncFailure == null
            ) {
                delay(50L)
            }
        }
        // Let already-enqueued source/health projections settle before sealing the authority view.
        delay(250L)
    }

    private suspend fun freshExplicitObservation(
        app: LifeOsApplication,
    ): SelfObservationAnalysisState {
        val previousCapturedAt = app.selfObservationAnalysis.value?.cycle?.snapshot?.capturedAt
        app.refreshSelfObservation()
        return withTimeout(30_000L) {
            app.selfObservationAnalysis
                .filterNotNull()
                .first { state ->
                    state.cycle.trigger == SelfObservationTrigger.EXPLICIT_UI_REFRESH &&
                        (
                            previousCapturedAt == null ||
                                state.cycle.snapshot.capturedAt > previousCapturedAt
                            )
                }
        }
    }

    private fun encode(value: String?): String = value ?: "~"

    private fun encodeLong(value: Long?): String = value?.toString() ?: "~"

    private fun encodePhotonId(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodePhotonId(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
