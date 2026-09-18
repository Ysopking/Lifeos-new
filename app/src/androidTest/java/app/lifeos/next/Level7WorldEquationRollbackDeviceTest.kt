package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.world.EncryptedProductiveWorldHeadRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Level7WorldEquationRollbackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val root: File
        get() = instrumentation.targetContext.filesDir.resolve("level7-equation-rollback")
    private val marker: File
        get() = root.resolve("rollback-expected.txt")

    @Test
    fun seedDegradedTrialHead() = runBlocking {
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val context = Level7DeviceFixtures.context(instrumentation.targetContext, root)
        val repo = EncryptedProductiveWorldHeadRepository(context)

        val v17 = Level7DeviceFixtures.worldHead(
            revision = 1L,
            snapshotId = "world-v17",
            predecessor = null,
            cycle = "cycle-v17",
            equationVersion = "v17",
        )
        assertTrue(repo.compareAndSet(null, v17))
        val v18 = Level7DeviceFixtures.worldHead(
            revision = 2L,
            snapshotId = "world-v18",
            predecessor = v17.activeSnapshot.snapshotId,
            cycle = "cycle-v18",
            equationVersion = "v18",
        )
        assertTrue(repo.compareAndSet(1L, v18))

        val rollback = Level7DeviceFixtures.worldHead(
            revision = 3L,
            snapshotId = v17.activeSnapshot.snapshotId,
            predecessor = v18.activeSnapshot.snapshotId,
            cycle = "cycle-rollback-v17",
            equationVersion = v17.equationVersion,
        )
        marker.writeText(
            listOf(
                rollback.activeSnapshot.snapshotId,
                rollback.equationVersion,
                rollback.fingerprint,
            ).joinToString("\n")
        )
    }

    @Test
    fun rollbackAfterProcessDeathRestoresExactV17WorldHead() = runBlocking {
        assertTrue(marker.isFile)
        val expected = marker.readLines()
        assertEquals(3, expected.size)
        val context = Level7DeviceFixtures.context(instrumentation.targetContext, root)
        val repo = EncryptedProductiveWorldHeadRepository(context)
        val degraded = requireNotNull(repo.load())
        assertEquals("v18", degraded.equationVersion)

        val rollback = Level7DeviceFixtures.worldHead(
            revision = degraded.revision + 1L,
            snapshotId = expected[0],
            predecessor = degraded.activeSnapshot.snapshotId,
            cycle = "cycle-rollback-v17",
            equationVersion = expected[1],
        )
        assertEquals(expected[2], rollback.fingerprint)
        assertTrue(repo.compareAndSet(degraded.revision, rollback))

        val restored = requireNotNull(EncryptedProductiveWorldHeadRepository(context).load())
        assertEquals(expected[0], restored.activeSnapshot.snapshotId)
        assertEquals(expected[1], restored.equationVersion)
        assertEquals(expected[2], restored.fingerprint)
    }
}
